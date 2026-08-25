package com.mydrop.vpn.core.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Numbers, and nothing that has to be read in a language.
 *
 * The words used to live here — "КБ", "мин назад", "сервера" — which put the whole of the app's
 * unit vocabulary in a layer that has no way to reach resources. What is left is the arithmetic:
 * which scale a figure lands on, how many minutes ago something happened. `ui/format/Units.kt`
 * turns those answers into text, and `data/Strings` does the same for the journal.
 */

/** Value plus unit kept apart so the UI can typeset them at different sizes. */
data class ValueAndUnit(val value: String, val unit: String) {
    override fun toString(): String = "$value $unit"
}

/** Binary (1024) scales, smallest first. The index is what a caller resolves to a word. */
enum class ByteScale { Bytes, Kilo, Mega, Giga, Tera, Peta }

/** A figure already divided down to its scale, with the scale that was used. */
data class ScaledBytes(val value: String, val scale: ByteScale)

/** Binary (1024) scaling, which is what every VPN client shows for traffic quotas. */
fun scaleBytes(raw: Long): ScaledBytes {
    if (raw <= 0L) return ScaledBytes("0", ByteScale.Bytes)
    val exponent = (ln(abs(raw).toDouble()) / ln(1024.0)).toInt()
        .coerceIn(0, ByteScale.entries.lastIndex)
    val value = raw / 1024.0.pow(exponent)
    val text = when {
        exponent == 0 -> value.toLong().toString()
        value >= 100 -> String.format(Locale.US, "%.0f", value)
        value >= 10 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
    return ScaledBytes(text, ByteScale.entries[exponent])
}

/**
 * Megabits, which is the unit a speed test is read in — every provider quotes them and every other
 * speed test shows them, so bytes here would make the number incomparable with the one thing the
 * user wants to compare it against. Decimal megabits (10⁶), not binary: that is what the figure on
 * the contract means.
 */
fun megabitsValue(bytesPerSecond: Long): String {
    val megabits = bytesPerSecond * 8 / 1_000_000.0
    return when {
        megabits <= 0 -> "0"
        megabits >= 100 -> String.format(Locale.US, "%.0f", megabits)
        megabits >= 10 -> String.format(Locale.US, "%.1f", megabits)
        else -> String.format(Locale.US, "%.2f", megabits)
    }
}

/** hh:mm:ss for long sessions, mm:ss for short ones — a leading "00:" is just noise. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** How long ago something happened, bucketed to the granularity worth showing. */
sealed interface Elapsed {
    data object Never : Elapsed
    data object JustNow : Elapsed
    data class Minutes(val count: Long) : Elapsed
    data class Hours(val count: Long) : Elapsed
    data class Days(val count: Long) : Elapsed
    data object LongAgo : Elapsed
}

fun elapsedSince(epochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Elapsed {
    if (epochMillis == null) return Elapsed.Never
    val delta = nowMillis - epochMillis
    return when {
        delta < 60_000 -> Elapsed.JustNow
        delta < 3_600_000 -> Elapsed.Minutes(delta / 60_000)
        delta < 86_400_000 -> Elapsed.Hours(delta / 3_600_000)
        delta < 2_592_000_000 -> Elapsed.Days(delta / 86_400_000)
        else -> Elapsed.LongAgo
    }
}

/** Days remaining on a plan, or null when the provider publishes no expiry. */
fun daysUntil(epochSeconds: Long?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    val deltaMillis = epochSeconds * 1000 - nowMillis
    return (deltaMillis / 86_400_000).coerceAtLeast(0)
}
