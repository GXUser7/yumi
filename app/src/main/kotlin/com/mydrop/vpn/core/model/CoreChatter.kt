package com.mydrop.vpn.core.model

/**
 * Keeps the core from shouting the journal down.
 *
 * The journal is a ring: fifty megabytes on disk, twenty thousand lines in memory. It is sized for
 * a night of ordinary running, where the core writes a few lines a second and the app writes one
 * every few minutes. Both those numbers are true right up until something goes wrong.
 *
 * A field journal from the third of September holds a hundred and three thousand five hundred and
 * twenty-three lines. A hundred and three thousand five hundred and two of them were the core's.
 * The whole file — fifty megabytes of ring, rotated — covered **two minutes and three seconds**,
 * at eight hundred and forty-one lines a second. Everything the app itself had written that
 * afternoon was gone: which server it left and why, whether the old core closed, how long the
 * reload took. The twenty-one surviving app lines said a server was unreachable.
 *
 * That is the failure mode this exists for, and it is worse than losing history. The storm is
 * itself a symptom — three cores were alive at once, writing over each other — so the journal
 * destroys the evidence of the fault precisely when the fault is happening, and the one artefact
 * anyone can send in comes back empty of everything except the noise.
 *
 * So the core gets a budget and the app does not, and what does not fit is counted rather than
 * dropped silently: the count is the most useful line in the file, because a core that wants to
 * write eight hundred lines a second is a core in trouble whatever else the journal says.
 *
 * ## Why a bucket and not a cap per second
 *
 * A flat cap was the first shape of this, and on Xray it was wrong. Ordinary browsing is not a
 * steady trickle — it is quiet, then a page opens forty connections and the core writes five lines
 * about each of them. A three-hour journal from a phone measured that: fifty thousand core lines
 * over two and a half thousand seconds, a **peak sustained rate of two hundred and thirty-three a
 * second over ten seconds**, and single seconds asking for twelve hundred. Against a flat sixty a
 * second that threw away thirty-nine per cent of everything the core said and raised the alarm
 * below ninety-six times, in a session where nothing was wrong. A warning that common is not read.
 *
 * A bucket separates the two questions. [BURST] is how much of a spike may pass at once, and it is
 * what makes ordinary bursts invisible. [PER_SECOND] is the rate the bucket refills at, and it —
 * not the burst — is what a *sustained* storm is clamped to, which is the number that decides
 * whether the file survives. So the burst can be generous without weakening the protection at all:
 * eight hundred and forty-one lines a second empties the bucket in under a second and is held to
 * sixty a second for as long as it lasts, exactly as before.
 *
 * Second, quieter reason the burst is not larger still: [com.mydrop.vpn.data.LogRepository]
 * republishes its whole buffer on every line, so a line costs the length of the ring. The refill
 * rate bounds that cost in the steady state; the burst is what it can cost at once.
 */
class CoreChatter(
    private val perSecond: Int = PER_SECOND,
    private val burst: Int = BURST,
    private val windowMillis: Long = WINDOW_MILLIS,
) {

    /** Fractional, because a refill of sixty a second must survive being asked every few millis. */
    private var tokens = burst.toDouble()
    private var refilledAtMillis = UNSTARTED
    private var windowStartMillis = UNSTARTED
    private var dropped = 0

    /**
     * @param write whether this line may be written at all.
     * @param suppressed lines dropped before this one, reported once when a window rolls over —
     *   zero the rest of the time, so the caller writes a summary only when there is one to write.
     */
    data class Verdict(val write: Boolean, val suppressed: Int)

    /** Whether one line from the core may be written now. */
    fun admit(nowMillis: Long): Verdict {
        refill(nowMillis)

        // Also the first call, when the window has never started: nowMillis is far past zero.
        val hidden = if (windowStartMillis == UNSTARTED || nowMillis - windowStartMillis >= windowMillis) {
            windowStartMillis = nowMillis
            dropped.also { dropped = 0 }
        } else {
            0
        }

        if (tokens >= 1.0) {
            tokens -= 1.0
            return Verdict(write = true, suppressed = hidden)
        }
        dropped++
        // A rollover that lands on a dropped line still has to report: the count belongs to the
        // window that ended, not to whichever line happens to be admitted next, and a storm can
        // run for whole windows without a single line getting through.
        return Verdict(write = false, suppressed = hidden)
    }

    private fun refill(nowMillis: Long) {
        if (refilledAtMillis == UNSTARTED) {
            refilledAtMillis = nowMillis
            return
        }
        // Backwards is not expected — the caller passes a monotonic clock — but a negative elapsed
        // would drain the bucket rather than fill it, and silently throttling a healthy core is a
        // worse failure than briefly refusing to.
        val elapsed = (nowMillis - refilledAtMillis).coerceAtLeast(0L)
        refilledAtMillis = nowMillis
        tokens = (tokens + elapsed * perSecond / 1000.0).coerceAtMost(burst.toDouble())
    }

    /** Forgotten along with the core that was shouting. */
    fun reset() {
        tokens = burst.toDouble()
        refilledAtMillis = UNSTARTED
        windowStartMillis = UNSTARTED
        dropped = 0
    }

    internal companion object {
        /**
         * The rate a storm is held to, chosen from what the two states actually look like.
         *
         * Ordinary running averages a handful of lines a second; the storm was eight hundred and
         * forty. Sixty is far above the former as an average and cuts the latter by nine tenths.
         */
        const val PER_SECOND = 60

        /**
         * How much of a spike passes untouched — ten seconds' worth of refill.
         *
         * Measured against the three-hour phone journal: at sixty with no burst the core lost
         * thirty-nine per cent of its lines and tripped the alarm ninety-six times; at this size
         * it loses eleven and trips it twenty-three. Larger would be better journalling still —
         * twelve hundred brings the loss under one per cent — and the thing to weigh before
         * raising it is not the storm, which the refill rate handles either way, but the cost of
         * the burst arriving at once through `LogRepository`, which copies its whole ring per line.
         */
        const val BURST = 600

        const val WINDOW_MILLIS = 1_000L

        /** No clock reading, rather than a clock reading of zero. */
        private const val UNSTARTED = Long.MIN_VALUE
    }
}
