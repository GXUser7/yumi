package com.mydrop.vpn.data

import android.content.Context
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.ProbeEndpoint
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Keeps `geoip.dat` and `geosite.dat` where the core can read them.
 *
 * The replacement for `RuleSetStore`, and a straight loss in every dimension but one. sing-box read
 * pre-compiled `.srs` sets — three files, under 70 KB together, bundled in the APK and extracted in
 * milliseconds. Xray reads protobuf databases that are two orders of magnitude larger: `geoip.dat`
 * is around 8 MB and `geosite.dat` around 20. Shipping those inside the APK would more than undo
 * the 97 MB the smaller core saved, so they are fetched instead.
 *
 * What that costs is a first run without them, and the design follows from that: **a missing
 * database must never stop the tunnel.** Xray resolves every `geoip:`/`geosite:` reference while
 * parsing the document and refuses the whole configuration when one cannot be resolved
 * (`common/geodata/geodat_loader.go`) — not the rule, the configuration. So [available] is asked
 * before the document is built, the factory leaves those rules out when the answer is no, and the
 * user gets a tunnel that routes everything through the proxy instead of a tunnel that will not
 * start. Bypassing the local network keeps working either way; those ranges are spelled out from
 * the RFCs rather than looked up.
 *
 * Downloads go out through the tunnel when there is one, and directly when there is not. That order
 * is the wrong way round for most things and the right way round for this: the files live on
 * GitHub, and the networks where this app earns its keep are precisely the ones where GitHub does
 * not answer. Failing is still an acceptable outcome — without the databases the rules that name
 * them are left out of the configuration, the tunnel comes up carrying everything through the
 * proxy, and the journal says so.
 */
class GeoAssetStore(
    private val context: Context,
    private val logs: LogRepository,
    /**
     * How to reach the running tunnel, or null when there is none.
     *
     * The databases are published on GitHub, which is exactly the kind of host that is unreachable
     * on a network worth using this app on — so a direct refusal is retried through the tunnel,
     * the same way a subscription is. That is also why this runs after a connection rather than at
     * startup: on the networks that need the fallback, there is nothing to fall back to until the
     * tunnel is up.
     */
    private val probeEndpoint: () -> ProbeEndpoint? = { null },
) {

    /**
     * What is on disk, for the settings screen to show and for a person to act on.
     *
     * The databases arrive quietly in the background and their absence is quiet too — the routing
     * rules that name them are simply left out. Without something to look at, "route by rules" and
     * "block ads" can both be switched on and doing nothing, with no way to tell.
     */
    data class State(
        val ready: Boolean = false,
        val bytes: Long = 0,
        val refreshing: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        publish()
    }

    private fun publish(refreshing: Boolean = false) {
        _state.value = State(
            ready = available(),
            bytes = ASSETS.sumOf { File(directory, it.fileName).length() },
            refreshing = refreshing,
        )
    }

    /** Named by `xray.location.asset`, which is what [com.mydrop.vpn.vpn.XrayCore.setAssetPath] sets. */
    val directory: File get() = File(context.filesDir, DIRECTORY)

    /**
     * Whether the configuration may reference `geoip:` and `geosite:` at all.
     *
     * Size is checked as well as existence because the failure this has to catch is a half-written
     * file — a download killed with the process, or a disk that filled. A truncated database parses
     * as far as it goes and then takes the configuration down with it, which reads to the user as
     * "the tunnel stopped working" with nothing pointing at a file.
     */
    fun available(): Boolean = ASSETS.all { asset ->
        File(directory, asset.fileName).let { it.isFile && it.length() >= MINIMUM_BYTES }
    }

    /**
     * Fetches whatever is missing. Returns whether everything is present afterwards.
     *
     * Safe to call on every connection: an asset already on disk at a plausible size is left alone,
     * so the ordinary case costs one `stat` per file and no network at all.
     */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        publish(refreshing = true)
        try {
            val target = directory.apply { mkdirs() }
            ASSETS.forEach { asset ->
                val file = File(target, asset.fileName)
                // `force` is what the button in settings passes: a person asking again has a reason
                // — a database from six months ago routes by six-month-old categories — and the
                // background pass on every connection must not do that or it would re-download
                // thirty megabytes each time the tunnel comes up.
                if (!force && file.isFile && file.length() >= MINIMUM_BYTES) return@forEach
                runCatching { download(asset, file) }
                    .onSuccess { logs.info(R.string.log_geo_downloaded, asset.fileName) }
                    .onFailure { logs.warn(R.string.log_geo_failed, asset.fileName, it.message.orEmpty()) }
            }
            available()
        } finally {
            publish()
        }
    }

    /**
     * Downloads one database, verifies it, and only then puts it where the core will look.
     *
     * Three things have to be true before the file is moved into place, and each of them stands for
     * a way this has gone wrong somewhere before:
     *
     *  - **the transfer finished.** A stream cut halfway ends a read loop as normally as a complete
     *    one does; without comparing what arrived against what was announced, a truncated file looks
     *    like a finished download. (The app's own update path had exactly this bug.)
     *  - **the bytes are the ones published.** The checksum comes from the same release as the file,
     *    which is not protection against a hostile mirror — but it is protection against the far
     *    likelier corruption in transit, and it costs one small request.
     *  - **it lands atomically.** Written beside the target and renamed, so a process killed mid-
     *    download cannot leave the core reading a partial database on the next start.
     */
    private fun download(asset: Asset, target: File) {
        val expected = runCatching { fetchChecksum(asset) }.getOrNull()

        val part = File(target.parentFile, target.name + ".part")
        part.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var announced = -1L
        var written = 0L

        follow(asset.url) { connection ->
            announced = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                    }
                }
            }
        }

        try {
            check(written >= MINIMUM_BYTES) { "only $written bytes arrived" }
            check(announced <= 0 || written == announced) {
                "$written bytes of an announced $announced"
            }
            if (expected != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expected, ignoreCase = true)) { "checksum mismatch" }
            }
            target.delete()
            check(part.renameTo(target)) { "could not rename into place" }
        } finally {
            part.delete()
        }
    }

    /** Null when the release publishes no checksum, or it cannot be read; see [download]. */
    private fun fetchChecksum(asset: Asset): String? {
        var text: String? = null
        follow(asset.url + ".sha256sum") { connection ->
            text = connection.inputStream.bufferedReader().use { it.readText() }
        }
        // `<hex>  <filename>`, which is what sha256sum writes.
        return text?.trim()?.substringBefore(' ')?.takeIf { it.length == 64 }
    }

    /**
     * Walks redirects by hand, because release assets are handed off to another host and
     * `HttpURLConnection` will not carry a request across that hop on its own.
     */
    private fun follow(from: String, body: (HttpURLConnection) -> Unit) {
        val probe = probeEndpoint()
        var current = from
        repeat(MAX_REDIRECTS) {
            val opened = if (probe == null) {
                URL(current).openConnection()
            } else {
                installProbeAuthenticator(probe)
                URL(current).openConnection(
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, probe.port)),
                )
            }
            val connection = (opened as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("redirect without a location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                check(code in 200..299) { "HTTP $code" }
                body(connection)
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("too many redirects")
    }

    /** See the same method in [SubscriptionService]: credentials for our own loopback, nothing else. */
    private fun installProbeAuthenticator(probe: ProbeEndpoint) {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestingHost != LOOPBACK || requestingPort != probe.port) return null
                return PasswordAuthentication(probe.username, probe.password.toCharArray())
            }
        })
    }

    private data class Asset(val fileName: String, val url: String)

    private companion object {
        const val DIRECTORY = "xray-assets"

        const val LOOPBACK = "127.0.0.1"

        /**
         * Smaller than either real database by a wide margin, and larger than any error page a
         * proxy might hand back in their place. The point is to reject something implausible, not
         * to pin a size that will drift with every release.
         */
        const val MINIMUM_BYTES = 100_000L

        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val MAX_REDIRECTS = 5

        /**
         * The community databases Xray's own documentation points at. `dlc.dat` is what the domain
         * list is published as; the core looks for it under the name `geosite.dat`, so it is
         * renamed on the way in rather than referenced by its published name.
         */
        val ASSETS = listOf(
            Asset(
                fileName = "geoip.dat",
                url = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
            ),
            Asset(
                fileName = "geosite.dat",
                url = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
            ),
        )
    }
}
