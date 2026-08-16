package com.mydrop.vpn.core.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Value plus unit kept apart so the UI can typeset them at different sizes. */
data class ValueAndUnit(val value: String, val unit: String) {
    override fun toString(): String = "$value $unit"
}

private val byteUnits = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ")
private val rateUnits = arrayOf("Б/с", "КБ/с", "МБ/с", "ГБ/с", "ТБ/с")

/** Binary (1024) scaling, which is what every VPN client shows for traffic quotas. */
fun formatBytes(bytes: Long): ValueAndUnit = scale(bytes, byteUnits)

fun formatRate(bytesPerSecond: Long): ValueAndUnit = scale(bytesPerSecond, rateUnits)

/**
 * Megabits, which is the unit a speed test is read in — every provider quotes them and every other
 * speed test shows them, so bytes here would make the number incomparable with the one thing the
 * user wants to compare it against. Decimal megabits (10⁶), not binary: that is what the figure on
 * the contract means.
 */
fun formatMegabits(bytesPerSecond: Long): ValueAndUnit {
    val megabits = bytesPerSecond * 8 / 1_000_000.0
    val text = when {
        megabits <= 0 -> "0"
        megabits >= 100 -> String.format(Locale.US, "%.0f", megabits)
        megabits >= 10 -> String.format(Locale.US, "%.1f", megabits)
        else -> String.format(Locale.US, "%.2f", megabits)
    }
    return ValueAndUnit(text, "Мбит/с")
}

private fun scale(raw: Long, units: Array<String>): ValueAndUnit {
    if (raw <= 0L) return ValueAndUnit("0", units[0])
    val exponent = (ln(abs(raw).toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = raw / 1024.0.pow(exponent)
    val text = when {
        exponent == 0 -> value.toLong().toString()
        value >= 100 -> String.format(Locale.US, "%.0f", value)
        value >= 10 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
    return ValueAndUnit(text, units[exponent])
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

fun formatRelativeTime(epochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis == null) return "никогда"
    val delta = nowMillis - epochMillis
    return when {
        delta < 60_000 -> "только что"
        delta < 3_600_000 -> "${delta / 60_000} мин назад"
        delta < 86_400_000 -> "${delta / 3_600_000} ч назад"
        delta < 2_592_000_000 -> "${delta / 86_400_000} дн назад"
        else -> "давно"
    }
}

/**
 * Russian plural agreement: 1 сервер, 2 сервера, 5 серверов — with the 11–14 exception, which
 * takes the "many" form despite ending in 1–4.
 */
fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val form = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$count $form"
}

fun pluralServers(count: Int): String = plural(count, "сервер", "сервера", "серверов")

fun pluralSources(count: Int): String = plural(count, "источник", "источника", "источников")

fun pluralConnections(count: Int): String =
    plural(count, "соединение", "соединения", "соединений")

/** Days remaining on a plan, or null when the provider publishes no expiry. */
fun daysUntil(epochSeconds: Long?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    val deltaMillis = epochSeconds * 1000 - nowMillis
    return (deltaMillis / 86_400_000).coerceAtLeast(0)
}
