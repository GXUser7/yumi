package com.mydrop.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * The speed test's instrument: a dial with a needle, a labelled scale and a lit arc.
 *
 * It is the conventional shape on purpose. An earlier version filled a morphing figure like a
 * vessel, which was more interesting to draw and worse to read: with no scale on it, a half-full
 * shape answers "is this fast?" with a shrug. A dial carries numbers, so a glance lands on
 * "40 Мбит/с, and the ceiling is 500" instead of "somewhere past the middle".
 *
 * The scale is logarithmic, and its labelled stops say so: 1, 5, 10, 25, 50, 100, 250, 500. Spaced
 * linearly, everything a phone actually sees on a proxied link would crowd into the first sixth of
 * the sweep, and the distinction between 5 and 50 Мбит/с — which is the difference between video
 * and no video — would be invisible.
 */
@Composable
fun SpeedGauge(
    bytesPerSecond: Long,
    accent: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    // The readings arriving here are already averaged over a sliding second; this spring only has
    // to carry the needle between them. The slower of the spatial specs on purpose — a fast one
    // arrives before the next sample and then sits still, which turns a smooth series back into a
    // twitch.
    val pace by animateFloatAsState(
        targetValue = paceOf(bytesPerSecond),
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "gauge-pace",
    )

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needle = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = minOf(size.width, size.height) / 2f
        val fill = pace.coerceIn(0f, 1f)
        val thickness = radius * 0.085f

        drawArc(
            color = trackColor,
            startAngle = START_DEGREES,
            sweepAngle = SWEEP_DEGREES,
            useCenter = false,
            topLeft = Offset(center.x - radius + thickness, center.y - radius + thickness),
            size = Size((radius - thickness) * 2, (radius - thickness) * 2),
            style = Stroke(width = thickness, cap = StrokeCap.Round),
        )
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(accent.copy(alpha = 0.45f), accent, accent),
                center = center,
            ),
            startAngle = START_DEGREES,
            sweepAngle = SWEEP_DEGREES * fill,
            useCenter = false,
            topLeft = Offset(center.x - radius + thickness, center.y - radius + thickness),
            size = Size((radius - thickness) * 2, (radius - thickness) * 2),
            style = Stroke(width = thickness, cap = StrokeCap.Round),
        )

        drawScale(radius, thickness, fill, accent, trackColor, measurer, labelStyle, labelColor)
        drawNeedle(radius, thickness, fill, accent, needle)
    }
}

/** Ticks at every stop, with the labelled ones longer and carrying their number outside the arc. */
private fun DrawScope.drawScale(
    radius: Float,
    thickness: Float,
    fill: Float,
    accent: Color,
    track: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    labelColor: Color,
) {
    val ringInner = radius - thickness * 2.4f

    SCALE_STOPS.forEach { stop ->
        val fraction = paceOfMegabits(stop)
        val angle = (START_DEGREES + SWEEP_DEGREES * fraction) * PI.toFloat() / 180f
        val direction = Offset(cos(angle), sin(angle))

        drawLine(
            color = if (fraction <= fill) accent else track,
            start = center + direction * (ringInner - radius * 0.06f),
            end = center + direction * ringInner,
            strokeWidth = 2f.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val label = measurer.measure(stop.label(), labelStyle)
        val anchor = center + direction * (ringInner - radius * 0.17f)
        drawText(
            textLayoutResult = label,
            color = labelColor,
            topLeft = Offset(
                anchor.x - label.size.width / 2f,
                anchor.y - label.size.height / 2f,
            ),
        )
    }
}

/**
 * A tapered needle rather than a line: a shape with a wide root and a narrow end reads its
 * direction instantly at a glance, which is the entire job of a needle.
 *
 * The end is blunt and capped with a circle of the same width, so it finishes in a round tip. A
 * true point is sharper than anything else on this screen — every other edge in the app is a
 * radius — and it reads as a spike laid over the dial rather than as part of it.
 */
private fun DrawScope.drawNeedle(
    radius: Float,
    thickness: Float,
    fill: Float,
    accent: Color,
    path: Path,
) {
    val angle = (START_DEGREES + SWEEP_DEGREES * fill) * PI.toFloat() / 180f
    val direction = Offset(cos(angle), sin(angle))
    val across = Offset(-direction.y, direction.x)
    val root = radius * 0.045f
    val tipHalf = root * 0.38f
    val tip = center + direction * (radius - thickness * 2.9f)

    path.reset()
    path.moveTo(tip.x + across.x * tipHalf, tip.y + across.y * tipHalf)
    path.lineTo(center.x + across.x * root, center.y + across.y * root)
    path.lineTo(center.x - direction.x * root * 1.6f, center.y - direction.y * root * 1.6f)
    path.lineTo(center.x - across.x * root, center.y - across.y * root)
    path.lineTo(tip.x - across.x * tipHalf, tip.y - across.y * tipHalf)
    path.close()

    drawPath(path, color = accent)
    drawCircle(color = accent, radius = tipHalf, center = tip)
    drawCircle(color = accent, radius = root * 1.5f, center = center)
    drawCircle(
        color = accent.copy(alpha = 0.25f),
        radius = root * 2.6f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

/** Bottom-left round to bottom-right; the gap at the bottom is where the reading sits. */
private const val START_DEGREES = 135f
private const val SWEEP_DEGREES = 270f

/** Megabits per second. The top of the scale is the ceiling a phone on a good link can reach. */
private val SCALE_STOPS = listOf(0f, 1f, 5f, 10f, 25f, 50f, 100f, 250f, 500f)

private const val FULL_SCALE_MEGABITS = 500f

private fun Float.label(): String = when {
    this <= 0f -> "0"
    this < 1f -> toString()
    else -> toInt().toString()
}

private fun paceOfMegabits(megabits: Float): Float =
    (ln(1f + megabits) / ln(1f + FULL_SCALE_MEGABITS)).coerceIn(0f, 1f)

private fun paceOf(bytesPerSecond: Long): Float {
    if (bytesPerSecond <= 0L) return 0f
    return paceOfMegabits(bytesPerSecond * 8f / 1_000_000f)
}
