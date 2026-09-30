package com.mydrop.vpn.ui.components

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The ground every screen stands on: Material 3 Expressive shapes — the very polygons the
 * components and the connect spinner use — drifting behind the content, with volume.
 *
 * Carried over from YouCloud, so the two apps are one material. It replaced three still pools of
 * coloured light and a layer of grain, which had a reason (the room lit by the tunnel's state) and
 * read as a template anyway: a brown gradient under a grid of outlined cards. The tunnel's state is
 * on the connect screen, in the figure and the headline; the ground only has to be a place.
 *
 * Each shape is lit from one direction: a gradient from a lit face to a shaded one, a soft
 * highlight, a rim of light along the lit edge and a blurred shadow cast away from the light.
 * Shapes sit at different depths; nearer ones are larger, stronger and move more, farther ones
 * fade into the ground. Tilting the phone slides them by depth (parallax) and swings the light,
 * and a shake knocks them about on springs — so the scene reads as objects in a space behind the
 * glass, not as a flat pattern.
 *
 * Motion comes from the accelerometer rather than the gyroscope: gravity in it gives the tilt and
 * what is left over gives the shakes, from one cheap sensor every phone has. It is only listened to
 * while the screen is resumed, and the frames stop with the window — a VPN left running all day
 * spends nothing on its backdrop while nobody is looking at it.
 */

/** One shape of the scene. Positions and sizes are fractions of the screen. */
private class BackdropShape(
    val from: RoundedPolygon,
    val to: RoundedPolygon?,
    val cx: Float,
    val cy: Float,
    val size: Float,
    /** 0 = far back, 1 = right behind the glass. */
    val depth: Float,
    /** Degrees per second; the sign sets the direction. */
    val spin: Float,
    val morphSeconds: Float = 0f
) {
    val morph: Morph? = to?.let { Morph(from, it) }
    val basePath: android.graphics.Path = from.toPath()
}

private fun backdropShapes(): List<BackdropShape> = listOf(
    BackdropShape(MaterialShapes.SoftBurst, null, cx = 0.9f, cy = 0.74f, size = 0.6f, depth = 0.25f, spin = 3f),
    BackdropShape(MaterialShapes.Pentagon, null, cx = 0.28f, cy = 0.07f, size = 0.22f, depth = 0.3f, spin = 9f),
    BackdropShape(MaterialShapes.Cookie9Sided, null, cx = 0.94f, cy = 0.1f, size = 0.64f, depth = 0.5f, spin = 4f),
    BackdropShape(
        MaterialShapes.Flower, MaterialShapes.Cookie12Sided,
        cx = 0.7f, cy = 0.44f, size = 0.3f, depth = 0.65f, spin = -8f, morphSeconds = 11f
    ),
    BackdropShape(MaterialShapes.Clover4Leaf, null, cx = 0.04f, cy = 0.4f, size = 0.52f, depth = 0.8f, spin = -6f),
    BackdropShape(
        MaterialShapes.Pill, MaterialShapes.Sunny,
        cx = 0.14f, cy = 0.94f, size = 0.44f, depth = 0.95f, spin = -3f, morphSeconds = 14f
    )
)

/** Positions and velocities of the shapes, integrated once per frame. */
private class BackdropMotion(count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    val wobble = FloatArray(count)
    val wobbleVelocity = FloatArray(count)
    val angle = FloatArray(count) { it * 37f }
    var time = 0f
    /** Where the light comes from, as a unit vector; swings with the tilt. */
    var lightX = -0.55f
    var lightY = -0.83f
    /**
     * Whether a shape is moving faster than the slow drift — a tilt being followed, a shake
     * settling. Otherwise the scene only turns and breathes, under a pixel a frame at thirty frames a
     * second, and blurred: drawing it more often than that is work nobody can see.
     */
    var lively = true
        private set

    fun step(dt: Float, shapes: List<BackdropShape>, tilt: TiltSensor, shiftPx: Float) {
        time += dt
        var fastest = 0f
        var fastestWobble = 0f
        // Shakes since the last frame, consumed by this one.
        val kickX = tilt.kickX
        val kickY = tilt.kickY
        val kick = tilt.kick
        tilt.clearImpulse()
        shapes.forEachIndexed { i, shape ->
            // Springs toward the parallax position: nearer shapes travel further.
            val targetX = -tilt.tiltX * shape.depth * shiftPx
            val targetY = tilt.tiltY * shape.depth * shiftPx
            vx[i] += ((targetX - x[i]) * STIFFNESS - vx[i] * DAMPING) * dt
            vy[i] += ((targetY - y[i]) * STIFFNESS - vy[i] * DAMPING) * dt
            // A shake throws them the other way, then the springs pull them home.
            vx[i] = (vx[i] - kickX * shape.depth * KICK_GAIN * shiftPx).coerceIn(-MAX_SPEED * shiftPx, MAX_SPEED * shiftPx)
            vy[i] = (vy[i] + kickY * shape.depth * KICK_GAIN * shiftPx).coerceIn(-MAX_SPEED * shiftPx, MAX_SPEED * shiftPx)
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt

            wobbleVelocity[i] += (-wobble[i] * WOBBLE_STIFFNESS - wobbleVelocity[i] * WOBBLE_DAMPING) * dt
            wobbleVelocity[i] = (wobbleVelocity[i] + kick * (if (i % 2 == 0) 1f else -1f) * WOBBLE_KICK * (0.5f + shape.depth))
                .coerceIn(-MAX_WOBBLE_SPEED, MAX_WOBBLE_SPEED)
            wobble[i] += wobbleVelocity[i] * dt

            angle[i] = (angle[i] + shape.spin * dt) % 360f
            fastest = maxOf(fastest, kotlin.math.abs(vx[i]), kotlin.math.abs(vy[i]))
            fastestWobble = maxOf(fastestWobble, kotlin.math.abs(wobbleVelocity[i]))
        }
        lively = fastest > LIVELY_SPEED * shiftPx || fastestWobble > LIVELY_WOBBLE
        val lx = -0.55f + tilt.tiltX * 0.9f
        val ly = -0.83f - tilt.tiltY * 0.9f
        val length = sqrt(lx * lx + ly * ly).coerceAtLeast(0.001f)
        lightX = lx / length
        lightY = ly / length
    }

    private companion object {
        const val STIFFNESS = 38f
        const val DAMPING = 7f
        // A firm shake (about half a g past gravity) throws the nearest shape roughly its full
        // parallax distance; the caps keep a violent one from flinging shapes off screen.
        const val KICK_GAIN = 8f
        const val MAX_SPEED = 16f
        const val WOBBLE_STIFFNESS = 30f
        const val WOBBLE_DAMPING = 4.5f
        const val WOBBLE_KICK = 260f
        const val MAX_WOBBLE_SPEED = 600f
        // A quarter of the parallax distance a second, a few degrees a second of wobble: past
        // these a shape moves far enough between frames for thirty of them to show.
        const val LIVELY_SPEED = 0.25f
        const val LIVELY_WOBBLE = 3f
    }
}

/**
 * Tilt and shakes from the accelerometer. Gravity is its low-passed signal; tilt is measured
 * against a slowly drifting rest pose, so the scene centres itself on however the phone is being
 * held and only answers movement. What gravity does not explain is the phone being shaken.
 */
private class TiltSensor : SensorEventListener {
    var tiltX = 0f
        private set
    var tiltY = 0f
        private set

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var restX = 0f
    private var restY = 0f
    private var primed = false
    var kickX = 0f
        private set
    var kickY = 0f
        private set
    var kick = 0f
        private set

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!primed) {
            gx = x; gy = y; gz = z
            restX = x; restY = y
            primed = true
        }
        gx += (x - gx) * 0.2f
        gy += (y - gy) * 0.2f
        gz += (z - gz) * 0.2f
        restX += (gx - restX) * 0.01f
        restY += (gy - restY) * 0.01f
        tiltX = ((gx - restX) / SensorManager.GRAVITY_EARTH * 2.2f).coerceIn(-1f, 1f)
        tiltY = ((gy - restY) / SensorManager.GRAVITY_EARTH * 2.2f).coerceIn(-1f, 1f)

        val lx = x - gx
        val ly = y - gy
        val lz = z - gz
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        if (magnitude > SHAKE_THRESHOLD) {
            kickX += lx / SensorManager.GRAVITY_EARTH
            kickY += ly / SensorManager.GRAVITY_EARTH
            kick += (magnitude - SHAKE_THRESHOLD) / SensorManager.GRAVITY_EARTH
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** The shakes read by a frame: without a Triple for them on each one. */
    fun clearImpulse() {
        kickX = 0f; kickY = 0f; kick = 0f
    }

    fun reset() {
        tiltX = 0f; tiltY = 0f
        kickX = 0f; kickY = 0f; kick = 0f
        primed = false
    }

    private companion object {
        const val SHAKE_THRESHOLD = 1.6f
    }
}

/** Listens to the accelerometer while [enabled] and the screen is resumed, and not otherwise. */
@Composable
private fun rememberTiltSensor(enabled: Boolean): TiltSensor {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tilt = remember { TiltSensor() }
    DisposableEffect(enabled, lifecycleOwner) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || manager == null || sensor == null) {
            tilt.reset()
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    manager.registerListener(tilt, sensor, SensorManager.SENSOR_DELAY_GAME)
                Lifecycle.Event.ON_PAUSE -> {
                    manager.unregisterListener(tilt)
                    tilt.reset()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            manager.unregisterListener(tilt)
            tilt.reset()
        }
    }
    return tilt
}

/**
 * The backdrop. [motionEnabled] ties the shapes to the accelerometer; [animated] false freezes
 * the scene (nothing is redrawn per frame then).
 */
@Composable
fun ShapesBackdrop(
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
    animated: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = scheme.background.luminance() < 0.5f
    val shapes = remember { backdropShapes().sortedBy { it.depth } }
    val motion = remember { BackdropMotion(shapes.size) }
    val tilt = rememberTiltSensor(enabled = motionEnabled && animated)
    val shiftPx = with(LocalDensity.current) { PARALLAX_SHIFT_DP * density }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        var last = 0L
        var drawn = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                motion.step(dt, shapes, tilt, shiftPx)
                // Redrawn sixty times a second at most: the shapes drift slowly, and on a 120 Hz
                // screen every other frame of them was work nobody could see. Only drifting, thirty:
                // each frame of the backdrop repaints the whole screen over it, glass and all.
                val interval = if (motion.lively) BACKDROP_FRAME_NANOS else CALM_BACKDROP_FRAME_NANOS
                if (now - drawn >= interval) {
                    drawn = now
                    frame.longValue = now
                }
            }
        }
    }

    // A ground a step lighter than the app's background, so the shapes and the covers in front of
    // them have something to stand off from instead of all sinking into the same near-black.
    val ground = if (darkTheme) scheme.surfaceContainer else scheme.surfaceContainerLow
    // One family of tones — the secondary palette the launcher's widgets and folders use — so the
    // scene reads as one material at different depths rather than a handful of coloured stickers.
    val palette = BackdropPalette(
        ground = ground,
        body = scheme.secondaryContainer,
        light = if (darkTheme) scheme.secondary else Color.White,
        dark = if (darkTheme) Color.Black else scheme.secondary,
        darkTheme = darkTheme
    )
    val far = remember(shapes) { shapes.indices.filter { shapes[it].depth < NEAR_DEPTH } }
    val near = remember(shapes) { shapes.indices.filter { shapes[it].depth >= NEAR_DEPTH } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ground)
    ) {
        // Depth of field: the far shapes are the softest, the near ones less so, and the content in
        // front is the only thing in focus — which is what separates a cover from the backdrop.
        BackdropLayer(shapes, far, motion, frame, palette, blur = FAR_BLUR, shrink = FAR_SHRINK)
        BackdropLayer(shapes, near, motion, frame, palette, blur = NEAR_BLUR, shrink = NEAR_SHRINK)
    }
}

private data class BackdropPalette(
    val ground: Color,
    val body: Color,
    val light: Color,
    val dark: Color,
    val darkTheme: Boolean
)

/**
 * What a layer paints its shapes with. The gradients are made once per palette, laid along a unit
 * line or circle, and on each frame the canvas is moved to where they fall on the shape instead:
 * the shapes are redrawn sixty times a second, and brushes, shaders and strokes made afresh on
 * every one of those frames kept the garbage collector busy for nothing. What reaches the screen
 * is the same — the same gradients between the same points, over the same paths.
 */
private class BackdropPaints(shapes: List<BackdropShape>, indices: List<Int>, palette: BackdropPalette) {
    // From (0, 0) to (1, 0): lit face, body, shaded face.
    val body = arrayOfNulls<android.graphics.Shader>(shapes.size)
    // Radius 1 around (0, 0): the soft highlight.
    val highlight = arrayOfNulls<android.graphics.Shader>(shapes.size)
    // From (0, 0) to (1, 0): the rim of light, gone by the middle.
    val rim = arrayOfNulls<android.graphics.Shader>(shapes.size)

    // Compose's own defaults for a drawn path: antialiased, bitmap filtering, butt caps, mitre joins.
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.BUTT
        strokeJoin = android.graphics.Paint.Join.MITER
        strokeMiter = 4f
    }
    val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = Color.Black.copy(alpha = if (palette.darkTheme) 0.38f else 0.12f).toArgb()
    }

    // Scratch objects, reused on every frame.
    val work = android.graphics.Path()
    val local = android.graphics.Path()
    val matrix = android.graphics.Matrix()
    val toScreen = android.graphics.Matrix()
    val toLocal = android.graphics.Matrix()

    init {
        val clamp = android.graphics.Shader.TileMode.CLAMP
        val transparent = Color.Transparent.toArgb()
        for (i in indices) {
            val depth = shapes[i].depth
            // Far shapes stay close to the ground, near ones stand out of it.
            val bodyColor = lerp(palette.ground, palette.body, 0.45f + 0.55f * depth)
            val lit = lerp(bodyColor, palette.light, if (palette.darkTheme) 0.22f else 0.5f)
            val shaded = lerp(bodyColor, palette.dark, if (palette.darkTheme) 0.3f else 0.16f)
            body[i] = android.graphics.LinearGradient(
                0f, 0f, 1f, 0f,
                intArrayOf(lit.toArgb(), bodyColor.toArgb(), shaded.toArgb()), null, clamp
            )
            highlight[i] = android.graphics.RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.White.copy(alpha = 0.07f * (0.4f + depth)).toArgb(), transparent), null, clamp
            )
            rim[i] = android.graphics.LinearGradient(
                0f, 0f, 1f, 0f,
                intArrayOf(lit.copy(alpha = 0.7f).toArgb(), transparent), null, clamp
            )
        }
    }
}

@Composable
private fun BackdropLayer(
    shapes: List<BackdropShape>,
    indices: List<Int>,
    motion: BackdropMotion,
    frame: androidx.compose.runtime.MutableLongState,
    palette: BackdropPalette,
    blur: Dp,
    shrink: Int
) {
    val paints = remember(shapes, indices, palette) { BackdropPaints(shapes, indices, palette) }
    // Drawn [shrink] times smaller and stretched back, blurred on the way: soft shapes lose
    // nothing by it, and a full-screen blur on every frame was most of what the backdrop cost.
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val small = measurable.measure(
                    Constraints.fixed((width + shrink - 1) / shrink, (height + shrink - 1) / shrink)
                )
                layout(width, height) { small.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = shrink.toFloat()
                scaleY = shrink.toFloat()
                transformOrigin = TransformOrigin(0f, 0f)
                val radius = blur.toPx() / shrink
                renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            }
    ) {
        // Read so that every frame of the simulation redraws, without recomposing anything.
        frame.longValue
        scale(1f / shrink, 1f / shrink, pivot = Offset.Zero) {
            val fullWidth = size.width * shrink
            val fullHeight = size.height * shrink
            val unit = min(fullWidth, fullHeight)
            val lightX = motion.lightX
            val lightY = motion.lightY
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                val work = paints.work
                indices.forEach { i ->
                    val shape = shapes[i]
                    val diameter = shape.size * unit
                    val centerX = shape.cx * fullWidth + motion.x[i]
                    val centerY = shape.cy * fullHeight + motion.y[i] +
                        sin(motion.time * 0.35f + i * 1.7f) * 6f * density * shape.depth
                    buildShapePath(shape, motion, i, diameter, centerX, centerY, work, paints.matrix)
                    val radius = diameter / 2f

                    // A shadow thrown away from the light; nearer shapes float higher, so theirs
                    // falls further. Drawn hard: the layer's blur is what softens it.
                    val distance = (8f + 20f * shape.depth) * density
                    canvas.save()
                    canvas.translate(-lightX * distance, -lightY * distance)
                    canvas.drawPath(work, paints.shadow)
                    canvas.restore()

                    // Lit face to shaded, across the whole shape from the light's side.
                    paints.fill.shader = paints.body[i]
                    drawAlong(
                        canvas, paints, paints.fill,
                        fromX = centerX + lightX * radius, fromY = centerY + lightY * radius,
                        toX = centerX - lightX * radius, toY = centerY - lightY * radius
                    )

                    // A soft highlight toward the light, inside the shape.
                    paints.fill.shader = paints.highlight[i]
                    val glow = radius * 0.85f
                    canvas.save()
                    canvas.clipPath(work)
                    canvas.translate(centerX + lightX * (radius * 0.45f), centerY + lightY * (radius * 0.45f))
                    canvas.scale(glow, glow)
                    canvas.drawCircle(0f, 0f, 1f, paints.fill)
                    canvas.restore()

                    // A rim of light along the lit edge.
                    paints.stroke.shader = paints.rim[i]
                    drawAlong(
                        canvas, paints, paints.stroke,
                        fromX = centerX + lightX * radius, fromY = centerY + lightY * radius,
                        toX = centerX, toY = centerY,
                        strokeWidth = 1.5f * density
                    )
                }
            }
        }
    }
}

/**
 * Draws [BackdropPaints.work] with [paint], whose shader runs from (0, 0) to (1, 0), laid from
 * (fromX, fromY) to (toX, toY): the canvas is turned and scaled onto that line, and the path,
 * moved the opposite way, lands where it was. A stroke is thinned by the scale to keep its width.
 */
private fun drawAlong(
    canvas: android.graphics.Canvas,
    paints: BackdropPaints,
    paint: android.graphics.Paint,
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    strokeWidth: Float = 0f
) {
    val dx = toX - fromX
    val dy = toY - fromY
    val length = sqrt(dx * dx + dy * dy)
    if (length <= 0f) return
    val toScreen = paints.toScreen
    toScreen.setScale(length, length)
    toScreen.postRotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
    toScreen.postTranslate(fromX, fromY)
    if (!toScreen.invert(paints.toLocal)) return
    paints.work.transform(paints.toLocal, paints.local)
    if (strokeWidth > 0f) paint.strokeWidth = strokeWidth / length
    canvas.save()
    canvas.concat(toScreen)
    canvas.drawPath(paints.local, paint)
    canvas.restore()
}

private fun buildShapePath(
    shape: BackdropShape,
    motion: BackdropMotion,
    index: Int,
    diameter: Float,
    centerX: Float,
    centerY: Float,
    work: android.graphics.Path,
    matrix: android.graphics.Matrix
) {
    work.rewind()
    val morph = shape.morph
    if (morph != null && shape.morphSeconds > 0f) {
        // Eased back and forth, lingering at each end so both shapes can be read.
        val phase = (motion.time / shape.morphSeconds) * 2f * PI.toFloat()
        val progress = (1f - cos(phase)) / 2f
        morph.toPath(progress, work)
    } else {
        work.set(shape.basePath)
    }
    // MaterialShapes are normalised into the unit square.
    matrix.setTranslate(-0.5f, -0.5f)
    matrix.postScale(diameter, diameter)
    matrix.postRotate(motion.angle[index] + motion.wobble[index])
    matrix.postTranslate(centerX, centerY)
    work.transform(matrix)
}

/** How far the nearest shape slides at full tilt. */
private const val PARALLAX_SHIFT_DP = 34f

/** Shapes from this depth on are drawn in the sharper, near layer. */
private const val NEAR_DEPTH = 0.6f
private val FAR_BLUR = 8.dp
private val NEAR_BLUR = 3.dp
private const val FAR_SHRINK = 4
private const val NEAR_SHRINK = 2
private const val BACKDROP_FRAME_NANOS = 15_000_000L
// Thirty a second, with room for a frame arriving a little early.
private const val CALM_BACKDROP_FRAME_NANOS = 32_000_000L
