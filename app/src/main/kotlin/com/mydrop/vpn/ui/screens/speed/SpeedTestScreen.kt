package com.mydrop.vpn.ui.screens.speed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.format.ValueAndUnit
import com.mydrop.vpn.core.format.formatMegabits
import com.mydrop.vpn.core.model.SpeedPhase
import com.mydrop.vpn.core.model.SpeedTestState
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.SpeedGauge
import com.mydrop.vpn.ui.components.SpeedTrace
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle

/**
 * The speed test.
 *
 * The screen has two faces and swaps between them rather than showing both at once. While a phase
 * runs, the dial and the live trace answer "what is happening"; once it is over, they give the
 * space up to the results, because a dial resting on a final number is a worse way to read that
 * number than the number itself. The swap is a fade with a little scale under the expressive
 * springs — the same motion the rest of the app changes state with.
 *
 * The path is stated as plainly as the figures. A VPN client that reports throughput without
 * saying whether the bytes went through the tunnel is reporting the phone's own connection half
 * the time, and the number that matters here is the server's.
 */
@Composable
fun SpeedTestScreen(
    state: SpeedTestState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val accent = when (state.phase) {
        SpeedPhase.Upload -> semantic.upload
        SpeedPhase.Failed -> MaterialTheme.colorScheme.error
        else -> semantic.download
    }
    val showResults = !state.running && state.hasResult

    // Read here rather than inside transitionSpec: that lambda is not a composable scope, so the
    // motion scheme has to be captured while we are still in one.
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val appearing = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val leaving = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader(
            title = "Скорость",
            subtitle = pathDescription(state),
            modifier = Modifier.padding(bottom = 6.dp),
            actions = { TonalIconButton(Icons.Rounded.ArrowBack, "Назад", onBack) },
        )

        AnimatedContent(
            targetState = showResults,
            transitionSpec = {
                (
                    fadeIn(appearing) +
                        scaleIn(animationSpec = spatial, initialScale = 0.92f)
                    ) togetherWith (
                    fadeOut(leaving) +
                        scaleOut(animationSpec = spatial, targetScale = 0.94f)
                    )
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "speed-stage",
        ) { finished ->
            if (finished) {
                ResultsPanel(state = state, modifier = Modifier.fillMaxSize())
            } else {
                LiveStage(state = state, accent = accent, modifier = Modifier.fillMaxSize())
            }
        }

        Control(
            running = state.running,
            hasResult = state.hasResult,
            onStart = onStart,
            onStop = onStop,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/** What the tunnel was doing when the numbers were taken — never left to be guessed. */
private fun pathDescription(state: SpeedTestState): String = when {
    state.phase == SpeedPhase.Failed -> state.message ?: "Замер не удался"
    state.throughTunnel && state.serverName != null -> "Через сервер «${state.serverName}»"
    state.throughTunnel -> "Через туннель"
    else -> "Напрямую — туннель выключен, это канал самого телефона"
}

/* ── While it runs ────────────────────────────────────────────────────────────────────────── */

@Composable
private fun LiveStage(state: SpeedTestState, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)

            SpeedGauge(
                bytesPerSecond = state.liveBytesPerSecond,
                accent = accent,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(side),
            )

            // Below the hub, in the wedge the dial leaves open at the bottom. Centred, it would sit
            // on the needle's pivot with the needle sweeping through the digits.
            Reading(
                rate = formatMegabits(state.liveBytesPerSecond),
                phase = state.phase,
                accent = accent,
                message = state.message,
                modifier = Modifier.offset(y = side * 0.26f),
            )
        }

        SpeedTrace(
            samples = state.series,
            accent = accent,
            gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )

        // What is already known, while the rest is still being measured.
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.latencyMillis > 0) Chip("Отклик ${state.latencyMillis} мс")
            if (state.downloadBytesPerSecond > 0) {
                Chip("Приём ${formatMegabits(state.downloadBytesPerSecond)}")
            }
        }
    }
}

@Composable
private fun Reading(
    rate: ValueAndUnit,
    phase: SpeedPhase,
    accent: Color,
    message: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = rate.value,
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = rate.unit, style = MaterialTheme.typography.labelLarge, color = accent)

        Spacer(Modifier.height(10.dp))

        // Keyed on the phase, not on its text: keying on text crossfades the label every time a
        // digit changes, which reads as flicker.
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "phase",
        ) { current ->
            Text(
                text = if (current == SpeedPhase.Failed) message ?: current.label else current.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* ── Once it is over ──────────────────────────────────────────────────────────────────────── */

@Composable
private fun ResultsPanel(state: SpeedTestState, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current

    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResultCard(
            label = "Приём",
            icon = Icons.Rounded.ArrowDownward,
            value = formatMegabits(state.downloadBytesPerSecond),
            samples = state.downloadSeries,
            accent = semantic.download,
            modifier = Modifier.weight(1f),
        )
        ResultCard(
            label = "Отдача",
            icon = Icons.Rounded.ArrowUpward,
            value = formatMegabits(state.uploadBytesPerSecond),
            samples = state.uploadSeries,
            accent = semantic.upload,
            modifier = Modifier.weight(1f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Отклик ${state.latencyMillis} мс")
            Chip("Разброс ${state.jitterMillis} мс")
            if (state.phase == SpeedPhase.Failed) Chip("Оборвался")
        }
    }
}

/**
 * One direction's result: the figure, then the shape it was made of.
 *
 * The number and its trace share a container because they are one statement — an average alone
 * cannot separate a steady link from one that burst and stalled to the same mean.
 */
@Composable
private fun ResultCard(
    label: String,
    icon: ImageVector,
    value: ValueAndUnit,
    samples: List<Long>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = value.value,
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = accent,
                )
                Text(
                    text = value.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        SpeedTrace(
            samples = samples,
            accent = accent,
            gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            live = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text = text,
        style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/* ── Control ──────────────────────────────────────────────────────────────────────────────── */

private val ControlHeight = 96.dp

@Composable
private fun Control(
    running: Boolean,
    hasResult: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = if (running) onStop else onStart,
        modifier = modifier.fillMaxWidth().height(ControlHeight),
        shape = RoundedCornerShape(if (running) 34.dp else ControlHeight / 2),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (running) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = when {
                running -> Icons.Rounded.Stop
                hasResult -> Icons.Rounded.Refresh
                else -> Icons.Rounded.PlayArrow
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = when {
                running -> "Остановить"
                hasResult -> "Ещё раз"
                else -> "Замерить"
            },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
        )
    }
}
