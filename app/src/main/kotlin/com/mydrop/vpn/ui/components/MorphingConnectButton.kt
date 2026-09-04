package com.mydrop.vpn.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import kotlin.math.floor

/**
 * Shapes the button cycles through while the tunnel is coming up.
 *
 * Deliberately only four, and all of them low-lobed: morphing between shapes with very
 * different vertex counts (a pill against a twelve-scalloped cookie, say) produces spiky,
 * unstable in-between forms. Staying within round silhouettes keeps every intermediate frame
 * looking like a shape someone drew on purpose.
 */
private val connectingShapes = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Pill,
    MaterialShapes.Oval,
    MaterialShapes.Clover4Leaf,
)

private val idleShape = MaterialShapes.Circle
private val connectedShape = MaterialShapes.Clover4Leaf

/**
 * How long one shape-to-shape morph takes in each handshake phase. The arc speeds up as the
 * connection gets going and eases off as it finishes verifying, so the motion itself tells you
 * roughly how far along the handshake is.
 */
private fun segmentDurationFor(phase: VpnState.Connecting.Phase?): Int = when (phase) {
    VpnState.Connecting.Phase.Starting -> 640
    VpnState.Connecting.Phase.Handshaking -> 500
    VpnState.Connecting.Phase.EstablishingTunnel -> 420
    VpnState.Connecting.Phase.Testing -> 580
    null -> 520
}

@Composable
fun MorphingConnectButton(
    state: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 248.dp,
) {
    val semantic = LocalSemanticColors.current
    val colorScheme = MaterialTheme.colorScheme

    val isConnecting = state is VpnState.Connecting
    val isConnected = state is VpnState.Connected
    val isFailed = state is VpnState.Failed

    // Prebuilt so the draw pass never allocates: one morph per adjacent pair, plus the
    // idle <-> connected morph used whenever the tunnel is not mid-handshake.
    val cycleMorphs = remember {
        connectingShapes.indices.map { index ->
            Morph(connectingShapes[index], connectingShapes[(index + 1) % connectingShapes.size])
        }
    }
    val settleMorph = remember { Morph(idleShape, connectedShape) }

    val phase by rememberUpdatedState((state as? VpnState.Connecting)?.phase)
    val cycle = remember { Animatable(0f) }

    LaunchedEffect(isConnecting) {
        if (!isConnecting) return@LaunchedEffect
        // Advancing by exactly one unit per step keeps the position continuous, so a phase
        // change alters the pace without ever snapping the shape.
        while (true) {
            cycle.animateTo(
                targetValue = cycle.value + 1f,
                animationSpec = tween(segmentDurationFor(phase), easing = EaseInOutCubic),
            )
        }
    }

    val settle by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "settle",
    )

    // Rotation only once the tunnel is up. Spinning a shape that is simultaneously morphing
    // reads as noise rather than motion, so the handshake animates form alone.
    val infinite = rememberInfiniteTransition(label = "connect-button")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val rotation = if (isConnecting) 0f else spin

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )

    val targetShapeColor = when {
        isFailed -> colorScheme.errorContainer
        isConnected -> semantic.connectedContainer
        isConnecting -> colorScheme.primaryContainer
        else -> colorScheme.surfaceContainerHighest
    }
    val shapeColor by animateColorAsState(
        targetValue = targetShapeColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "shape-color",
    )

    val accent = when {
        isFailed -> colorScheme.error
        isConnected -> semantic.connected
        else -> colorScheme.primary
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .scale(pressScale)
                .selectable(
                    selected = state.isActive,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Switch,
                    onClick = onClick,
                ),
        ) {
            // Animated values are read here, inside the draw scope, so every frame is a redraw
            // rather than a recomposition of the whole button.
            val path = if (isConnecting) {
                val position = cycle.value
                val index = floor(position).toInt()
                val fraction = position - index
                cycleMorphs[index.mod(cycleMorphs.size)].toPath(fraction)
            } else {
                settleMorph.toPath(settle)
            }

            withTransform({
                rotate(rotation, pivot = center)
                // MaterialShapes are normalised into a 0..1 box.
                scale(this@Canvas.size.width, this@Canvas.size.height, pivot = Offset.Zero)
            }) {
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = listOf(shapeColor, shapeColor.blendTowards(accent, 0.35f)),
                        start = Offset.Zero,
                        end = Offset(1f, 1f),
                    ),
                )
            }
        }

        ConnectButtonLabel(state = state, accent = accent)
    }
}

@Composable
private fun ConnectButtonLabel(state: VpnState, accent: Color) {
    val icon: ImageVector = when (state) {
        is VpnState.Connected -> Icons.Rounded.Shield
        is VpnState.Failed -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.PowerSettingsNew
    }

    val caption = stringResource(
        when (state) {
            is VpnState.Connected -> R.string.connect_state_connected
            is VpnState.Connecting -> state.phase.labelRes
            VpnState.Disconnecting -> R.string.connect_button_disconnecting
            is VpnState.Failed -> R.string.connect_state_error
            VpnState.Disconnected -> R.string.connect_button_connect
        },
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(46.dp),
        )
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "caption",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Linear blend in sRGB; adequate for a decorative gradient stop. */
private fun Color.blendTowards(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = alpha,
)
