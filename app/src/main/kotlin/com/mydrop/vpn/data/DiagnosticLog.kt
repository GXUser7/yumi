package com.mydrop.vpn.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The journal, on disk, for debug builds only.
 *
 * The in-memory journal dies with the process and holds two thousand lines, which is fine for
 * looking at something that just happened and useless for the failures that matter: a tunnel that
 * dropped twenty minutes ago, a handover that happened while the screen was off, anything at all
 * after Android has killed and restarted the service. Reading it over `adb` does not help either —
 * the device's own logcat ring is shared with the whole system and can be flushed by an unrelated
 * component logging a few hundred lines a second, which is exactly how the evidence for one
 * Wi-Fi handover was lost while it was being collected.
 *
 * So: append to a file, rotate it, and let somebody pull it afterwards.
 *
 * **Debug builds only, and that is the point.** These lines carry server addresses, the domains
 * requests were made to and the shape of somebody's browsing; [LogRepository] deliberately keeps
 * them in memory for exactly that reason. Writing them to storage is a trade worth making while
 * chasing a bug on your own phone, and not one to make silently for everybody else.
 */
class DiagnosticLog(directory: File, private val enabled: Boolean) {

    private val file = File(directory, FILE_NAME)
    private val previous = File(directory, "$FILE_NAME.1")
    private val lock = Any()
    private val timestamps = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Where to find it: `adb shell run-as <package> cat files/diagnostics/yumi.log`. */
    val path: String get() = file.absolutePath

    fun write(level: Char, tag: String, message: String) {
        if (!enabled) return
        val line = "${timestamps.format(Date())} $level/$tag $message\n"
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                // Rotated before the write rather than after, so the cap is a cap: checking
                // afterwards lets one long line push the file past it and leaves it there.
                if (file.length() > MAX_BYTES) {
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(line)
            }
            // Deliberately silent on failure. This is a diagnostic; a full disk or a locked file
            // must not take down the thing it is diagnosing, and there is nowhere to report it
            // that would not be this same file.
        }
    }

    /** Both halves, oldest first, for whoever is reading it off the device. */
    fun read(): String = synchronized(lock) {
        buildString {
            runCatching { if (previous.isFile) append(previous.readText()) }
            runCatching { if (file.isFile) append(file.readText()) }
        }
    }

    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        runCatching { previous.delete() }
        Unit
    }

    private companion object {
        const val FILE_NAME = "yumi.log"

        /** Two of these at most, so the whole thing is bounded at four megabytes. */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
