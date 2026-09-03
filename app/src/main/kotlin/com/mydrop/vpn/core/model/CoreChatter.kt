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
 * So the core gets a budget and the app does not. [PER_SECOND] a second is far above anything
 * normal running produces and far below a storm, and what does not fit is counted rather than
 * dropped silently: the count is the most useful line in the file, because a core that wants to
 * write eight hundred lines a second is a core in trouble whatever else the journal says.
 *
 * Second, quieter benefit: [com.mydrop.vpn.data.LogRepository] republishes its whole buffer on
 * every line, so a line costs the length of the ring. At eight hundred a second against twenty
 * thousand entries that is some seventeen million element copies a second, spent on a phone whose
 * cores are already fighting each other. The budget takes that down with it.
 */
class CoreChatter(
    private val perWindow: Int = PER_SECOND,
    private val windowMillis: Long = WINDOW_MILLIS,
) {

    private var windowStartMillis = 0L
    private var admitted = 0
    private var dropped = 0

    /**
     * @param write whether this line may be written at all.
     * @param suppressed lines dropped before this one, reported once when a window rolls over —
     *   zero the rest of the time, so the caller writes a summary only when there is one to write.
     */
    data class Verdict(val write: Boolean, val suppressed: Int)

    /** Whether one line from the core may be written now. */
    fun admit(nowMillis: Long): Verdict {
        // Also the first call, when the window has never started: nowMillis is far past zero.
        if (nowMillis - windowStartMillis >= windowMillis) {
            val hidden = dropped
            windowStartMillis = nowMillis
            admitted = 1
            dropped = 0
            return Verdict(write = true, suppressed = hidden)
        }
        if (admitted < perWindow) {
            admitted++
            return Verdict(write = true, suppressed = 0)
        }
        dropped++
        return Verdict(write = false, suppressed = 0)
    }

    /** Forgotten along with the core that was shouting. */
    fun reset() {
        windowStartMillis = 0L
        admitted = 0
        dropped = 0
    }

    internal companion object {
        /**
         * Chosen from what the two states actually look like rather than from a round number.
         * Ordinary running is a handful of lines a second; the storm was eight hundred and forty.
         * Sixty keeps every line of the former and cuts the latter by nine tenths.
         */
        const val PER_SECOND = 60

        const val WINDOW_MILLIS = 1_000L
    }
}
