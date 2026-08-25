package com.mydrop.vpn.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A single serializable blob persisted to disk and exposed as a [StateFlow].
 *
 * Writes go through a temp file plus rename so a kill mid-write cannot leave a truncated
 * config behind — losing every server because the process died during a subscription refresh
 * would be far worse than losing the last edit.
 *
 * Two things about the write path are deliberate, and both were the other way round before.
 *
 * **What reaches the disk is read when the write runs, not captured when it was asked for.**
 * [update] used to hand its own result to a fresh coroutine, and two updates landing together —
 * a subscription refresh and a latency measurement, which is an everyday pairing — reached the
 * disk in whatever order the IO pool got to them. The older of the two could land last, and the
 * file then disagreed with what the app was showing until something else happened to save.
 *
 * **A failed write is reported.** It used to be swallowed whole, so a full disk lost every server
 * the user had added and said nothing anywhere.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    scope: CoroutineScope,
    private val onWriteFailure: (Throwable) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<T> = _state.asStateFlow()

    val value: T get() = _state.value

    /**
     * A nudge, never a payload. Each save writes whatever the state holds at that moment, so a
     * request arriving while one is already in flight is satisfied by it and can be dropped —
     * hence a conflated channel of one and a single consumer, which also makes the writes ordered
     * by construction rather than by luck.
     */
    private val saveRequests =
        Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch(Dispatchers.IO) {
            for (request in saveRequests) persist(_state.value)
        }
    }

    fun update(transform: (T) -> T) {
        _state.updateAndGetValue(transform)
        saveRequests.trySend(Unit)
    }

    private fun MutableStateFlow<T>.updateAndGetValue(transform: (T) -> T): T {
        while (true) {
            val current = value
            val next = transform(current)
            if (compareAndSet(current, next)) return next
        }
    }

    /**
     * Read synchronously, in the constructor, and therefore on whatever thread built the store —
     * in practice the main one, from `Application.onCreate`.
     *
     * That is a real cost at startup and it stays, because every alternative is worse. Several
     * callers need the stored value before any coroutine could have produced it:
     * `MainActivity.attachBaseContext` picks the interface language from it before the first frame
     * exists, `BootReceiver` decides whether to raise the tunnel from a broadcast, and the Quick
     * Settings tile answers a tap with no composition anywhere. Loading in the background would
     * hand all three the defaults and correct them a moment later — the wrong language for one
     * frame, and a tunnel that does not come up on a phone configured to bring it up.
     */
    private fun read(): T {
        if (!file.exists()) return defaultValue
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrElse {
                // A corrupt store must not brick the app; keep the bad file for diagnosis.
                runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
                defaultValue
            }
    }

    private fun persist(value: T) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer, value))
            if (!temp.renameTo(file)) {
                // Renaming onto an existing file fails on some Android storage layers. Copying is
                // not atomic, which is why it is the fallback rather than the path.
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure(onWriteFailure)
    }
}
