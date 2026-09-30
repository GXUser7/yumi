package com.mydrop.vpn.ui.theme

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

/*
 * Frosted glass, as YouCloud makes it.
 *
 * The first version of this file lit the ground with pools of colour and made every pane a small
 * production: a gradient from a sheen at the top, a rim that caught the light along one edge and
 * faded along the other, and grain over all of it. Each piece had a reason; together they looked
 * like a template — a brown room of outlined cards, every one of them trying to be noticed.
 *
 * YouCloud's glass is quieter, and that is what this now is. A pane is a flat wash of one tone of
 * the scheme over the backdrop, and a hairline of light round its edge. The backdrop does the rest:
 * its shapes are soft and deep (see ShapesBackdrop), and a translucent pane over them reads as
 * glass without having to be told so.
 *
 * Three thicknesses rather than a free alpha, so a screen stays one material: Thin for things that
 * sit on other glass, Regular for cards and panels, Thick for the few that float over moving content
 * and must stay readable whatever passes underneath.
 *
 * Only one pane blurs what is behind it, the navigation pill, because it is the only one with
 * anything behind it but the backdrop: lists scroll under it. Every other pane sits directly on the
 * backdrop, which is already a blur of soft shapes, so a real blur there would cost a render pass
 * per card and look exactly the same.
 */

enum class GlassTone { Thin, Regular, Thick }

/**
 * The glass of one colour scheme.
 *
 * Derived from the scheme like the semantic colours are, so a palette or the wallpaper repaints the
 * glass along with everything else instead of leaving grey panes in a coloured room.
 */
@Immutable
data class GlassColors(
    val dark: Boolean,
    /** What cards and panels are washed with. */
    val base: Color,
    /**
     * What the controls that float — the navigation pill, the round buttons — are washed with:
     * the tone the launcher puts its widgets and folders on (see [toGlassColors]).
     */
    val panel: Color,
    /** What sits on a [panel]. */
    val onPanel: Color,
    val thinAlpha: Float,
    val regularAlpha: Float,
    val thickAlpha: Float,
    /** The hairline of light round every pane. */
    val edge: Color,
    /**
     * Whether the panes are solid: the "transparency effects" switch turned off. Every tone is laid
     * on at full strength and nothing is blurred, which is what a slow phone asked for — the
     * backdrop still shows between the panes, it just stops showing through them.
     */
    val solid: Boolean = false,
)

/**
 * In the dark, cards are `surfaceContainer`, as YouCloud's are. Floating controls are a muted tone
 * of the accent: YouCloud takes tone 20 of the secondary palette, the tone a Pixel launcher draws its
 * widgets on — but in a wallpaper scheme the secondary is the accent's own hue, and in this app's
 * built-in palettes it is not. Glacier's secondary is sand, and a sand pill under an ice-blue screen
 * read as a stain. Mixing the container tone with the accent's container gives the launcher's
 * muted tone in a wallpaper scheme and the same kind of tone in every palette.
 */
fun ColorScheme.toGlassColors(dark: Boolean, solid: Boolean = false): GlassColors = (if (dark) {
    GlassColors(
        dark = true,
        base = surfaceContainer,
        panel = lerp(surfaceContainerHigh, primaryContainer, 0.35f),
        onPanel = onPrimaryContainer,
        thinAlpha = 0.40f,
        regularAlpha = 0.58f,
        thickAlpha = 0.74f,
        edge = Color.White.copy(alpha = 0.07f),
    )
} else {
    GlassColors(
        dark = false,
        base = surfaceContainerLow,
        panel = lerp(surfaceContainerHigh, primaryContainer, 0.5f),
        onPanel = onPrimaryContainer,
        thinAlpha = 0.46f,
        regularAlpha = 0.64f,
        thickAlpha = 0.82f,
        edge = Color.White.copy(alpha = 0.55f),
    )
}).let { if (solid) it.copy(thinAlpha = 1f, regularAlpha = 1f, thickAlpha = 1f, solid = true) else it }

val LocalGlassColors: ProvidableCompositionLocal<GlassColors> =
    staticCompositionLocalOf { MyDropDarkColors.toGlassColors(dark = true) }

/** One pane, resolved: everything [glass] needs to draw it and nothing it has to look up. */
@Immutable
data class GlassStyle(
    val fill: Color,
    val edge: Color,
    val edgeWidth: Dp,
)

object Glass {
    /**
     * @param tint colours the pane instead of the neutral glass — a selected row, a warning. It is
     *   laid on thicker than the neutral glass, as YouCloud's playing row is, so a tinted pane is
     *   never the faintest thing in a list of untinted ones.
     * @param rim an accent for the edge, for the few panes whose outline carries meaning.
     */
    @Composable
    @ReadOnlyComposable
    fun style(
        tone: GlassTone = GlassTone.Regular,
        tint: Color = Color.Unspecified,
        rim: Color = Color.Unspecified,
        rimWidth: Dp = 1.dp,
    ): GlassStyle = LocalGlassColors.current.style(tone, tint, rim, rimWidth)

    /** The glass of floating controls: the navigation pill, the round buttons beside it. */
    @Composable
    @ReadOnlyComposable
    fun panel(tone: GlassTone = GlassTone.Regular): GlassStyle = LocalGlassColors.current.let {
        GlassStyle(fill = it.panel.copy(alpha = it.alphaOf(tone)), edge = it.edge, edgeWidth = 1.dp)
    }
}

private fun GlassColors.alphaOf(tone: GlassTone): Float = when (tone) {
    GlassTone.Thin -> thinAlpha
    GlassTone.Regular -> regularAlpha
    GlassTone.Thick -> thickAlpha
}

fun GlassColors.style(
    tone: GlassTone = GlassTone.Regular,
    tint: Color = Color.Unspecified,
    rim: Color = Color.Unspecified,
    rimWidth: Dp = 1.dp,
): GlassStyle = GlassStyle(
    fill = if (tint.isSpecified) {
        tint.copy(alpha = if (solid) 1f else tint.alpha * TINTED_ALPHA)
    } else {
        base.copy(alpha = alphaOf(tone))
    },
    edge = if (rim.isSpecified) rim.copy(alpha = RIM_ALPHA) else edge,
    edgeWidth = rimWidth,
)

/** YouCloud's selected row: the panel colour at 0.8 where the neutral glass is at 0.58. */
private const val TINTED_ALPHA = 0.8f

/** An accent edge is a line drawn in the accent, not a glowing border. */
private const val RIM_ALPHA = 0.5f

/**
 * Paints a pane of [style] behind the content, in [shape].
 *
 * Behind, not over: the content stays crisp on top of its own glass. The caller clips to the same
 * shape when the content could otherwise spill past the corners — a ripple, a wave.
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
        val fill = style.fill.copy(alpha = maxOf(style.fill.alpha, FALLBACK_ALPHA))
        return clipped.pane(style.copy(fill = fill), shape, fill = true)
    }
    return clipped
        .hazeEffect(
            state = state.haze,
            style = HazeStyle(
                backgroundColor = backdrop,
                tints = listOf(HazeTint(style.fill)),
                blurRadius = FROST_RADIUS,
                noiseFactor = 0f,
                // Below Android 12 there is no blur to be had, and a pane the list reads through
                // unblurred is a mess rather than glass. Nearly opaque instead.
                fallbackTint = HazeTint(style.fill.copy(alpha = FALLBACK_ALPHA)),
            ),
        )
        .pane(style, shape, fill = false)
}

/** YouCloud's FrostBlur. */
private val FROST_RADIUS = 28.dp
private const val FALLBACK_ALPHA = 0.94f

private fun Modifier.pane(style: GlassStyle, shape: Shape, fill: Boolean): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = style.edgeWidth.toPx()
        // Inset by half the stroke, so the whole hairline lands inside the pane. Centred on the
        // outline, half of it would be clipped away by the shape it is the edge of.
        val edgeOutline = if (stroke > 0f && style.edge.alpha > 0f) {
            val inset = Size(
                width = (size.width - stroke).coerceAtLeast(0f),
                height = (size.height - stroke).coerceAtLeast(0f),
            )
            shape.createOutline(inset, layoutDirection, this)
        } else {
            null
        }
        onDrawBehind {
            if (fill) drawOutline(outline, style.fill)
            if (edgeOutline != null) {
                translate(stroke / 2f, stroke / 2f) {
                    drawOutline(edgeOutline, style.edge, style = Stroke(stroke))
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
