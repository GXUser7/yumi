package com.mydrop.vpn.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import com.mydrop.vpn.core.model.ProbeEndpoint
import java.io.File
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fetches and caches 256×256 OpenStreetMap tiles on disk (`cacheDir/map_tiles`) and in memory.
 *
 * Requests go through the running tunnel's loopback SOCKS inbound when available, falling back
 * to direct connection so city street tiles remain accessible under whitelist/DPI filtering and
 * survive offline once cached.
 */
class MapTileStore(
    context: Context,
    private val scope: CoroutineScope,
    private val probeEndpoint: () -> ProbeEndpoint? = { null },
) {
    private val cacheRoot = File(context.cacheDir, TILE_DIR)
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CAPACITY_TILES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val downloadGate = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun getTile(z: Int, x: Int, y: Int): Bitmap? {
        if (z !in MIN_ZOOM..MAX_ZOOM) return null
        val worldTiles = 1 shl z
        if (y !in 0 until worldTiles) return null
        val wrappedX = ((x % worldTiles) + worldTiles) % worldTiles
        val key = "$z/$wrappedX/$y"

        memoryCache.get(key)?.let { return it }

        val retryAt = failedUntil[key]
        if (retryAt != null && SystemClock.elapsedRealtime() < retryAt) return null

        if (!inFlight.add(key)) return null

        scope.launch(Dispatchers.IO) {
            try {
                loadOrFetchTile(key, z, wrappedX, y)
            } finally {
                inFlight.remove(key)
            }
        }
        return null
    }

    fun clearCache() {
        memoryCache.evictAll()
        failedUntil.clear()
        scope.launch(Dispatchers.IO) {
            runCatching { cacheRoot.deleteRecursively() }
            _revision.update { it + 1 }
        }
    }

    private suspend fun loadOrFetchTile(key: String, z: Int, x: Int, y: Int) {
        val file = File(cacheRoot, "$z/$x/$y.png")
        if (file.isFile && file.length() > 0L) {
            val cached = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (cached != null) {
                memoryCache.put(key, cached)
                _revision.update { it + 1 }
                return
            }
            runCatching { file.delete() }
        }

        downloadGate.withPermit {
            val bytes = fetchBytes(z, x, y)
            if (bytes == null) {
                failedUntil[key] = SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MS
                return
            }
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap == null) {
                failedUntil[key] = SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MS
                return
            }

            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, "${file.name}.tmp")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) {
                    file.delete()
                    if (!part.renameTo(file)) {
                        file.writeBytes(bytes)
                        part.delete()
                    }
                }
            }
            failedUntil.remove(key)
            memoryCache.put(key, bitmap)
            _revision.update { it + 1 }
        }
    }

    private fun fetchBytes(z: Int, x: Int, y: Int): ByteArray? {
        val url = "https://tile.openstreetmap.org/$z/$x/$y.png"
        val probe = probeEndpoint()
        if (probe != null) {
            val viaTunnel = runCatching { downloadWithProxy(url, probe) }.getOrNull()
            if (viaTunnel != null) return viaTunnel
        }
        return runCatching { downloadDirect(url) }.getOrNull()
    }

    private fun downloadWithProxy(url: String, probe: ProbeEndpoint): ByteArray? {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestingHost != LOOPBACK || requestingPort != probe.port) return null
                return PasswordAuthentication(probe.username, probe.password.toCharArray())
            }
        })
        val connection = URL(url).openConnection(
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, probe.port)),
        ) as HttpURLConnection
        return readImageResponse(connection)
    }

    private fun downloadDirect(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return readImageResponse(connection)
    }

    private fun readImageResponse(connection: HttpURLConnection): ByteArray? {
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", TILE_USER_AGENT)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size in 64..MAX_TILE_BYTES) bytes else null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TILE_DIR = "map_tiles"
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 17
        const val MEMORY_CAPACITY_TILES = 96
        const val MAX_CONCURRENT_DOWNLOADS = 4
        const val FAILURE_BACKOFF_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_TILE_BYTES = 512 * 1024
        const val LOOPBACK = "127.0.0.1"
        const val TILE_USER_AGENT = "YumiVPN/2.1 (Android; https://github.com/GXUser7/yumi)"
    }
}
