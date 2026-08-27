package com.mydrop.vpn.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Keeps the geo databases Xray routes by.
 *
 * sing-box read compiled rule-sets of a few tens of kilobytes each, small enough to ship inside the
 * APK. Xray reads `geoip.dat` and `geosite.dat`, and those are twenty-three megabytes and two —
 * a quarter of the download of the app itself, for data that is stale the week it is built. So they
 * are fetched once, on first run, and kept in app storage.
 *
 * Two properties matter more than speed here:
 *
 *  - **A missing file is not a degraded mode, it is a dead tunnel.** Xray resolves `geoip:` and
 *    `geosite:` references while parsing the configuration, and a reference it cannot resolve
 *    rejects the whole document rather than the one rule
 *    (`common/geodata/geodat_loader.go:16-25`). So [missing] exists to keep those references out of
 *    the configuration entirely until the files are really there.
 *
 *  - **A half-written file is worse than no file,** because it exists. Downloads land in a
 *    temporary name, are checked against the published SHA-256, and only then take the real one.
 *    A process killed mid-download leaves rubbish the next run overwrites, never a truncated
 *    database the core will choke on.
 */
class GeoAssetStore(private val context: Context) {

    /** One asset: where it comes from, what it is called here, and how to know it arrived whole. */
    data class Asset(val fileName: String, val url: String, val checksumUrl: String) {
        val partName: String get() = "$fileName.part"
    }

    val directory: File get() = File(context.filesDir, DIRECTORY)

    fun file(asset: Asset): File = File(directory, asset.fileName)

    /** True when every database the routing rules can reference is present. */
    fun available(): Boolean = missing().isEmpty()

    /**
     * Names of the databases that are not on disk.
     *
     * Presence only — the contents were verified when they were written, and re-hashing
     * twenty-three megabytes on the way into every connection would be a visible pause for a
     * question already answered.
     */
    fun missing(): List<String> = ASSETS.filterNot { file(it).isFile }.map { it.fileName }

    /** Bytes still to fetch, for a caller that wants to say so before starting. */
    fun pendingBytes(): Long = ASSETS.filterNot { file(it).isFile }.sumOf { APPROXIMATE_SIZES[it.fileName] ?: 0L }

    /**
     * Fetches whatever is missing.
     *
     * @param onProgress fraction of the whole job done, 0..1. Called often enough to animate and
     *   rarely enough not to flood the main thread.
     * @return null on success, or the message of whatever went wrong first. Failure leaves the
     *   store exactly as it was: assets that had already arrived stay, the one that failed is not
     *   half-written under its real name.
     */
    suspend fun download(onProgress: (Float) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        directory.mkdirs()

        val wanted = ASSETS.filterNot { file(it).isFile }
        if (wanted.isEmpty()) {
            onProgress(1f)
            return@withContext null
        }

        val total = wanted.sumOf { APPROXIMATE_SIZES[it.fileName] ?: 0L }.coerceAtLeast(1L)
        var done = 0L

        for (asset in wanted) {
            coroutineContext.ensureActive()
            val expected = runCatching { fetchChecksum(asset) }.getOrNull()
            val result = runCatching {
                fetch(asset, expected) { chunk ->
                    done += chunk
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            result.exceptionOrNull()?.let { error ->
                File(directory, asset.partName).delete()
                return@withContext error.message ?: error.javaClass.simpleName
            }
        }
        onProgress(1f)
        null
    }

    /**
     * The published checksum, or null when it could not be read.
     *
     * Null is not fatal: the file is still size-checked, and refusing to update because a
     * seventy-byte side file was unreachable would trade a real improvement for a theoretical one.
     */
    private fun fetchChecksum(asset: Asset): String? {
        val text = open(asset.checksumUrl).use { it.readBytes().decodeToString() }
        // `<hex>  <filename>`, the format sha256sum has written since forever.
        return text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length == 64 }
    }

    private fun fetch(asset: Asset, expected: String?, onChunk: (Long) -> Unit) {
        val part = File(directory, asset.partName)
        val digest = MessageDigest.getInstance("SHA-256")

        open(asset.url).use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onChunk(read.toLong())
                }
            }
        }

        if (part.length() < MINIMUM_PLAUSIBLE_BYTES) {
            part.delete()
            error("${asset.fileName}: получено ${part.length()} байт, это не база")
        }
        if (expected != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                error("${asset.fileName}: контрольная сумма не сошлась")
            }
        }

        val target = file(asset)
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            error("${asset.fileName}: не удалось переименовать во временный файл")
        }
    }

    private fun open(url: String): java.io.InputStream {
        var current = URL(url)
        // Redirects are followed by hand because the release URLs bounce to a storage host, and
        // `instanceFollowRedirects` will not carry a redirect across protocols — which is exactly
        // what these do on the way to the CDN.
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            when (val code = connection.responseCode) {
                in 200..299 -> return connection.inputStream
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(current, location ?: error("перенаправление без адреса"))
                }
                else -> {
                    connection.disconnect()
                    error("сервер ответил $code")
                }
            }
        }
        error("слишком много перенаправлений")
    }

    companion object {
        const val DIRECTORY = "geo"

        val GEOIP = Asset(
            fileName = "geoip.dat",
            url = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
            checksumUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat.sha256sum",
        )

        /**
         * Published as `dlc.dat` and read by the core as `geosite.dat`. The rename is the whole
         * difference between the two names, and doing it here keeps the core's expectation and the
         * project's release naming from having to agree.
         */
        val GEOSITE = Asset(
            fileName = "geosite.dat",
            url = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
            checksumUrl =
                "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat.sha256sum",
        )

        val ASSETS = listOf(GEOIP, GEOSITE)

        /** Only for showing progress; the real sizes come from the server. */
        private val APPROXIMATE_SIZES = mapOf(
            GEOIP.fileName to 23_000_000L,
            GEOSITE.fileName to 2_300_000L,
        )

        /** Below this it is an error page or a truncated stream, not a database. */
        private const val MINIMUM_PLAUSIBLE_BYTES = 100_000L

        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "Yumi"
    }
}
