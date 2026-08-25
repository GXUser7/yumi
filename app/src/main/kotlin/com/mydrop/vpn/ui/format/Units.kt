package com.mydrop.vpn.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mydrop.vpn.R
import com.mydrop.vpn.core.format.ByteScale
import com.mydrop.vpn.core.format.Elapsed
import com.mydrop.vpn.core.format.ValueAndUnit
import com.mydrop.vpn.core.format.elapsedSince
import com.mydrop.vpn.core.format.megabitsValue
import com.mydrop.vpn.core.format.scaleBytes

/**
 * The words for the numbers `core/format` produces.
 *
 * Composable rather than parameterised with a `Resources`: every caller is already inside a
 * composition, and reading the locale from `LocalConfiguration` is what makes a language change
 * redraw these along with everything else. The journal takes the other route — see
 * `com.mydrop.vpn.data.Strings`.
 */

@Composable
@ReadOnlyComposable
private fun ByteScale.sizeUnit(): String = stringResource(
    when (this) {
        ByteScale.Bytes -> R.string.unit_bytes
        ByteScale.Kilo -> R.string.unit_kibibytes
        ByteScale.Mega -> R.string.unit_mebibytes
        ByteScale.Giga -> R.string.unit_gibibytes
        ByteScale.Tera -> R.string.unit_tebibytes
        ByteScale.Peta -> R.string.unit_pebibytes
    },
)

/** Rates stop at TB/s: there is no per-second figure a phone can produce beyond it. */
@Composable
@ReadOnlyComposable
private fun ByteScale.rateUnit(): String = stringResource(
    when (this) {
        ByteScale.Bytes -> R.string.unit_bytes_per_second
        ByteScale.Kilo -> R.string.unit_kibibytes_per_second
        ByteScale.Mega -> R.string.unit_mebibytes_per_second
        ByteScale.Giga -> R.string.unit_gibibytes_per_second
        ByteScale.Tera, ByteScale.Peta -> R.string.unit_tebibytes_per_second
    },
)

@Composable
@ReadOnlyComposable
fun formatBytes(bytes: Long): ValueAndUnit =
    scaleBytes(bytes).let { ValueAndUnit(it.value, it.scale.sizeUnit()) }

@Composable
@ReadOnlyComposable
fun formatRate(bytesPerSecond: Long): ValueAndUnit =
    scaleBytes(bytesPerSecond).let { ValueAndUnit(it.value, it.scale.rateUnit()) }

@Composable
@ReadOnlyComposable
fun formatMegabits(bytesPerSecond: Long): ValueAndUnit = ValueAndUnit(
    megabitsValue(bytesPerSecond),
    stringResource(R.string.unit_megabits_per_second),
)

@Composable
@ReadOnlyComposable
fun formatRelativeTime(epochMillis: Long?): String = when (val elapsed = elapsedSince(epochMillis)) {
    Elapsed.Never -> stringResource(R.string.time_never)
    Elapsed.JustNow -> stringResource(R.string.time_just_now)
    is Elapsed.Minutes -> stringResource(R.string.time_minutes_ago, elapsed.count)
    is Elapsed.Hours -> stringResource(R.string.time_hours_ago, elapsed.count)
    is Elapsed.Days -> stringResource(R.string.time_days_ago, elapsed.count)
    Elapsed.LongAgo -> stringResource(R.string.time_long_ago)
}

@Composable
@ReadOnlyComposable
fun pluralServers(count: Int): String = pluralStringResource(R.plurals.servers, count, count)

@Composable
@ReadOnlyComposable
fun pluralSources(count: Int): String = pluralStringResource(R.plurals.sources, count, count)
