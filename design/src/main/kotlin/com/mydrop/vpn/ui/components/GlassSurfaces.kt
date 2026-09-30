package com.mydrop.vpn.ui.components

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.mydrop.vpn.ui.theme.Glass
import com.mydrop.vpn.ui.theme.GlassStyle
import com.mydrop.vpn.ui.theme.glass

/** The corner every glass card uses unless it has a reason not to. */
val GlassCardShape: Shape = RoundedCornerShape(28.dp)

/**
 * A card made of glass: the pane, the content on it, and a press that gives.
 *
 * Built on [Surface] rather than drawn by hand, so the ripple, the semantics and the minimum touch
 * target behave exactly as the Material card they replace. The Surface itself is transparent; the
 * pane is the modifier under it.
 *
 * The press is the expressive part. A tapped card sinks by a percent and a half on the scheme's
 * fast spatial spring and comes back with its overshoot, so a row answers the finger physically
 * rather than only with a ripple — on glass a ripple is a faint wash, and without the give a tap on
 * a translucent card can feel like it landed on nothing.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = GlassCardShape,
    style: GlassStyle = Glass.style(),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "glass-press",
    )
    val pane = modifier
        .graphicsLayer {
            scaleX = depth
            scaleY = depth
        }
        .clip(shape)
        .glass(style, shape)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = pane,
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            interactionSource = interaction,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = pane,
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
        ) {
            Column(content = content)
        }
    }
}

private const val PRESSED_SCALE = 0.985f

/**
 * Frosts everything behind the dialog this is called from.
 *
 * A dialog is its own window, so no blur drawn inside the app's window can reach under it — but
 * Android 12 can blur behind a window itself, across windows, on the compositor. That turns the
 * flat grey scrim into frosted glass for the price of one flag. Where the device or the battery
 * saver has cross-window blur switched off, the flag is ignored and the ordinary dim remains,
 * which is exactly what was there before.
 *
 * Call it anywhere inside the dialog's content. It finds the window through the view the content
 * is hosted in; outside a dialog there is no such window and it does nothing.
 */
@Composable
fun FrostBehindWindow(radius: Dp = 28.dp) {
    val view = LocalView.current
    val pixels = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, pixels) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (view.parent as? DialogWindowProvider)?.window?.let { blurBehind(it, pixels) }
        }
        onDispose { }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun blurBehind(window: Window, pixels: Int) {
    // Wrapped, because this is decoration: a firmware that objects to the flag must cost the dialog
    // its frost, never the dialog itself.
    runCatching {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = pixels }
    }
}
