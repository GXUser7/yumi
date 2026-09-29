package com.mydrop.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.mydrop.vpn.ui.theme.GrainBrush
import com.mydrop.vpn.ui.theme.LocalGlassColors
import kotlin.math.max

/**
 * The lit ground every screen stands on, and the light that the glass panes catch.
 *
 * Three soft pools of colour on the background: the accent the caller names, high on the left
 * behind the headline; the scheme's tertiary low on the right; a faint secondary between them.
 * Then the same grain the panes carry, so the ground and the glass are one material.
 *
 * The first pool is the point. It is handed the tunnel's state by the caller — the accent when
 * protected, the "connecting" tone while it comes up, the error colour when it failed, and the
 * accent again at a third of the strength when nothing is running — so the room itself says what
 * the figure on the first tab says, on every tab, without a word or an icon. That is the rule this
 * app is drawn by, shapes and light carrying data rather than decorating, extended from one figure
 * to the whole window.
 *
 * Still, deliberately. The pools cross-fade when the state changes and otherwise do not move: the
 * backdrop sits behind the navigation pill's blur, and a drifting background would make the pill
 * re-blur every frame on every screen for as long as the app is open. A VPN is the one app people
 * leave running all day, and its idle screen is not where the battery should go.
 */
@Composable
fun AmbientBackdrop(
    glow: Color,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = LocalGlassColors.current
    val lit by animateColorAsState(glow, tween(GLOW_FADE_MILLIS), label = "backdrop-glow")
    val strength by animateFloatAsState(
        intensity,
        tween(GLOW_FADE_MILLIS),
        label = "backdrop-strength",
    )

    val background = scheme.background
    val partner = scheme.tertiary
    val second = scheme.secondary
    val levels = if (glass.dark) DarkLevels else LightLevels
    // An AMOLED ground is black because somebody wants the pixels off. The pools stay — without
    // them the glass has nothing to catch — but at half the light.
    val oled = if (background == Color.Black) 0.5f else 1f

    Canvas(modifier.fillMaxSize()) {
        drawRect(background)
        val span = max(size.width, size.height)
        pool(
            color = lit,
            alpha = levels.accent * strength * oled,
            center = Offset(size.width * 0.12f, size.height * 0.10f),
            radius = span * 0.72f,
        )
        pool(
            color = partner,
            // Brightens a little with the accent, so a running tunnel lights the whole room rather
            // than one corner of it.
            alpha = levels.partner * (0.55f + 0.45f * strength) * oled,
            center = Offset(size.width * 0.98f, size.height * 0.80f),
            radius = span * 0.62f,
        )
        pool(
            color = second,
            alpha = levels.second * oled,
            center = Offset(0f, size.height * 0.60f),
            radius = span * 0.42f,
        )
        drawRect(GrainBrush, alpha = glass.grainAlpha)
    }
}

private class Levels(val accent: Float, val partner: Float, val second: Float)

private val DarkLevels = Levels(accent = 0.34f, partner = 0.16f, second = 0.08f)

/** Lower: the light scheme's accent is the dark ice, and a third of it is already a blue wash. */
private val LightLevels = Levels(accent = 0.20f, partner = 0.10f, second = 0.07f)

private const val GLOW_FADE_MILLIS = 900

/**
 * A pool of light: full at the centre, most of it gone by the middle, nothing at the rim.
 *
 * Three stops rather than two. A straight fade from the colour to nothing reads as a disc with a
 * soft edge; dropping most of the light early and trailing the rest reads as glow.
 */
private fun DrawScope.pool(color: Color, alpha: Float, center: Offset, radius: Float) {
    if (alpha <= 0f || radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.45f to color.copy(alpha = alpha * 0.42f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
