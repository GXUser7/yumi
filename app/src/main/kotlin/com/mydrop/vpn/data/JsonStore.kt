package com.mydrop.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A single serializable blob persisted to disk and exposed as a [StateFlow].
 *
 * Writes go through a temp file plus rename so a kill mid-write cannot leave a truncated
 * config behind — losing every server because the process died during a subscription refresh
 * would be far worse than losing the last edit.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    private val scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val writeLock = Mutex()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<T> = _state.asStateFlow()

    val value: T get() = _state.value

    fun update(transform: (T) -> T) {
        val updated = _state.updateAndGetValue(transform)
        scope.launch(Dispatchers.IO) { persist(updated) }
    }

    private fun MutableStateFlow<T>.updateAndGetValue(transform: (T) -> T): T {
        while (true) {
            val current = value
            val next = transform(current)
            if (compareAndSet(current, next)) return next
        }
    }

    private fun read(): T {
        if (!file.exists()) return defaultValue
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrElse {
                // A corrupt store must not brick the app; keep the bad file for diagnosis.
                runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
                defaultValue
            }
    }

    private suspend fun persist(value: T) = writeLock.withLock {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer, value))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }
}
