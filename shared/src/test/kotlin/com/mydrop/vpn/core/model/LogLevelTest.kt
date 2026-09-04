package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The core's numbering, pinned.
 *
 * These constants come from sing-box's own `log/level.go`, which orders them the way logrus does.
 * Reading them as syslog severities instead — which is what the app did — shifted every line the
 * core produced one step towards severe: `INFO` was filed and painted as a warning, and the
 * per-connection `TRACE` flood was filed as `Info`, where nothing could separate it from the
 * handful of lines that matter.
 */
class LogLevelTest {

    @Test
    fun `sing-box levels map to ours`() {
        assertEquals(LogEntry.Level.Error, LogEntry.levelFromCore(0)) // Panic
        assertEquals(LogEntry.Level.Error, LogEntry.levelFromCore(1)) // Fatal
        assertEquals(LogEntry.Level.Error, LogEntry.levelFromCore(2)) // Error
        assertEquals(LogEntry.Level.Warn, LogEntry.levelFromCore(3))
        assertEquals(LogEntry.Level.Info, LogEntry.levelFromCore(4))
        assertEquals(LogEntry.Level.Debug, LogEntry.levelFromCore(5))
        assertEquals(LogEntry.Level.Trace, LogEntry.levelFromCore(6))
    }

    /** A level this side has never heard of is more verbose, not more urgent. */
    @Test
    fun `an unknown level is treated as trace rather than as an error`() {
        assertEquals(LogEntry.Level.Trace, LogEntry.levelFromCore(7))
        assertEquals(LogEntry.Level.Trace, LogEntry.levelFromCore(99))
    }

    /**
     * The specific confusion this replaced: under the old syslog reading, the core's routine
     * `INFO` came out as a warning and its `TRACE` came out as `Info`.
     */
    @Test
    fun `routine core output is not filed as a warning`() {
        assertEquals(LogEntry.Level.Info, LogEntry.levelFromCore(4))
        assertEquals(LogEntry.Level.Trace, LogEntry.levelFromCore(6))
    }
}
