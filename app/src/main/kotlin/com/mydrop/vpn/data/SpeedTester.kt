package com.mydrop.vpn.data

import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.SpeedPhase
import com.mydrop.vpn.core.model.SpeedTestState
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Measures throughput the way the user would experience it, and says which path it measured.
 *
 * Two decisions carry the accuracy of the whole thing.
 *
 * **Several streams at once.** One TCP connection through a proxy does not fill a modern link: it
 * is bounded by the window, the round trip and whatever the far end feels like giving a single
 * flow, so a single-stream test on a 200 Mbit line reports a confident fraction of it. Every real
 * speed test opens a handful of connections in parallel and adds them up, and so does this one.
 *
 * **The status line is checked.** The first version asked for a 400 MB body; the endpoint caps the
 * request at just under 100 MB and answers anything larger with a one-byte 403. Nothing looked at
 * the response code, so a refusal that arrived in 600 ms was measured as if it were the transfer,
 * and the screen reported nonsense with total confidence. A failed request now fails the test.
 *
 * Sockets are driven by hand rather than through `HttpURLConnection`: the proxy handshake needs a
 * `CONNECT` with credentials no stock client sends for us, the byte counting has to be continuous
 * rather than per-response, and a phase has to stop mid-body when its budget runs out.
 */
class SpeedTester(private val logs: LogRepository, private val strings: Strings) {

    /**
     * Runs one test. A non-null [probe] routes everything through the core, which is the only way
     * to measure the server rather than the connection the tunnel rides on — the app is excluded
     * from its own tunnel.
     */
    fun measure(probe: ProbeEndpoint?, serverName: String?): Flow<SpeedTestState> = channelFlow {
        // channelFlow rather than flow: the transfer phases run their streams as child coroutines,
        // and a plain flow builder refuses emissions from anywhere but its own.
        var state = SpeedTestState(
            phase = SpeedPhase.Latency,
            throughTunnel = probe != null,
            serverName = serverName,
        )
        send(state)

        try {
            val (latency, jitter) = measureLatency(probe)
            state = state.copy(
                latencyMillis = latency,
                jitterMillis = jitter,
                phase = SpeedPhase.Download,
            )
            send(state)

            val download = runTransfer(probe, upload = false) { rate, progress ->
                send(state.copy(liveBytesPerSecond = rate, phaseProgress = progress))
            }
            state = state.copy(
                downloadBytesPerSecond = download,
                phase = SpeedPhase.Upload,
                liveBytesPerSecond = 0,
                phaseProgress = 0f,
            )
            send(state)

            val upload = runTransfer(probe, upload = true) { rate, progress ->
                send(state.copy(liveBytesPerSecond = rate, phaseProgress = progress))
            }
            state = state.copy(
                uploadBytesPerSecond = upload,
                phase = SpeedPhase.Done,
                liveBytesPerSecond = 0,
                phaseProgress = 1f,
            )
            send(state)

            val path = strings.get(
                if (probe != null) R.string.log_speed_through_tunnel else R.string.log_speed_direct,
            )
            val summary = strings.get(
                R.string.log_speed_summary,
                path,
                download.megabits(),
                upload.megabits(),
                latency,
            )
            logs.info(summary)
            // Also to logcat: the in-app journal dies with the process, and these numbers are the
            // only way to tell a slow link from a broken measurement when reading logs off a
            // device.
            android.util.Log.i(TAG, summary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message
                ?: error::class.simpleName
                ?: strings.get(R.string.error_speed_no_connection)
            logs.warn(R.string.log_speed_failed, message)
            android.util.Log.w(TAG, "speed test failed in ${state.phase}: $message", error)
            send(state.copy(phase = SpeedPhase.Failed, liveBytesPerSecond = 0, message = message))
        }
    }.flowOn(Dispatchers.IO)

    // -------------------------------------------------------------- Phases

    /**
     * A connection plus the plain socket underneath it.
     *
     * Closing is done on the raw socket on purpose. `SSLSocket.close()` politely writes a
     * close-notify alert first, and during the upload phase the send buffer it needs is full — so
     * the close blocks until the far end drains it, for ten seconds and more. That stall used to
     * sit inside the measured window and between the last byte and the results on screen.
     */
    private class Wire(private val raw: Socket, val tls: SSLSocket) : Closeable {
        override fun close() {
            runCatching { raw.close() }
        }
    }

    /** Median round trip and the spread around it, over one already-open connection. */
    private suspend fun measureLatency(probe: ProbeEndpoint?): Pair<Int, Int> =
        connect(probe).use { wire ->
            val socket = wire.tls
            val samples = mutableListOf<Long>()
            repeat(LATENCY_SAMPLES) {
                ensureActive()
                val started = System.nanoTime()
                socket.outputStream.write(request("GET", "/__down?bytes=0", keepAlive = true))
                socket.outputStream.flush()
                val head = readHead(socket.inputStream)
                checkStatus(head)
                drain(socket.inputStream, contentLength(head) ?: 0)
                samples += System.nanoTime() - started
            }
            val median = samples.sorted()[samples.size / 2] / 1_000_000
            val jitter = samples.zipWithNext { a, b -> abs(a - b) }.average() / 1_000_000
            median.toInt() to jitter.roundToInt()
        }

    /**
     * One transfer phase: [STREAMS] connections moving bytes into a shared counter while this
     * coroutine samples it on a clock.
     *
     * The workers never emit — only the sampler does — so the reported rate is the sum of every
     * stream over one interval rather than whatever one of them happened to be doing.
     *
     * The first [WARMUP_MILLIS] are moved but not counted: connections spend that long opening
     * their windows, and folding the ramp into the average is how a fast link gets reported as a
     * middling one.
     */
    private suspend fun runTransfer(
        probe: ProbeEndpoint?,
        upload: Boolean,
        onSample: suspend (Long, Float) -> Unit,
    ): Long = coroutineScope {
        val moved = AtomicLong(0)
        val wires = CopyOnWriteArrayList<Wire>()
        // Set before the sockets are closed on purpose. Closing them is how the phase ends — it is
        // what unblocks the streams — so the exception every one of them then throws is the sound
        // of the phase finishing, not of it failing.
        val finishing = AtomicBoolean(false)
        val firstError = AtomicReference<Throwable?>(null)
        val started = System.nanoTime()

        val workers = List(STREAMS) {
            launch(Dispatchers.IO) {
                try {
                    val wire = connect(probe)
                    wires += wire
                    // Sized to take roughly [TARGET_REQUEST_NANOS] on this link: too small and the
                    // round trip waiting for each answer becomes the measurement, too large and a
                    // slow stream never finishes one inside the budget.
                    wire.use {
                        while (isActive && System.nanoTime() - started < BUDGET_NANOS) {
                            // A body that had to be cut short leaves the connection unusable for
                            // the next request, so the stream ends with it.
                            val complete =
                                if (upload) uploadOnce(wire.tls, moved, started)
                                else downloadOnce(wire.tls, moved, started)
                            if (!complete) break
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // Expected: the budget ran out and the sampler closed the socket underneath.
                } catch (error: Throwable) {
                    if (!finishing.get()) firstError.compareAndSet(null, error)
                }
            }
        }

        // Readings are taken over a sliding second rather than over the gap between two samples.
        // Through a proxy the bytes arrive in bursts: a 150 ms window catches either a burst or the
        // pause after it, so the honest instantaneous rate swings between double the real one and
        // zero, several times a second. That is unreadable — and it is not what the link is doing,
        // it is what the buffering is doing. The window still moves every 150 ms, so the trace
        // keeps its resolution.
        val window = ArrayDeque<Pair<Long, Long>>()
        window += started to 0L
        var countedFrom = started
        var countedAt = 0L
        var warmedUp = false

        while (System.nanoTime() - started < BUDGET_NANOS && workers.any { it.isActive }) {
            delay(SAMPLE_MILLIS)
            val now = System.nanoTime()
            val total = moved.get()

            window += now to total
            while (window.size > 1 && now - window.first().first > SMOOTHING_NANOS) {
                window.removeFirst()
            }
            val (windowStart, windowBytes) = window.first()

            onSample(
                rate(total - windowBytes, now - windowStart),
                ((now - started).toFloat() / BUDGET_NANOS).coerceIn(0f, 1f),
            )

            if (!warmedUp && now - started > WARMUP_MILLIS * 1_000_000L) {
                warmedUp = true
                countedFrom = now
                countedAt = total
            }
        }

        // The window closes here, with the budget — before any of the tearing down. Reading the
        // clock after it was how the upload figure got divided by a window that included ten
        // seconds of a socket refusing to close, and came out two and a half times too low.
        val elapsed = System.nanoTime() - countedFrom
        val counted = moved.get() - countedAt
        val total = moved.get()
        val sinceStart = System.nanoTime() - started

        // Closing is what unblocks a worker sitting in a blocking read or write; cancel alone
        // would leave it waiting out the socket timeout. The join is bounded because a stream that
        // will not let go is not a reason to keep the results off the screen.
        finishing.set(true)
        wires.forEach { runCatching { it.close() } }
        workers.forEach { it.cancel() }
        val teardown = System.nanoTime()
        withTimeoutOrNull(TEARDOWN_TIMEOUT_MILLIS) { workers.joinAll() }

        android.util.Log.i(
            TAG,
            "${if (upload) "upload" else "download"}: ${total / 1_000_000} MB, " +
                "counted ${counted / 1_000_000} MB over ${elapsed / 1_000_000} ms, " +
                "teardown ${(System.nanoTime() - teardown) / 1_000_000} ms",
        )

        // Bytes moved are a measurement, however few. Only a phase that transferred nothing at all
        // has an error worth reporting instead of a number.
        if (total == 0L) firstError.get()?.let { throw it }

        if (counted > 0 && warmedUp) rate(counted, elapsed) else rate(total, sinceStart)
    }

    /** One request's worth of body. False when it had to be cut short by the budget. */
    private fun downloadOnce(socket: Socket, moved: AtomicLong, started: Long): Boolean {
        socket.outputStream.write(
            request("GET", "/__down?bytes=$DOWNLOAD_BYTES", keepAlive = true),
        )
        socket.outputStream.flush()

        val head = readHead(socket.inputStream)
        checkStatus(head)
        var remaining = contentLength(head) ?: throw IllegalStateException(strings.get(R.string.error_speed_no_length))

        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            val read = socket.inputStream.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            if (read <= 0) return false
            remaining -= read
            moved.addAndGet(read.toLong())
            if (System.nanoTime() - started >= BUDGET_NANOS) return false
        }
        return true
    }

    /**
     * One POST, from its first byte to the server's answer.
     *
     * The answer is the whole point. A write returns as soon as the bytes sit in some buffer — the
     * socket's, the core's, the outbound's — and on this path those hold megabytes, so counting
     * writes reported a link two to three times faster than it was, over a chart that sat at zero
     * for half the run while the number stayed high. The response to a POST comes only once the far
     * end has the entire body, and that is the only thing here that means "delivered".
     */
    private fun uploadOnce(socket: Socket, moved: AtomicLong, started: Long): Boolean {
        socket.outputStream.write(
            request("POST", "/__up", keepAlive = true, contentLength = UPLOAD_BYTES),
        )
        // Noise rather than zeroes: a compressible payload would measure whatever compression sits
        // on the path instead of the path.
        val block = ByteArray(CHUNK) { (it * 31 + 7).toByte() }
        var remaining = UPLOAD_BYTES

        while (remaining > 0) {
            val chunk = minOf(CHUNK.toLong(), remaining).toInt()
            socket.outputStream.write(block, 0, chunk)
            remaining -= chunk
            moved.addAndGet(chunk.toLong())
            if (System.nanoTime() - started >= BUDGET_NANOS) return false
        }
        socket.outputStream.flush()
        checkStatus(readHead(socket.inputStream))
        return true
    }

    // ----------------------------------------------------------- Transport

    /**
     * A TLS connection to the measurement host, either straight out or through the core.
     *
     * Through the core it is a plain HTTP `CONNECT` first: the mixed inbound answers 200 and from
     * then on the socket is an ordinary tunnel for TLS to sit on. Only the target host is named in
     * cleartext, exactly as with any HTTPS proxy.
     */
    private fun connect(probe: ProbeEndpoint?): Wire {
        val raw = Socket()
        raw.soTimeout = READ_TIMEOUT_MILLIS
        try {
            if (probe == null) {
                raw.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MILLIS)
            } else {
                raw.connect(InetSocketAddress("127.0.0.1", probe.port), CONNECT_TIMEOUT_MILLIS)
                val credentials = Base64.getEncoder()
                    .encodeToString("${probe.username}:${probe.password}".toByteArray())
                raw.outputStream.write(
                    (
                        "CONNECT $HOST:$PORT HTTP/1.1\r\n" +
                            "Host: $HOST:$PORT\r\n" +
                            "Proxy-Authorization: Basic $credentials\r\n\r\n"
                        ).toByteArray(),
                )
                raw.outputStream.flush()
                val status = readHead(raw.inputStream).lineSequence().firstOrNull().orEmpty()
                check(" 200" in status) {
                    strings.get(R.string.error_speed_core_refused, status.trim())
                }
            }

            // A deliberately small send buffer. The upload phase counts bytes as they are written,
            // and a write returns once the data is in *some* buffer — so the size of those buffers
            // is the error in the reading. Megabytes of them let a phone report an upload it never
            // achieved; a bounded one keeps what has been written within a fraction of a second of
            // what has actually gone out.
            runCatching { raw.sendBufferSize = SEND_BUFFER_BYTES }

            val tls = (SSL.createSocket(raw, HOST, PORT, true) as SSLSocket).apply {
                soTimeout = READ_TIMEOUT_MILLIS
                startHandshake()
            }
            return Wire(raw, tls)
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    private fun request(
        method: String,
        path: String,
        keepAlive: Boolean,
        contentLength: Long? = null,
    ): ByteArray = buildString {
        append("$method $path HTTP/1.1\r\n")
        append("Host: $HOST\r\n")
        append("User-Agent: Yumi\r\n")
        append("Accept: */*\r\n")
        contentLength?.let { append("Content-Length: $it\r\n") }
        append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n")
    }.toByteArray()

    /**
     * Reads one header block — status line included — stopping at the blank line that ends it.
     *
     * Byte at a time on purpose: a buffered reader would swallow the start of the body, and in the
     * download phase that body is the thing being measured.
     */
    private fun readHead(stream: InputStream): String {
        val head = StringBuilder()
        var newlines = 0
        while (newlines < 2) {
            val byte = stream.read()
            if (byte < 0) break
            val symbol = byte.toChar()
            head.append(symbol)
            when (symbol) {
                '\n' -> newlines++
                '\r' -> Unit
                else -> newlines = 0
            }
        }
        return head.toString()
    }

    /** The check whose absence made the first version report a refusal as a measurement. */
    private fun checkStatus(head: String) {
        val status = head.lineSequence().firstOrNull().orEmpty().trim()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        if (code == null || code >= 300) {
            throw IllegalStateException(
                strings.get(
                    R.string.error_speed_server_said,
                    status.ifEmpty { strings.get(R.string.error_speed_nothing) },
                ),
            )
        }
    }

    private fun contentLength(head: String): Long? = head.lineSequence()
        .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toLongOrNull()

    private fun drain(stream: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    private suspend fun ensureActive() {
        if (!currentCoroutineContext().isActive) throw CancellationException(strings.get(R.string.error_speed_cancelled))
    }

    private fun rate(bytes: Long, nanos: Long): Long =
        if (nanos <= 0) 0 else (bytes * 1_000_000_000.0 / nanos).toLong()

    private fun Long.megabits(): String =
        String.format(Locale.US, "%.1f", this * 8 / 1_000_000.0)

    private companion object {
        /**
         * Cloudflare's speed endpoints: `__down` returns exactly the number of bytes asked for and
         * `__up` swallows whatever is posted. They are anycast, so the nearest edge answers and the
         * figure describes the link rather than the distance to one particular datacentre.
         */
        const val TAG = "YumiSpeed"

        const val HOST = "speed.cloudflare.com"
        const val PORT = 443

        /**
         * Just under the endpoint's ceiling: at 100 000 000 it stops serving and answers 403 with a
         * single byte. Streams that finish a body before the budget is up simply ask again.
         */
        const val DOWNLOAD_BYTES = 99_000_000L

        /** One long body per stream, cut short when the budget runs out. */
        const val UPLOAD_BYTES = 100_000_000L

        /**
         * Bounds how far the writes can run ahead of the wire. Small enough that the reading is
         * within a fraction of a second of the truth, large enough not to become the limit itself.
         */
        const val SEND_BUFFER_BYTES = 256 * 1024

        /**
         * Parallel connections per phase.
         *
         * Six rather than one because the round trip through a proxied server runs to hundreds of
         * milliseconds — 490 ms on the link this was tuned against. At that distance a single
         * stream spends most of its life waiting for acknowledgements, and the count of streams,
         * not the link, is what sets the number on screen.
         */
        const val STREAMS = 6

        const val CHUNK = 64 * 1024

        const val BUDGET_NANOS = 8_000_000_000L
        const val SAMPLE_MILLIS = 150L

        /** How much history each reading averages over. */
        const val SMOOTHING_NANOS = 1_000_000_000L

        const val WARMUP_MILLIS = 1_000L

        /** Long enough for a socket that closes normally, short enough not to be felt. */
        const val TEARDOWN_TIMEOUT_MILLIS = 1_000L

        const val LATENCY_SAMPLES = 5
        const val CONNECT_TIMEOUT_MILLIS = 6_000
        const val READ_TIMEOUT_MILLIS = 10_000

        val SSL: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    }
}
