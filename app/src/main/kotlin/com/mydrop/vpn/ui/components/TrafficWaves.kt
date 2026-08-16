package com.mydrop.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * The traffic figure: soft bands drifting across the tunnel's main shape.
 *
 * Each band is a wave whose amplitude is throughput, so the figure reads the tunnel rather than
 * decorating it — download swells the ice bands, upload the sand one, and an idle tunnel settles
 * into almost-flat lines. Nothing about it is sharp: filled areas fade downwards into the
 * surface, and the crest carries a soft stroke rather than an outline.
 *
 * Two harmonics per band, at frequencies that do not divide into each other, so the shape never
 * visibly repeats and never looks mechanical.
 */
@Composable
fun TrafficWaves(
    downloadBytesPerSecond: Long,
    uploadBytesPerSecond: Long,
    downloadColor: Color,
    uploadColor: Color,
    modifier: Modifier = Modifier,
    /** Keeps the figure breathing during the handshake, when there is no traffic to show yet. */
    warming: Boolean = false,
) {
    val downloadPace = paceOf(downloadBytesPerSecond)
    val uploadPace = paceOf(uploadBytesPerSecond)

    // Rates arrive in one-second steps, so the swell is eased across a step and a half rather than
    // a step: matching the sampling exactly means the surface arrives at each new value just as
    // the next one lands, which is a series of lurches rather than a swell.
    val downSwell by animateFloatAsState(
        targetValue = if (warming) maxOf(downloadPace, 0.45f) else downloadPace,
        animationSpec = tween(1600, easing = LinearEasing),
        label = "download-swell",
    )
    val upSwell by animateFloatAsState(
        targetValue = if (warming) maxOf(uploadPace, 0.3f) else uploadPace,
        animationSpec = tween(1600, easing = LinearEasing),
        label = "upload-swell",
    )

    val bands = remember { defaultBands() }
    val elapsed = rememberSeconds()
    val down = rememberUpdatedState(downSwell)
    val up = rememberUpdatedState(upSwell)
    val downInk = rememberUpdatedState(downloadColor)
    val upInk = rememberUpdatedState(uploadColor)
    val path = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Everything animated is read here, in the draw scope, so a frame costs a redraw rather
        // than a recomposition of the screen around the figure.
        val time = elapsed.value
        val stroke = 1.5.dp.toPx()

        bands.forEach { band ->
            val swell = if (band.upload) up.value else down.value
            val ink = if (band.upload) upInk.value else downInk.value
            drawBand(band, time, swell, ink, path, stroke)
        }
    }
}

private fun DrawScope.drawBand(
    band: Band,
    time: Float,
    swell: Float,
    ink: Color,
    path: Path,
    stroke: Float,
) {
    val width = size.width
    val height = size.height
    // A resting band is not flat — a dead-still line reads as a broken widget rather than as an
    // idle tunnel — but it barely moves until traffic arrives.
    val amplitude = height * band.amplitude * (0.12f + swell * 0.88f)
    val baseline = height * band.baseline
    val drift = time * band.speed * (0.25f + swell * 0.75f)

    path.reset()
    path.moveTo(0f, baseline)

    val step = 6f
    var x = 0f
    while (x <= width) {
        path.lineTo(x, waveAt(x / width, drift, band) * amplitude + baseline)
        x += step
    }
    path.lineTo(width, waveAt(1f, drift, band) * amplitude + baseline)

    // Stroke the crest first, then close the same path downwards for the fill, so the two always
    // agree on the curve.
    drawPath(
        path = path,
        color = ink.copy(alpha = band.strokeAlpha * (0.35f + swell * 0.65f)),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    path.lineTo(width, height)
    path.lineTo(0f, height)
    path.close()

    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(
                ink.copy(alpha = band.fillAlpha * (0.3f + swell * 0.7f)),
                ink.copy(alpha = 0f),
            ),
            startY = baseline - amplitude,
            endY = height,
        ),
        // Bands overlap; multiplying them would muddy the colour, so they simply add up and the
        // crossings read as a lighter tone.
        blendMode = BlendMode.Plus,
    )
}

/** Two non-harmonic sines, so the crest never visibly repeats across the width. */
private fun waveAt(fraction: Float, drift: Float, band: Band): Float {
    val primary = sin(fraction * band.frequency * TWO_PI + drift + band.phase)
    val secondary = sin(fraction * band.frequency * 1.73f * TWO_PI - drift * 0.6f + band.phase)
    return primary * 0.68f + secondary * 0.32f
}

private class Band(
    val baseline: Float,
    val amplitude: Float,
    val frequency: Float,
    val speed: Float,
    val phase: Float,
    val fillAlpha: Float,
    val strokeAlpha: Float,
    val upload: Boolean,
)

/**
 * Three bands: two for download at different depths, one for upload sitting lower. Upstream is
 * usually a fraction of downstream, and an even split would misreport the tunnel.
 *
 * The amplitudes are roughly half what they were. At the old figures a busy second threw the
 * crests across a third of the figure and the surface read as a storm rather than as a tunnel
 * doing its job — and since traffic arrives in one-second steps, every step landed as a lurch.
 * The figure has to say idle / working / working hard, and that survives a calmer swing.
 */
private fun defaultBands(): List<Band> = listOf(
    Band(0.46f, 0.070f, 1.1f, 0.90f, 0f, 0.32f, 0.60f, upload = false),
    Band(0.62f, 0.055f, 1.7f, 0.64f, 2.1f, 0.24f, 0.45f, upload = false),
    Band(0.78f, 0.040f, 1.4f, -0.76f, 4.0f, 0.22f, 0.50f, upload = true),
)

private const val TWO_PI = (PI * 2).toFloat()

/** Seconds since the figure first drew, rebased so Float keeps frame-level precision. */
@Composable
private fun rememberSeconds(): State<Float> = produceState(0f) {
    var origin = 0L
    while (true) {
        withInfiniteAnimationFrameMillis { frame ->
            if (origin == 0L) origin = frame
            value = (frame - origin) / 1000f
        }
    }
}

/**
 * The rate at which the figure is fully swept up. Set to a busy line rather than a fast one on
 * purpose: calibrating against 100 Mbit/s meant streaming video — a few megabits — barely moved
 * the surface, which read as an idle tunnel while it was in fact working hard. What the figure
 * has to distinguish is nothing / something / a lot, and 25 Mbit/s puts everyday use in the
 * middle of that range instead of at the bottom.
 */
private const val FULL_SCALE_MEGABITS = 25f

/** Bytes per second onto a 0..1 swell, log-shaped so a slow link still visibly moves. */
private fun paceOf(bytesPerSecond: Long): Float {
    if (bytesPerSecond <= 0L) return 0f
    val megabits = bytesPerSecond * 8f / 1_000_000f
    return (ln(1f + megabits) / ln(1f + FULL_SCALE_MEGABITS)).coerceIn(0.06f, 1f)
}
