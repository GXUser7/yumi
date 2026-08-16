package com.mydrop.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The live trace: every sample of the phase in flight, drawn as it arrives.
 *
 * This is the half of the screen that answers "what is it doing *right now*" — a dial shows the
 * present moment and nothing else, so a link that started fast and collapsed halfway through looks
 * exactly like one that was always mediocre. The trace keeps the shape of the measurement, which
 * is often the interesting part: a proxy that bursts and stalls draws a comb, a healthy one draws
 * a plateau.
 *
 * The window is fixed at [WINDOW] samples so the drawing advances at a constant speed instead of
 * squeezing itself as the phase goes on, and the vertical scale follows the peak of what is on
 * screen — the point is the shape, not an absolute reading, which the dial above already gives.
 */
@Composable
fun SpeedTrace(
    samples: List<Long>,
    accent: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    /**
     * A live trace advances across a fixed window and marks its head; a finished one is a record
     * of a phase that is over, so it fills the width and has no present moment to point at.
     */
    live: Boolean = true,
) {
    Canvas(modifier) {
        val baseline = size.height

        // A resting grid line, so an empty chart still reads as a chart rather than as a gap.
        drawLine(
            color = gridColor,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
        )
        if (samples.size < 2) return@Canvas

        val peak = (samples.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val span = if (live) WINDOW else samples.size
        val step = size.width / (span - 1).coerceAtLeast(1).toFloat()

        val points = samples.mapIndexed { index, value ->
            Offset(
                x = index * step,
                y = baseline - (value / peak) * size.height * 0.88f,
            )
        }

        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            // Horizontal-tangent cubics: smooth without the overshoot a spline would add, which on
            // a rate chart would draw throughput that never happened.
            for (index in 1 until points.size) {
                val previous = points[index - 1]
                val current = points[index]
                val midX = (previous.x + current.x) / 2f
                cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
            }
        }

        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, baseline)
            lineTo(points.first().x, baseline)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)),
            ),
        )
        drawPath(line, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // The head of the trace, so the eye finds the present moment without hunting for it.
        if (live) drawCircle(color = accent, radius = 3.dp.toPx(), center = points.last())
    }
}

/** Samples on screen at once: at 150 ms each this is the nine seconds a phase lasts. */
private const val WINDOW = 60
