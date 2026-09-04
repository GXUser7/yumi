package com.mydrop.vpn.core.model

/**
 * Comparing two version strings, which is less obvious than it looks.
 *
 * The app's own `versionName` and a GitHub tag are written differently — `0.3.5` against `v0.3.5`
 * — and a debug build carries a suffix on top of that (`0.3.5-debug`). Comparing the strings
 * directly says a debug build of 0.3.5 is older than the release of 0.3.5, and offers an update
 * that installs the same code. Comparing them as text also gets `0.10.0` and `0.9.0` the wrong way
 * round, which is a bug that appears exactly once, on the tenth release, long after anybody is
 * still looking at this.
 *
 * So: strip the decoration, read the numbers, compare them as numbers.
 */
object Version {

    /** `v0.3.5-debug` → `[0, 3, 5]`. Anything unparseable in a position reads as zero. */
    fun parse(raw: String): List<Int> = raw
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        // Everything from the first pre-release or build marker on is decoration.
        .takeWhile { it != '-' && it != '+' && it != ' ' }
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * Whether [candidate] is a version worth offering to somebody running [current].
     *
     * Equal versions are not newer, which is what keeps a debug build of the current release from
     * being told to update to itself.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val left = parse(candidate)
        val right = parse(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
