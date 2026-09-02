package com.mydrop.vpn.core.model

/**
 * Counts how many sing-box cores are alive at once, from the log lines they write.
 *
 * This exists because a field journal showed several of them running side by side. Every line the
 * core emits carries the age of the instance that wrote it — sing-box's formatter prepends
 * `INFO[2545]`, where the number is seconds since that `Box` was created (`BaseTime` in
 * `log/format.go` is the box's `createdAt`). Subtracting that age from the arrival time gives the
 * instant the instance started, and two different instants in the same second mean two different
 * cores. In one journal three were writing across the same eleven minutes, each one's age
 * advancing in step with the wall clock; the oldest had been running for fifty-two minutes.
 *
 * The reason it has to be counted this way, rather than by the app keeping a tally of the cores it
 * started, is that the app already believes there is only one. It calls `startOrReloadService`,
 * which is documented to close the previous instance, and it never learns that the close did not
 * take. The log lines are the only place where the truth is visible, so they are where the count
 * comes from.
 *
 * Deliberately biased towards under-counting. The age is whole seconds and lines arrive in
 * batches, so two estimates of the same instance can differ by a second or two; anything inside
 * [sameGenerationMillis] is treated as one core. That merges genuinely distinct cores started a
 * few seconds apart, and this is the right trade: a warning nobody believes is worse than one that
 * misses the narrowest case, and the fault this hunts leaves cores running for minutes.
 */
class CoreGenerations(
    private val liveWindowMillis: Long = LIVE_WINDOW_MILLIS,
    private val sameGenerationMillis: Long = SAME_GENERATION_MILLIS,
    private val reportEveryMillis: Long = REPORT_EVERY_MILLIS,
) {

    private class Generation(var startedAtMillis: Long, var lastSeenMillis: Long)

    private val generations = ArrayList<Generation>(4)
    private var lastReportAtMillis = 0L

    /** How many cores were seen writing recently, and how old the oldest of them is. */
    data class Report(val liveCores: Int, val oldestAgeMillis: Long)

    /**
     * Takes one line as it arrives from the core.
     *
     * Lines the app itself wrote have no age prefix and are ignored, so this can be fed everything
     * without filtering.
     */
    fun observe(message: String, nowMillis: Long) {
        val uptimeSeconds = uptimeSecondsOf(message) ?: return
        val startedAt = nowMillis - uptimeSeconds * 1000L

        val existing = generations.minByOrNull { kotlin.math.abs(it.startedAtMillis - startedAt) }
        if (existing != null &&
            kotlin.math.abs(existing.startedAtMillis - startedAt) <= sameGenerationMillis
        ) {
            // The earliest estimate wins. Both the whole-second age and the delay between the core
            // writing a line and the app receiving it push the estimate later than the truth, and
            // neither can push it earlier — so the smallest number seen is the closest one.
            if (startedAt < existing.startedAtMillis) existing.startedAtMillis = startedAt
            existing.lastSeenMillis = maxOf(existing.lastSeenMillis, nowMillis)
        } else {
            generations.add(Generation(startedAt, nowMillis))
        }

        // A core that stopped writing is gone, and keeping it would make the count creep upwards
        // over a long session until every reconnect looked like a leak.
        generations.removeAll { nowMillis - it.lastSeenMillis > liveWindowMillis }
    }

    /** Cores that have written something inside the live window. */
    fun liveCores(nowMillis: Long): Int =
        generations.count { nowMillis - it.lastSeenMillis <= liveWindowMillis }

    /**
     * A warning worth writing to the journal, or null.
     *
     * Rate-limited, because the condition it reports lasts for minutes and the lines that reveal it
     * arrive hundreds of times a second.
     */
    fun dueReport(nowMillis: Long): Report? {
        val live = generations.filter { nowMillis - it.lastSeenMillis <= liveWindowMillis }
        if (live.size < 2) return null
        if (lastReportAtMillis != 0L && nowMillis - lastReportAtMillis < reportEveryMillis) return null
        lastReportAtMillis = nowMillis
        val oldest = live.minOf { it.startedAtMillis }
        return Report(liveCores = live.size, oldestAgeMillis = nowMillis - oldest)
    }

    /** Forgotten when the tunnel is torn down for real: the next session counts from scratch. */
    fun reset() {
        generations.clear()
        lastReportAtMillis = 0L
    }

    internal companion object {
        /** A core that has not written for this long is treated as gone. */
        const val LIVE_WINDOW_MILLIS = 20_000L

        /** Two start estimates this close describe the same core; see the class comment. */
        const val SAME_GENERATION_MILLIS = 6_000L

        const val REPORT_EVERY_MILLIS = 60_000L

        /** Beyond a year the number is not an age, so the line is not one of the core's. */
        private const val MAX_PLAUSIBLE_UPTIME_SECONDS = 400L * 24 * 3600

        /**
         * The age in `INFO[2545] router: ...`, or null when the line does not carry one.
         *
         * Hand-parsed rather than matched with a regular expression: this runs on every line the
         * core writes, and a busy tunnel writes thousands a second.
         */
        fun uptimeSecondsOf(message: String): Long? {
            val open = message.indexOf('[')
            // "INFO", "ERROR", "WARN", "DEBUG", "TRACE", "FATAL", "PANIC" — nothing longer.
            if (open < 3 || open > 5) return null
            for (index in 0 until open) if (message[index] !in 'A'..'Z') return null

            val close = message.indexOf(']', open + 1)
            if (close <= open + 1) return null

            var value = 0L
            for (index in open + 1 until close) {
                val digit = message[index]
                if (digit !in '0'..'9') return null
                value = value * 10 + (digit - '0')
                if (value > MAX_PLAUSIBLE_UPTIME_SECONDS) return null
            }
            return value
        }
    }
}
