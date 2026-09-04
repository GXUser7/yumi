package com.mydrop.vpn.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.graphics.shapes.toPath
import com.mydrop.vpn.core.model.WorldMap
import kotlin.math.floor

/**
 * The world as a square window onto a scrolling map, drawn in pixels.
 *
 * Square rather than round, because the figure it lives in is a rounded square and a circle inside
 * one leaves four corners of nothing. So this is not a globe seen from outside — it is a strip of
 * map running past a window, filling it edge to edge and wrapping at the antimeridian, which is a
 * seam in the array and not in the world.
 *
 * Three colours and no more. Sea is `primary`, land is `primaryContainer`, and the grid between
 * cells is the figure's own background showing through. Nothing is shaded and nothing is
 * translucent. The one exception is the marker, and it earns its place by being the only thing on
 * screen that is not the planet.
 *
 * The strip is baked once into a bitmap of one block of pixels per cell and drawn with nearest
 * neighbour, so the grid survives being scaled up — which is why the paint refuses to filter.
 * Smoothing a pixel planet is the one thing it must not do.
 */
@Composable
fun PixelPlanet(
    mask: ByteArray,
    /** Where the tunnel comes out, or null while there is nowhere to point at. */
    latitude: Double?,
    longitude: Double?,
    connected: Boolean,
    warming: Boolean,
    seaColor: Color,
    landColor: Color,
    gapColor: Color,
    markerColor: Color,
    modifier: Modifier = Modifier,
) {
    val seconds by rememberFrameSeconds()

    val strip = remember(mask, seaColor, landColor, gapColor) {
        bakeStrip(mask, seaColor.toArgb(), landColor.toArgb(), gapColor.toArgb())
    }
    // Held across frames rather than rebuilt in each one: sixty allocations a second of things
    // that are written before they are read is garbage for nothing.
    val paint = remember { Paint().apply { isFilterBitmap = false; isAntiAlias = false } }
    val destination = remember { RectF() }
    val camera = remember { MapCamera() }
    // The one smooth thing on a planet made of squares, and deliberately so. Bounds are measured
    // rather than assumed, because where MaterialShapes centres its polygons is its business.
    val marker = remember { MaterialShapes.Cookie4Sided.toPath() }
    val markerBounds = remember(marker) { RectF().also { marker.computeBounds(it, true) } }
    val markerScratch = remember { Path() }
    val markerMatrix = remember { Matrix() }
    val markerPaint = remember { Paint().apply { isAntiAlias = true } }

    Canvas(modifier.fillMaxSize()) {
        // Advanced here rather than in a coroutine writing to state, because `seconds` already
        // recomposes this every frame and a second frame-rate state would only add a recomposition
        // carrying the same numbers. The clock is monotonic, so the result is the same either way.
        camera.advance(seconds, size.width, size.height, connected, warming, latitude, longitude)
        // Painted under the strip so that scrolling past the top of the map reads as more ocean
        // rather than as a hole. Above the eighty-first parallel it very nearly is.
        drawRect(color = seaColor)
        drawStrip(camera, strip, destination, paint)
        if (connected && latitude != null && longitude != null) {
            markerPaint.color = markerColor.toArgb()
            drawMarker(seconds, camera, marker, markerBounds, markerScratch, markerMatrix, markerPaint)
        }
    }
}

/** Degrees a cell covers: chunky enough to read as pixels, fine enough to read as Europe. */
private const val CELL_DEGREES = 2.0

/**
 * The band of latitude the strip covers: all of it, pole to pole.
 *
 * Cropping the poles was the obvious economy and it was wrong. Bringing a country to the middle of
 * the window means scrolling half a window-height of map above it, and a strip that stops at the
 * eighty-first parallel does not have that much map above Riga — so the top edge came into view
 * and the map looked cut off, which is exactly what it was. Antarctica is kept out of sight by the
 * idle zoom below rather than by being thrown away, because the same rows are what let Scandinavia
 * reach the centre.
 */
private const val LATITUDE_TOP = 90.0
private const val LATITUDE_BOTTOM = -90.0
private const val LATITUDE_SPAN = LATITUDE_TOP - LATITUDE_BOTTOM

private const val COLUMNS = (360.0 / CELL_DEGREES).toInt()
private const val ROWS = (LATITUDE_SPAN / CELL_DEGREES).toInt()

/** Bitmap pixels per cell; the last row and column of each are the grid gap, baked in. */
private const val CELL_PIXELS = 4

/** How much wider the strip is than tall, which is what makes a square window show a slice. */
private const val STRIP_ASPECT = (360.0 / LATITUDE_SPAN).toFloat()

private fun bakeStrip(mask: ByteArray, sea: Int, land: Int, gap: Int): Bitmap {
    val width = COLUMNS * CELL_PIXELS
    val height = ROWS * CELL_PIXELS
    val pixels = IntArray(width * height) { gap }
    for (row in 0 until ROWS) {
        val latitude = LATITUDE_TOP - (row + 0.5) * CELL_DEGREES
        for (column in 0 until COLUMNS) {
            val longitude = -180.0 + (column + 0.5) * CELL_DEGREES
            val colour = if (WorldMap.isLand(mask, latitude, longitude)) land else sea
            for (y in 0 until CELL_PIXELS - 1) {
                val base = (row * CELL_PIXELS + y) * width + column * CELL_PIXELS
                for (x in 0 until CELL_PIXELS - 1) pixels[base + x] = colour
            }
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Where the window sits on the strip, integrated from the frame clock.
 *
 * Springs rather than eased tweens, for the reason Material gives for preferring them: a spring
 * can be redirected mid-flight without a seam, and the tunnel changing servers under a running
 * animation is exactly that.
 */
private class MapCamera {
    var stripWidth = 0f
        private set
    var stripHeight = 0f
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set

    /**
     * How far the marker has to come off centre for it to still be over its country.
     *
     * Zero everywhere a server is ever likely to be. It stops being zero only past about the
     * seventieth parallel, where centring the country would mean scrolling off the top of the
     * world — and there the map staying whole matters more than the shape staying dead centre.
     */
    var markerOffsetY = 0f
        private set

    private var scroll = 0.0
    private var scrollSpeed = 0.0
    private var zoom = 1.0
    private var zoomSpeed = 0.0
    private var lastSeconds = Float.NaN

    fun advance(
        seconds: Float,
        width: Float,
        height: Float,
        connected: Boolean,
        warming: Boolean,
        latitude: Double?,
        longitude: Double?,
    ) {
        // A first frame has no previous one to measure against, and a figure returning from the
        // background can hand this a step of several seconds. Both would fling the springs.
        val step =
            if (lastSeconds.isNaN()) 0.0 else (seconds - lastSeconds).toDouble().coerceIn(0.0, 0.05)
        lastSeconds = seconds
        if (width <= 0f || height <= 0f) return

        val zoomTarget = if (connected) CONNECTED_ZOOM else IDLE_ZOOM
        zoomSpeed += (-STIFFNESS * (zoom - zoomTarget) - DAMPING * zoomSpeed) * step
        // Floored at the idle zoom, so the strip is never shorter than the window. A spring
        // relaxing out of the connected zoom overshoots below its target, and a strip a hair
        // shorter than the window is what turned the clamp below inside out and crashed the app.
        zoom = (zoom + zoomSpeed * step).coerceAtLeast(IDLE_ZOOM.toDouble())

        stripHeight = (height * zoom).toFloat()
        stripWidth = stripHeight * STRIP_ASPECT

        if (connected && latitude != null && longitude != null) {
            val turn = stripWidth.toDouble()
            var target = (longitude + 180.0) / 360.0 * turn - width / 2.0
            // Unwrapped against where the window actually is, so a switch from Latvia to France
            // scrolls the short way instead of unwinding most of the world.
            while (target - scroll > turn / 2) target -= turn
            while (target - scroll < -turn / 2) target += turn
            scrollSpeed += (-STIFFNESS * (scroll - target) - DAMPING * scrollSpeed) * step
            scroll += scrollSpeed * step
        } else {
            scroll += step * stripWidth * if (warming) FAST_DRIFT else IDLE_DRIFT
            scrollSpeed = 0.0
        }

        originX = -scroll.toFloat()

        val wanted = if (connected && latitude != null) {
            height / 2f - ((LATITUDE_TOP - latitude) / LATITUDE_SPAN * stripHeight).toFloat()
        } else {
            (height - stripHeight) / 2f
        }
        // Held on both sides. Leaving the top free was tried on the argument that the three
        // northernmost rows carry no land — 90° to 84° is Arctic ocean, nought cells of a hundred
        // and eighty — so the sea painted behind would meet a strip beginning in the same sea and
        // the edge would not show. On a device it showed immediately: the colours match, but the
        // strip has a grid baked into it and the backdrop does not, so scrolling past the top left
        // a fifth of the figure as flat colour against a textured map. Centring Germany costs
        // about that much.
        //
        // What gives instead is the marker: whatever the clamp took, it takes too, so it stays
        // over its country rather than drifting onto the wrong sea. Dead centre for anything
        // between roughly the fortieth parallels, and a little off it further out.
        originY = wanted.coerceIn((height - stripHeight).coerceAtMost(0f), 0f)
        markerOffsetY = originY - wanted
    }

    private companion object {
        /** Strip widths per second, so the drift reads the same whatever size the figure is. */
        const val IDLE_DRIFT = 0.018
        const val FAST_DRIFT = 0.10
        /**
         * Idle sits a little inside the poles, which is how Antarctica stays off screen without
         * being cut out of the data.
         *
         * Connected is gentle on purpose. An earlier 3.8 did put every European country dead
         * centre without the clamp ever biting, and it was useless to look at: a square window
         * over forty-seven degrees is close enough that the coastline stops being recognisable as
         * anywhere. At 1.5 the window covers a hundred and twenty degrees, the marker still lands
         * centred for anything north of about thirty south, and the country is somewhere you can
         * place at a glance.
         */
        const val IDLE_ZOOM = 1.35
        const val CONNECTED_ZOOM = 1.5
        const val STIFFNESS = 24.0
        const val DAMPING = 9.0
    }
}

private fun DrawScope.drawStrip(
    camera: MapCamera,
    strip: Bitmap,
    destination: RectF,
    paint: Paint,
) {
    if (camera.stripWidth <= 0f || camera.stripHeight <= 0f) return
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        // Start at the copy left of the window and walk right until past its edge. The strip is
        // always wider than the window, so this is two copies — briefly three while a spring
        // overshoots — and never the whole world laid out off-screen.
        var x = camera.originX - floor(camera.originX / camera.stripWidth + 1f) * camera.stripWidth
        while (x < size.width) {
            destination.set(
                x,
                camera.originY,
                x + camera.stripWidth,
                camera.originY + camera.stripHeight,
            )
            native.drawBitmap(strip, null, destination, paint)
            x += camera.stripWidth
        }
    }
}

/**
 * The cookie, planted at the middle of the window and turning.
 *
 * Drawn at the centre rather than at the projected position of the country, and the difference is
 * the point: the marker is the viewfinder and the map is what moves. The camera above scrolls the
 * exit country underneath it, so during the flight the two converge instead of the marker sliding
 * about — and when it settles, the country is exactly under the shape.
 */
private fun DrawScope.drawMarker(
    seconds: Float,
    camera: MapCamera,
    shape: Path,
    bounds: RectF,
    scratch: Path,
    matrix: Matrix,
    paint: Paint,
) {
    if (bounds.width() <= 0f || bounds.height() <= 0f || camera.stripHeight <= 0f) return
    val centreX = size.width / 2f
    val centreY = size.height / 2f + camera.markerOffsetY
    // Sized off the cell, so the cookie stays the same few cells across at every zoom instead of
    // growing into a lid over the country it is pointing at.
    val side = camera.stripHeight / ROWS * 3.4f
    matrix.setScale(side / bounds.width(), side / bounds.height())
    matrix.postTranslate(
        centreX - bounds.left * side / bounds.width() - side / 2f,
        centreY - bounds.top * side / bounds.height() - side / 2f,
    )
    // Clockwise, which is what a positive angle is once y points down. Four lobes means a full
    // turn is four repeats, so the rate is set against the lobe rather than the revolution: at
    // eight degrees a second the shape comes back to itself every eleven seconds.
    matrix.postRotate(seconds * MARKER_DEGREES_PER_SECOND, centreX, centreY)
    shape.transform(matrix, scratch)
    drawIntoCanvas { it.nativeCanvas.drawPath(scratch, paint) }
}

private const val MARKER_DEGREES_PER_SECOND = 8f

/** Seconds since the figure appeared, ticking once per frame. */
@Composable
private fun rememberFrameSeconds(): State<Float> = produceState(0f) {
    var origin = 0L
    while (true) {
        withInfiniteAnimationFrameMillis { frame ->
            if (origin == 0L) origin = frame
            value = (frame - origin) / 1000f
        }
    }
}
