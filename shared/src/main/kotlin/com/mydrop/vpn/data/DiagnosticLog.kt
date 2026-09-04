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
class DiagnosticLog(
    directory: File,
    private val enabled: Boolean,
    /**
     * Which file this instance owns.
     *
     * A second one is kept for the lines of leaked cores — see [CORES_FILE_NAME]. The point of a
     * separate file is that the main journal is the thing being drowned: a leak writes hundreds of
     * lines a second, so the record of it cannot live in the ring it is overflowing.
     */
    name: String = FILE_NAME,
    private val maxBytes: Long = MAX_BYTES,
) {

    private val file = File(directory, name)
    private val previous = File(directory, "$name.1")
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
                if (file.length() > maxBytes) {
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

    internal companion object {
        const val FILE_NAME = "yumi.log"

        /**
         * The lines of cores that should no longer exist, kept apart from everything else.
         *
         * Smaller than the main journal on purpose: it holds one kind of line, it only fills while
         * a fault is happening, and it is worth nothing if the fault's own noise rotates it away.
         */
        const val CORES_FILE_NAME = "yumi-cores.log"

        /**
         * Two of these at most, so the whole thing is bounded at fifty megabytes.
         *
         * Four used to be the bound, and four megabytes is not a night. Measured on a phone: two
         * megabytes rotated away in three minutes of ordinary evening use — the core logs a line
         * per connection and one video app produced eighteen hundred of them — so a fault that
         * happened while somebody slept was already overwritten by the time they woke up and said
         * so. A log that cannot hold the interval between noticing a problem and being asked about
         * it is not a diagnostic.
         *
         * Fifty is affordable because this is debug-only storage in the app's private directory,
         * and because the quiet case is the one that matters: with the screen off the core writes
         * almost nothing, so a night costs kilobytes. The cap exists for the loud case.
         *
         * [read] concatenates both halves into one string and has no caller inside the app; if one
         * ever appears, it must stream instead — fifty megabytes of UTF-16 is a hundred in memory.
         */
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
