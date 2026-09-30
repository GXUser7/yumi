package com.mydrop.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mydrop.vpn.shared.R

/*
 * Two faces, the same two YouCloud is set in, so the two apps read as one family.
 *
 * Unbounded for the display roles — the tunnel's state word, the screen titles: wide, round and
 * heavy, its forms close to the shapes in the backdrop behind them, with a Cyrillic drawn as its
 * own rather than added on. Onest for everything else: a calm grotesque, as good in Cyrillic as in
 * Latin, that leaves the figure and the numbers the attention.
 *
 * The condensed Roboto Flex poster this replaces was tuned to shout, and on the glass it did —
 * black, narrow, at 52 sp it was the loudest thing on every screen. Unbounded is so wide that the
 * display sizes are smaller than YouCloud's: "подключиться" has to fit across a phone in one line.
 *
 * Both files are variable, one weight axis each, so every weight a style asks for is drawn at that
 * weight rather than faked from its neighbour. Both carry tabular figures, which is why there is
 * no monospace face any more: the latency, the rates and the session timer ask for `tnum`.
 */

@OptIn(ExperimentalTextApi::class)
private fun variableFamily(res: Int): FontFamily = FontFamily(
    (100..900 step 100).map { weight ->
        Font(res, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    },
)

private val Display = variableFamily(R.font.unbounded)
private val Sans = variableFamily(R.font.onest)

@Suppress("DEPRECATION")
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

private fun display(size: Int, lineHeight: Int, tracking: Float) = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Bold,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = NoFontPadding,
)

private fun sans(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = NoFontPadding,
)

val MyDropTypography = Typography(
    // displayLarge is the tunnel screen's state word and timer; displaySmall every screen's title.
    displayLarge = display(36, 44, -0.5f),
    displayMedium = display(32, 40, -0.5f),
    displaySmall = display(28, 36, -0.25f),

    headlineLarge = sans(32, 40, FontWeight.ExtraBold, -0.75f),
    headlineMedium = sans(28, 36, FontWeight.ExtraBold, -0.5f),
    headlineSmall = sans(24, 32, FontWeight.ExtraBold, -0.25f),

    titleLarge = sans(22, 28, FontWeight.ExtraBold, -0.25f),
    titleMedium = sans(16, 22, FontWeight.Bold),
    titleSmall = sans(14, 20, FontWeight.SemiBold, 0.1f),

    bodyLarge = sans(16, 22),
    bodyMedium = sans(14, 20, tracking = 0.1f),
    bodySmall = sans(12, 16, tracking = 0.2f),

    labelLarge = sans(14, 20, FontWeight.SemiBold, 0.1f),
    labelMedium = sans(12, 16, FontWeight.SemiBold, 0.4f),
    // Uppercase kickers ("ПРИЁМ", "МС") need the tracking to stay readable.
    labelSmall = sans(11, 14, FontWeight.SemiBold, 0.8f),
)

/**
 * Figures that line up and tick without jitter: latency, throughput, the session timer.
 *
 * Onest with tabular figures rather than a monospace face — the numbers belong to the same text
 * as the words around them, and only their widths need to hold still.
 */
val MonoStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.em,
    platformStyle = NoFontPadding,
)

/** The same figures for places that set their own style and only need the face. */
val MonoFamily: FontFamily = Sans
