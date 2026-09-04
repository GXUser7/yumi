package com.mydrop.vpn.data

import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.Release
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where new versions come from.
 *
 * The app is distributed from GitHub releases and nowhere else, so this asks GitHub directly
 * rather than a server of ours: there is no server of ours, and adding one would put a thing that
 * can go down between the user and an update they can already reach with a browser.
 *
 * Two ways of asking, and the second one is not paranoia. The API is pleasant — it carries the
 * release notes and the exact asset URLs — and it allows sixty unauthenticated requests an hour
 * *per address*. Behind carrier-grade NAT that address is shared by a good fraction of a mobile
 * network, so the pleasant path is also the one that answers 403 to somebody who has done nothing
 * wrong. The plain `releases/latest` page has no such limit and answers with a redirect naming the
 * tag, which is enough to tell whether an update exists and to build the download URL from the
 * naming convention. So: API first, redirect second, and an update is missed only when both fail.
 *
 * Blocking on purpose — every caller is already inside a coroutine on [kotlinx.coroutines.Dispatchers.IO],
 * and the same is true of [SubscriptionService] next door.
 */
class UpdateService(
    private val userAgent: String,
    private val strings: Strings,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The newest published release, or null when there is no asset for this phone's architecture.
     *
     * @param abi the ABI to look for, from `Build.SUPPORTED_ABIS`. A release carries one APK per
     *   architecture, and handing an arm64 phone the armeabi one wastes a download to fail at the
     *   end of it.
     */
    fun latest(abi: String): Release = runCatching { fromApi(abi) }
        .getOrElse { apiFailure ->
            runCatching { fromRedirect(abi) }.getOrElse {
                // The API's complaint is the more informative of the two — rate limiting says so
                // in as many words — so that is the one that surfaces.
                throw apiFailure
            }
        }

    private fun fromApi(abi: String): Release {
        val body = get("$API/releases/latest") { connection ->
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val release = json.parseToJsonElement(body).jsonObject
        val version = release["tag_name"]?.jsonPrimitive?.content
            ?: throw IllegalStateException(strings.get(R.string.error_update_unreadable))
        val notes = release["body"]?.jsonPrimitive?.contentOrEmpty().orEmpty()

        val asset = release["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it.name().endsWith(".apk") && it.name().contains(abi) }
            ?: throw IllegalStateException(strings.get(R.string.error_update_no_asset, abi))

        return Release(
            version = version.removePrefix("v"),
            notes = notes.trim(),
            apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
            sizeBytes = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * The version from the redirect `releases/latest` performs, and an asset URL assembled from
     * the naming the release script uses. No notes: the page would have to be scraped for them,
     * and a version number the user can act on beats a description they cannot.
     */
    private fun fromRedirect(abi: String): Release {
        val connection = (URL("$SITE/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("User-Agent", userAgent)
        }
        val tag = try {
            connection.responseCode
            connection.getHeaderField("Location")?.substringAfterLast("/tag/")
        } finally {
            connection.disconnect()
        }
        if (tag.isNullOrBlank() || tag.contains('/')) {
            throw IllegalStateException(strings.get(R.string.error_update_unreadable))
        }
        val version = tag.removePrefix("v")
        return Release(
            version = version,
            notes = "",
            apkUrl = "$SITE/releases/download/$tag/yumi-$version-$abi.apk",
            sizeBytes = 0L,
        )
    }

    /**
     * Streams the APK to [target], reporting progress as it goes.
     *
     * Written to a neighbouring `.part` file and renamed at the end, so an interrupted download
     * cannot leave a half-file that looks installable. [onProgress] is called with bytes so far and
     * the total, which is zero when the server did not say — the redirect path has no size either.
     */
    fun download(release: Release, target: File, onProgress: (Long, Long) -> Unit): File {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()

        var current = release.apkUrl
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    // GitHub hands the actual bytes off to another host, so this is the ordinary
                    // path rather than an edge case.
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException(strings.get(R.string.error_update_download, code.toString()))
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IllegalStateException(
                        strings.get(R.string.error_update_download, code.toString()),
                    )
                }

                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                var written = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                    }
                }
                // Compared against what was announced, not just against zero.
                //
                // The comment at the top of this method claims the `.part` file makes a half
                // download impossible. It does not: a connection cut mid-transfer ends the read
                // loop as normally as a complete one does, so the truncated file was renamed into
                // place and offered to the installer as a valid update. `.part` protects against
                // the process dying, which is a different accident.
                if (written <= 0L) {
                    throw IllegalStateException(strings.get(R.string.error_update_download, "0"))
                }
                if (total > 0 && written != total) {
                    throw IllegalStateException(
                        strings.get(R.string.error_update_truncated, written, total),
                    )
                }

                target.delete()
                check(part.renameTo(target)) {
                    strings.get(R.string.error_update_download, part.name)
                }
                return target
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(strings.get(R.string.error_update_unreadable))
    }

    private fun get(url: String, configure: (HttpURLConnection) -> Unit): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("User-Agent", userAgent)
            configure(this)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // GitHub says why in the body — "API rate limit exceeded" being the one worth
                // reading — and a bare status code would send somebody hunting for a fault in the
                // app that is not there.
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
                }.getOrNull()
                throw IllegalStateException(message ?: "HTTP $code")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.name(): String = this["name"]?.jsonPrimitive?.content.orEmpty()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrEmpty(): String =
        runCatching { content }.getOrDefault("")

    private companion object {
        const val SITE = "https://github.com/GXUser7/yumi"
        const val API = "https://api.github.com/repos/GXUser7/yumi"

        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5
    }
}
