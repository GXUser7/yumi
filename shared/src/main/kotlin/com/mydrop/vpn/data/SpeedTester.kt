package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.SpeedPhase
import com.mydrop.vpn.core.model.SpeedTestState
import com.mydrop.vpn.vpn.TunnelBridge
import com.mydrop.vpn.vpn.TunnelStream
import com.mydrop.vpn.vpn.XrayCore
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
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
 * When [throughTunnel] is true, streams open in-process through [XrayCore.TunnelBridge.dialTunnelTLS]
 * (`core.Dial`) rather than a loopback proxy inbound.
 */
class SpeedTester(
    private val logs: LogRepository,
    private val strings: Strings,
    private val bridge: TunnelBridge = XrayCore,
) {

    /**
     * Runs one test. When [throughTunnel] is true, traffic routes through the core's in-process
     * `core.Dial` TLS bridge instead of leaving directly from the app.
     */
    fun measure(throughTunnel: Boolean, serverName: String?): Flow<SpeedTestState> = channelFlow {
        // channelFlow rather than flow: the transfer phases run their streams as child coroutines,
        // and a plain flow builder refuses emissions from anywhere but its own.
        var state = SpeedTestState(
            phase = SpeedPhase.Latency,
            throughTunnel = throughTunnel,
            serverName = serverName,
        )
        send(state)

        try {
            val (latency, jitter) = measureLatency(throughTunnel)
            state = state.copy(
                latencyMillis = latency,
                jitterMillis = jitter,
                phase = SpeedPhase.Download,
            )
            send(state)

            val download = runTransfer(throughTunnel, upload = false) { rate, progress ->
                send(state.copy(liveBytesPerSecond = rate, phaseProgress = progress))
            }
            state = state.copy(
                downloadBytesPerSecond = download,
                phase = SpeedPhase.Upload,
                liveBytesPerSecond = 0,
                phaseProgress = 0f,
            )
            send(state)

            val upload = runTransfer(throughTunnel, upload = true) { rate, progress ->
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
                if (throughTunnel) R.string.log_speed_through_tunnel else R.string.log_speed_direct,
            )
            val summary = strings.get(
                R.string.log_speed_summary,
                path,
                download.megabits(),
                upload.megabits(),
                latency,
            )
            logs.info(summary)
            runCatching { android.util.Log.i(TAG, summary) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message
                ?: error::class.simpleName
                ?: strings.get(R.string.error_speed_no_connection)
            logs.warn(R.string.log_speed_failed, message)
            runCatching { android.util.Log.w(TAG, "speed test failed in ${state.phase}: $message", error) }
            send(state.copy(phase = SpeedPhase.Failed, liveBytesPerSecond = 0, message = message))
        }
    }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION")
    @Deprecated("probe-in removed", ReplaceWith("measure(probe != null, serverName)"))
    fun measure(probe: ProbeEndpoint?, serverName: String?): Flow<SpeedTestState> =
        measure(throughTunnel = probe != null, serverName = serverName)

    // -------------------------------------------------------------- Phases

    private interface Wire : Closeable {
        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)
        fun flush()
        fun readHead(): String
        fun read(buffer: ByteArray, offset: Int, length: Int): Int
    }

    /**
     * Direct TLS socket over a raw TCP socket. Closing is done on the raw socket so `SSLSocket.close`
     * cannot block waiting for a TLS `close_notify` flush when upload buffers are full.
     */
    private class DirectWire(private val raw: Socket, tls: SSLSocket) : Wire {
        private val input: InputStream = tls.inputStream
        private val output = tls.outputStream

        override fun write(data: ByteArray, offset: Int, length: Int) {
            output.write(data, offset, length)
        }

        override fun flush() {
            output.flush()
        }

        override fun readHead(): String = readHttpHead { input.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            input.read(buffer, offset, length)

        override fun close() {
            runCatching { raw.close() }
        }
    }

    /**
     * In-process TLS stream over [XrayCore.TunnelStream] with a small read-ahead buffer so
     * [readHead] avoids per-byte JNI calls while preserving any trailing body bytes in the chunk.
     */
    private class TunnelWire(private val stream: TunnelStream) : Wire {
        private var pending: ByteArray? = null
        private var pendingOffset = 0

        override fun write(data: ByteArray, offset: Int, length: Int) {
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                val slice = if (pos == 0 && remaining == data.size) {
                    data
                } else {
                    data.copyOfRange(pos, pos + remaining)
                }
                val written = stream.write(slice)
                if (written <= 0) throw IOException("tunnel stream closed on write")
                pos += written
                remaining -= written
            }
        }

        override fun flush() = Unit

        override fun readHead(): String = readHttpHead(::readSingleByte)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            val existing = pending
            if (existing != null && pendingOffset < existing.size) {
                val available = existing.size - pendingOffset
                val copy = minOf(length, available)
                System.arraycopy(existing, pendingOffset, buffer, offset, copy)
                pendingOffset += copy
                if (pendingOffset >= existing.size) {
                    pending = null
                    pendingOffset = 0
                }
                return copy
            }
            while (true) {
                val chunk = stream.read(maxOf(length, HEAD_CHUNK)) ?: return -1
                if (chunk.isEmpty()) continue
                val copy = minOf(length, chunk.size)
                System.arraycopy(chunk, 0, buffer, offset, copy)
                if (copy < chunk.size) {
                    pending = chunk
                    pendingOffset = copy
                }
                return copy
            }
        }

        private fun readSingleByte(): Int {
            while (true) {
                val buf = pending
                if (buf != null && pendingOffset < buf.size) {
                    val b = buf[pendingOffset++].toInt() and 0xFF
                    if (pendingOffset >= buf.size) {
                        pending = null
                        pendingOffset = 0
                    }
                    return b
                }
                val next = stream.read(HEAD_CHUNK) ?: return -1
                if (next.isEmpty()) continue
                pending = next
                pendingOffset = 0
            }
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }

    /** Median round trip and the spread around it, over one already-open connection. */
    private suspend fun measureLatency(throughTunnel: Boolean): Pair<Int, Int> =
        connect(throughTunnel).use { wire ->
            val samples = mutableListOf<Long>()
            repeat(LATENCY_SAMPLES) {
                ensureActive()
                val started = System.nanoTime()
                wire.write(request("GET", "/__down?bytes=0", keepAlive = true))
                wire.flush()
                val head = wire.readHead()
                checkStatus(head)
                drain(wire, contentLength(head) ?: 0)
                samples += System.nanoTime() - started
            }
            val median = samples.sorted()[samples.size / 2] / 1_000_000
            val jitter = samples.zipWithNext { a, b -> abs(a - b) }.average() / 1_000_000
            median.toInt() to jitter.roundToInt()
        }

    /**
     * One transfer phase: [STREAMS] connections moving bytes into a shared counter while this
     * coroutine samples it on a clock.
     */
    private suspend fun runTransfer(
        throughTunnel: Boolean,
        upload: Boolean,
        onSample: suspend (Long, Float) -> Unit,
    ): Long = coroutineScope {
        val moved = AtomicLong(0)
        val wires = CopyOnWriteArrayList<Wire>()
        val finishing = AtomicBoolean(false)
        val firstError = AtomicReference<Throwable?>(null)
        val started = System.nanoTime()

        val workers = List(STREAMS) {
            launch(Dispatchers.IO) {
                try {
                    val wire = connect(throughTunnel)
                    wires += wire
                    wire.use {
                        while (isActive && System.nanoTime() - started < BUDGET_NANOS) {
                            val complete =
                                if (upload) uploadOnce(wire, moved, started)
                                else downloadOnce(wire, moved, started)
                            if (!complete) break
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // Expected: the budget ran out and the sampler closed the wire underneath.
                } catch (error: Throwable) {
                    if (!finishing.get()) firstError.compareAndSet(null, error)
                }
            }
        }

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

        val elapsed = System.nanoTime() - countedFrom
        val counted = moved.get() - countedAt
        val total = moved.get()
        val sinceStart = System.nanoTime() - started

        finishing.set(true)
        wires.forEach { runCatching { it.close() } }
        workers.forEach { it.cancel() }
        val teardown = System.nanoTime()
        withTimeoutOrNull(TEARDOWN_TIMEOUT_MILLIS) { workers.joinAll() }

        runCatching {
            android.util.Log.i(
                TAG,
                "${if (upload) "upload" else "download"}: ${total / 1_000_000} MB, " +
                    "counted ${counted / 1_000_000} MB over ${elapsed / 1_000_000} ms, " +
                    "teardown ${(System.nanoTime() - teardown) / 1_000_000} ms",
            )
        }

        if (total == 0L) firstError.get()?.let { throw it }

        if (counted > 0 && warmedUp) rate(counted, elapsed) else rate(total, sinceStart)
    }

    /** One request's worth of body. False when it had to be cut short by the budget. */
    private fun downloadOnce(wire: Wire, moved: AtomicLong, started: Long): Boolean {
        wire.write(request("GET", "/__down?bytes=$DOWNLOAD_BYTES", keepAlive = true))
        wire.flush()

        val head = wire.readHead()
        checkStatus(head)
        var remaining = contentLength(head)
            ?: throw IllegalStateException(strings.get(R.string.error_speed_no_length))

        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            val read = wire.read(
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

    private fun uploadOnce(wire: Wire, moved: AtomicLong, started: Long): Boolean {
        wire.write(request("POST", "/__up", keepAlive = true, contentLength = UPLOAD_BYTES))
        val block = ByteArray(CHUNK) { (it * 31 + 7).toByte() }
        var remaining = UPLOAD_BYTES

        while (remaining > 0) {
            val chunk = minOf(CHUNK.toLong(), remaining).toInt()
            wire.write(block, 0, chunk)
            remaining -= chunk
            moved.addAndGet(chunk.toLong())
            if (System.nanoTime() - started >= BUDGET_NANOS) return false
        }
        wire.flush()
        checkStatus(wire.readHead())
        return true
    }

    // ----------------------------------------------------------- Transport

    private fun connect(throughTunnel: Boolean): Wire {
        if (throughTunnel) {
            val stream = bridge.dialTunnelTLS(HOST, PORT, HOST, CONNECT_TIMEOUT_MILLIS)
            return try {
                stream.setReadTimeout(READ_TIMEOUT_MILLIS)
                stream.setWriteTimeout(READ_TIMEOUT_MILLIS)
                TunnelWire(stream)
            } catch (error: Throwable) {
                runCatching { stream.close() }
                throw error
            }
        }

        val raw = Socket()
        raw.soTimeout = READ_TIMEOUT_MILLIS
        try {
            raw.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MILLIS)
            runCatching { raw.sendBufferSize = SEND_BUFFER_BYTES }
            val tls = (SSL.createSocket(raw, HOST, PORT, true) as SSLSocket).apply {
                soTimeout = READ_TIMEOUT_MILLIS
                startHandshake()
            }
            return DirectWire(raw, tls)
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

    private fun drain(wire: Wire, length: Long) {
        var remaining = length
        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            val read = wire.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    private suspend fun ensureActive() {
        if (!currentCoroutineContext().isActive) {
            throw CancellationException(strings.get(R.string.error_speed_cancelled))
        }
    }

    private fun rate(bytes: Long, nanos: Long): Long =
        if (nanos <= 0) 0 else (bytes * 1_000_000_000.0 / nanos).toLong()

    private fun Long.megabits(): String =
        String.format(Locale.US, "%.1f", this * 8 / 1_000_000.0)

    private companion object {
        const val TAG = "YumiSpeed"

        const val HOST = "speed.cloudflare.com"
        const val PORT = 443

        const val DOWNLOAD_BYTES = 99_000_000L
        const val UPLOAD_BYTES = 100_000_000L
        const val SEND_BUFFER_BYTES = 256 * 1024
        const val STREAMS = 6
        const val CHUNK = 64 * 1024
        const val HEAD_CHUNK = 4 * 1024

        const val BUDGET_NANOS = 8_000_000_000L
        const val SAMPLE_MILLIS = 150L
        const val SMOOTHING_NANOS = 1_000_000_000L
        const val WARMUP_MILLIS = 1_000L
        const val TEARDOWN_TIMEOUT_MILLIS = 1_000L

        const val LATENCY_SAMPLES = 5
        const val CONNECT_TIMEOUT_MILLIS = 6_000
        const val READ_TIMEOUT_MILLIS = 10_000

        val SSL: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

        fun readHttpHead(nextByte: () -> Int): String {
            val head = StringBuilder()
            var newlines = 0
            while (newlines < 2) {
                val byte = nextByte()
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
    }
}
