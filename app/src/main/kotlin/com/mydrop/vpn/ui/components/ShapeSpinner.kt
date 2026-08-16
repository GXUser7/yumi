package com.mydrop.vpn.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.graphics.shapes.Morph
import kotlin.math.floor

/**
 * The handshake indicator: six Material shapes morphing one into the next, forever.
 *
 * Two properties are deliberate and easy to lose. The morphs run back to back with no rest, and
 * the rotation never returns to zero — a constant drift underneath, plus a quarter turn banked
 * into each morph along the same eased curve as the shape change. That is what produces the
 * accelerate-then-ease-off feel while keeping the figure permanently in motion; a spinner that
 * pauses reads as a hang.
 *
 * The shapes are the real [MaterialShapes] constants, so the geometry is Google's own: straight
 * edges joined by true arcs, nothing with a sharp corner.
 */
private val spinnerShapes = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Pill,
    MaterialShapes.Triangle,
    MaterialShapes.Square,
    MaterialShapes.Pentagon,
    MaterialShapes.Clover4Leaf,
)

/** Seconds per shape-to-shape morph. */
private const val SEGMENT_SECONDS = 0.62f

/** Degrees banked into each morph, on top of the drift. */
private const val TURN_DEGREES = 90f

/** Degrees per second the figure keeps turning no matter where it is in a morph. */
private const val DRIFT_DEGREES = 32f

@Composable
fun ShapeSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    // One morph per adjacent pair, built once: the draw pass must not allocate.
    val morphs = remember {
        spinnerShapes.indices.map { index ->
            Morph(spinnerShapes[index], spinnerShapes[(index + 1) % spinnerShapes.size])
        }
    }
    val scratch = remember { Path() }
    val ink = rememberUpdatedState(color)
    val frameMillis = rememberFrameMillis()

    Canvas(modifier = modifier.size(size)) {
        // Read in the draw scope: the spinner never recomposes, it only redraws.
        val seconds = frameMillis.value / 1000f
        val segment = seconds / SEGMENT_SECONDS
        val index = floor(segment).toInt()
        val progress = segment - index
        val eased = easeInOutCubic(progress)

        val angle = seconds * DRIFT_DEGREES + (index + eased) * TURN_DEGREES
        val path = morphs[index.mod(morphs.size)].toPath(eased, scratch)

        withTransform({
            rotate(angle, pivot = center)
            // MaterialShapes are normalised into a 0..1 box.
            scale(this@Canvas.size.width, this@Canvas.size.height, pivot = Offset.Zero)
        }) {
            drawPath(path = path, color = ink.value)
        }
    }
}

/**
 * Milliseconds since this spinner first drew. Rebased on the first frame on purpose: the raw
 * frame clock counts from an arbitrary epoch, and dividing a number that large into a Float
 * leaves less than frame-level precision, which shows up as stutter.
 */
@Composable
private fun rememberFrameMillis(): State<Long> = produceState(0L) {
    var origin = 0L
    while (true) {
        withInfiniteAnimationFrameMillis { frame ->
            if (origin == 0L) origin = frame
            value = frame - origin
        }
    }
}

private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
