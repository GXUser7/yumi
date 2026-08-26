package com.mydrop.vpn.data

import androidx.annotation.StringRes
import com.mydrop.vpn.core.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory ring buffer of core log lines. Deliberately not persisted: logs from a VPN core
 * contain server addresses and request destinations, and writing that to disk by default would
 * be a privacy hazard the user never asked for.
 *
 * Most callers pass a resource id rather than a sentence, so the journal speaks the language the
 * user chose. Entries keep the wording they were written with when the language changes later —
 * a journal is a record of what happened, and rewriting past lines would be a different claim.
 *
 * Lines arrive from the core at up to a few hundred a second on trace level, so the buffer is a
 * real ring rather than `list + entry` followed by `takeLast`: that pair allocated two lists of
 * [capacity] elements *per line*.
 */
class LogRepository(
    private val strings: Strings,
    private val diagnostics: DiagnosticLog? = null,
    private val capacity: Int = 2_000,
) {

    private val buffer = ArrayDeque<LogEntry>(capacity)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogEntry.Level, message: String) {
        // Debug and trace from the core are per-connection chatter: thousands of lines an hour,
        // and they would rotate the file past anything worth keeping within the hour. They stay in
        // the in-memory journal and in logcat, where looking at them is a deliberate act.
        if (level > LogEntry.Level.Debug) {
            diagnostics?.write(level.name.first(), "Yumi", message)
        }
        val snapshot = synchronized(lock) {
            buffer.addLast(LogEntry(System.currentTimeMillis(), level, message))
            while (buffer.size > capacity) buffer.removeFirst()
            buffer.toList()
        }
        _entries.value = snapshot
    }

    fun info(message: String) = log(LogEntry.Level.Info, message)
    fun warn(message: String) = log(LogEntry.Level.Warn, message)
    fun error(message: String) = log(LogEntry.Level.Error, message)
    fun debug(message: String) = log(LogEntry.Level.Debug, message)

    fun info(@StringRes id: Int, vararg args: Any) = info(strings.get(id, *args))
    fun warn(@StringRes id: Int, vararg args: Any) = warn(strings.get(id, *args))
    fun error(@StringRes id: Int, vararg args: Any) = error(strings.get(id, *args))
    fun debug(@StringRes id: Int, vararg args: Any) = debug(strings.get(id, *args))

    /**
     * A line for whoever is debugging, not for whoever is using the app.
     *
     * Goes to logcat and to the diagnostic file, never to the journal on screen: probe-by-probe
     * traces would bury the handful of lines a user actually needs there, and those lines are the
     * whole reason the journal is worth opening.
     */
    fun trace(tag: String, message: String) {
        android.util.Log.i(tag, message)
        diagnostics?.write('T', tag, message)
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
    }
}
