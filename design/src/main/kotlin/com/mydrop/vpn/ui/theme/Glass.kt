package com.mydrop.vpn.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.random.Random

/*
 * Frosted glass: what every surface in the app is made of.
 *
 * The screens used to be a ladder of opaque greys — surfaceContainerLow for a card,
 * surfaceContainerHigh for the thing inside it — on a flat near-black ground. That works, and it
 * says nothing: a card was a slightly lighter rectangle, and the only thing on the screen that
 * knew whether the tunnel was up was the figure in the middle of the first tab.
 *
 * Now the ground is lit (see AmbientBackdrop) and the surfaces are panes over it: a translucent
 * fill that lets the light through, a hairline rim that catches it along the top edge, and a
 * fine grain that turns "transparent" into "matte". The grain is not decoration either — a
 * soft gradient on an eight-bit panel bands visibly, and noise at a few per cent is the cheapest
 * dither there is.
 *
 * Three thicknesses rather than a free alpha, for the same reason the palettes are derived rather
 * than tabulated: a screen where every pane picked its own opacity would stop reading as one
 * material. Thin is for things that sit on other glass (tiles inside a panel, small chips),
 * Regular for cards, Thick for the few panes that float over scrolling content and must stay
 * readable whatever passes underneath.
 *
 * Only one pane blurs what is behind it, the navigation pill, because it is the only one with
 * anything behind it but the lit ground: lists scroll under it. Every other pane sits directly on
 * the backdrop, which is already a blur of light, so a real blur there would cost a render pass
 * per card and look exactly the same.
 */

enum class GlassTone { Thin, Regular, Thick }

/**
 * The glass of one colour scheme: what it is tinted with, and how the light catches it.
 *
 * Derived from the scheme like the semantic colours are, so a palette or the wallpaper repaints
 * the glass along with everything else instead of leaving grey panes in a coloured room.
 */
@Immutable
data class GlassColors(
    val dark: Boolean,
    val base: Color,
    val thinAlpha: Float,
    val regularAlpha: Float,
    val thickAlpha: Float,
    val rimLight: Color,
    val rimShade: Color,
    /** How much lighter the top of a pane is than its bottom — light falling from above. */
    val sheen: Float,
    val grainAlpha: Float,
)

/**
 * The dark glass is a raised container tone at about half strength, so the backdrop's colour
 * reaches through without the pane dissolving into it. The light glass is white at more than half:
 * on a pale ground a thin white pane is invisible, and what makes it read as glass is the bright
 * rim and the faint shade along its lower edge rather than the fill.
 */
fun ColorScheme.toGlassColors(dark: Boolean): GlassColors = if (dark) {
    GlassColors(
        dark = true,
        base = surfaceContainerHigh,
        thinAlpha = 0.34f,
        regularAlpha = 0.50f,
        thickAlpha = 0.72f,
        rimLight = onSurface.copy(alpha = 0.20f),
        rimShade = onSurface.copy(alpha = 0.04f),
        sheen = 0.07f,
        grainAlpha = 0.045f,
    )
} else {
    GlassColors(
        dark = false,
        base = surfaceContainerLowest,
        thinAlpha = 0.46f,
        regularAlpha = 0.64f,
        thickAlpha = 0.84f,
        rimLight = Color.White.copy(alpha = 0.95f),
        rimShade = outline.copy(alpha = 0.22f),
        sheen = 0f,
        grainAlpha = 0.03f,
    )
}

val LocalGlassColors: ProvidableCompositionLocal<GlassColors> =
    staticCompositionLocalOf { MyDropDarkColors.toGlassColors(dark = true) }

/** One pane, resolved: everything [glass] needs to draw it and nothing it has to look up. */
@Immutable
data class GlassStyle(
    val fillTop: Color,
    val fillBottom: Color,
    val rimLight: Color,
    val rimShade: Color,
    val rimWidth: Dp,
    val grainAlpha: Float,
)

object Glass {
    /**
     * @param tint colours the pane instead of the neutral glass — a selected row, a warning. It
     *   takes the tone's opacity and a little more, so a tinted pane is never the faintest thing
     *   in a list of untinted ones.
     * @param rim an accent for the edge. The whole contour takes it, brighter along the top: the
     *   tunnel control and the figure use it to say "running" with an outline, which is how they
     *   said it before the glass.
     */
    @Composable
    @ReadOnlyComposable
    fun style(
        tone: GlassTone = GlassTone.Regular,
        tint: Color = Color.Unspecified,
        rim: Color = Color.Unspecified,
        rimWidth: Dp = 1.dp,
    ): GlassStyle = LocalGlassColors.current.style(tone, tint, rim, rimWidth)
}

fun GlassColors.style(
    tone: GlassTone = GlassTone.Regular,
    tint: Color = Color.Unspecified,
    rim: Color = Color.Unspecified,
    rimWidth: Dp = 1.dp,
): GlassStyle {
    val alpha = when (tone) {
        GlassTone.Thin -> thinAlpha
        GlassTone.Regular -> regularAlpha
        GlassTone.Thick -> thickAlpha
    }
    val body = if (tint.isSpecified) {
        tint.copy(alpha = (alpha + TINT_LIFT).coerceAtMost(1f))
    } else {
        base.copy(alpha = alpha)
    }
    return GlassStyle(
        fillTop = lerp(body, Color.White.copy(alpha = body.alpha), sheen),
        fillBottom = body,
        rimLight = if (rim.isSpecified) rim.copy(alpha = 0.85f) else rimLight,
        rimShade = if (rim.isSpecified) rim.copy(alpha = 0.30f) else rimShade,
        rimWidth = rimWidth,
        grainAlpha = grainAlpha,
    )
}

private const val TINT_LIFT = 0.14f

/**
 * Paints a pane of [style] behind the content, in [shape].
 *
 * Behind, not over: the content stays crisp on top of its own glass. The caller clips to the same
 * shape when the content could otherwise spill past the corners — a ripple, a wave — and does not
 * have to otherwise, because everything here is drawn inside the outline already.
 */
fun Modifier.glass(style: GlassStyle, shape: Shape): Modifier = pane(style, shape, fill = true)

/**
 * The navigation pill's glass: the same pane, over a real blur of whatever is behind it.
 *
 * With no [state] — a preview, or a screen that lives outside the app's frost source — it is the
 * ordinary pane made nearly opaque, because there is then nothing to stop a list scrolling under it
 * from showing through as clearly as it would through a window.
 */
fun Modifier.frostedGlass(
    state: FrostState?,
    style: GlassStyle,
    shape: Shape,
    backdrop: Color,
): Modifier {
    val clipped = clip(shape)
    if (state == null) {
        val solid = style.copy(
            fillTop = style.fillTop.copy(alpha = FALLBACK_ALPHA),
            fillBottom = style.fillBottom.copy(alpha = FALLBACK_ALPHA),
        )
        return clipped.pane(solid, shape, fill = true)
    }
    return clipped
        .hazeEffect(
            state = state.haze,
            style = HazeStyle(
                backgroundColor = backdrop,
                tints = listOf(HazeTint(style.fillBottom)),
                blurRadius = FROST_RADIUS,
                noiseFactor = FROST_NOISE,
                // Below Android 12 there is no blur to be had, and a pane the list reads through
                // unblurred is a mess rather than glass. Nearly opaque instead.
                fallbackTint = HazeTint(style.fillBottom.copy(alpha = FALLBACK_ALPHA)),
            ),
        )
        .pane(style, shape, fill = false)
}

private val FROST_RADIUS = 26.dp
private const val FROST_NOISE = 0.08f
private const val FALLBACK_ALPHA = 0.94f

private fun Modifier.pane(style: GlassStyle, shape: Shape, fill: Boolean): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val body = Brush.verticalGradient(listOf(style.fillTop, style.fillBottom))
        val stroke = style.rimWidth.toPx()
        // Inset by half the stroke, so the whole hairline lands inside the pane. Centred on the
        // outline, half of it would be clipped away by the shape it is the edge of.
        val rimOutline = if (stroke > 0f) {
            val inset = Size(
                width = (size.width - stroke).coerceAtLeast(0f),
                height = (size.height - stroke).coerceAtLeast(0f),
            )
            shape.createOutline(inset, layoutDirection, this)
        } else {
            null
        }
        // Mostly top to bottom with a slight lean, as if lit from above and a little to the left.
        // A true diagonal would light the left end of a wide pill and leave the right end dark.
        val rim = Brush.linearGradient(
            listOf(style.rimLight, style.rimShade),
            start = Offset.Zero,
            end = Offset(size.width * 0.35f, size.height),
        )
        onDrawBehind {
            if (fill) {
                drawOutline(outline, body)
                if (style.grainAlpha > 0f) {
                    drawOutline(outline, GrainBrush, alpha = style.grainAlpha)
                }
            }
            if (rimOutline != null) {
                translate(stroke / 2f, stroke / 2f) {
                    drawOutline(rimOutline, rim, style = Stroke(stroke))
                }
            }
        }
    }

/* ── Frost: the blur behind the pill ──────────────────────────────────────────────────────── */

/**
 * Where the frosted panes read their background from.
 *
 * A wrapper rather than Haze's own state, so the screens say what they mean — this is the content
 * glass can be frosted over — and the library stays an implementation detail of this file.
 */
@Stable
class FrostState internal constructor(internal val haze: HazeState)

@Composable
fun rememberFrostState(): FrostState {
    val haze = rememberHazeState()
    return remember(haze) { FrostState(haze) }
}

/** The app's frost source, for panes that are not handed one explicitly. */
val LocalFrostState: ProvidableCompositionLocal<FrostState?> = staticCompositionLocalOf { null }

/** Marks this content as what frosted panes elsewhere on the screen blur. */
fun Modifier.frostSource(state: FrostState): Modifier = hazeSource(state.haze)

/* ── Grain ────────────────────────────────────────────────────────────────────────────────── */

/**
 * A tile of grey noise, repeated.
 *
 * Generated rather than shipped: 128 pixels square is 64 KB in memory and nothing in the APK, and a
 * fixed seed means every pane and every launch gets the same texture — noise that changed from one
 * frame to the next would shimmer. One device pixel per texel, so the grain is as fine as the panel
 * can show and never reads as a pattern.
 */
internal val GrainBrush: ShaderBrush by lazy {
    val side = 128
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(side * side) {
        val level = random.nextInt(256)
        (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }
    val tile = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
    val shader = ImageShader(tile, TileMode.Repeated, TileMode.Repeated)
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader = shader
    }
}

private const val GRAIN_SEED = 0x59554D49
