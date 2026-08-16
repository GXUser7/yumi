package com.mydrop.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Rolling history of download/upload rates.
 *
 * Both series share one vertical scale so their relative magnitude stays honest — upload is
 * typically a fraction of download, and normalising each series independently would make a
 * trickle of ACKs look like a matching stream.
 */
@Composable
fun TrafficSparkline(
    downloadHistory: List<Long>,
    uploadHistory: List<Long>,
    downloadColor: Color,
    uploadColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (downloadHistory.size < 2) return@Canvas

        val peak = maxOf(
            downloadHistory.maxOrNull() ?: 0L,
            uploadHistory.maxOrNull() ?: 0L,
            1L,
        ).toFloat()

        fun pointsOf(series: List<Long>): List<Offset> {
            val stepX = size.width / (series.size - 1).coerceAtLeast(1)
            return series.mapIndexed { index, value ->
                Offset(
                    x = index * stepX,
                    y = size.height - (value / peak) * size.height * 0.92f,
                )
            }
        }

        fun linePath(points: List<Offset>): Path = Path().apply {
            moveTo(points.first().x, points.first().y)
            // Horizontal-tangent cubics: smooth without the overshoot a Catmull-Rom spline
            // would introduce, which on a rate chart would draw negative throughput.
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val midX = (previous.x + current.x) / 2f
                cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
            }
        }

        val downloadPoints = pointsOf(downloadHistory)
        val downloadPath = linePath(downloadPoints)

        val fillPath = Path().apply {
            addPath(downloadPath)
            lineTo(downloadPoints.last().x, size.height)
            lineTo(downloadPoints.first().x, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(downloadColor.copy(alpha = 0.28f), downloadColor.copy(alpha = 0f)),
            ),
        )
        drawPath(downloadPath, color = downloadColor, style = Stroke(width = 2.dp.toPx()))

        if (uploadHistory.size >= 2) {
            drawPath(
                path = linePath(pointsOf(uploadHistory)),
                color = uploadColor,
                style = Stroke(width = 1.5f.dp.toPx()),
            )
        }
    }
}

/** Fixed-length rolling window of the most recent samples. */
class RateHistory(private val capacity: Int = 48) {
    private val samples = mutableStateListOf<Long>()

    val values: List<Long> get() = samples

    fun push(value: Long) {
        samples.add(value)
        while (samples.size > capacity) samples.removeAt(0)
    }

    fun clear() = samples.clear()
}

@Composable
fun rememberRateHistory(capacity: Int = 48): RateHistory = remember { RateHistory(capacity) }
