package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory ring buffer of core log lines. Deliberately not persisted: logs from a VPN core
 * contain server addresses and request destinations, and writing that to disk by default would
 * be a privacy hazard the user never asked for.
 */
class LogRepository(private val capacity: Int = 2_000) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogEntry.Level, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _entries.update { current ->
            val appended = current + entry
            if (appended.size > capacity) appended.takeLast(capacity) else appended
        }
    }

    fun info(message: String) = log(LogEntry.Level.Info, message)
    fun warn(message: String) = log(LogEntry.Level.Warn, message)
    fun error(message: String) = log(LogEntry.Level.Error, message)
    fun debug(message: String) = log(LogEntry.Level.Debug, message)

    fun clear() {
        _entries.value = emptyList()
    }

    private fun MutableStateFlow<List<LogEntry>>.update(transform: (List<LogEntry>) -> List<LogEntry>) {
        while (true) {
            val current = value
            if (compareAndSet(current, transform(current))) return
        }
    }
}
