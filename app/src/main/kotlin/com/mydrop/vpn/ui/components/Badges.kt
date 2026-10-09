package com.mydrop.vpn.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.ui.theme.LocalSemanticColors

/** Protocol pill. REALITY-capable protocols get the primary accent to stand out in long lists. */
@Composable
fun ProtocolBadge(protocol: Protocol, modifier: Modifier = Modifier) {
    val emphasised = protocol == Protocol.VLESS || protocol == Protocol.HYSTERIA2
    Text(
        text = protocol.label,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        softWrap = false,
        color = if (emphasised) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .background(
                color = if (emphasised) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Secondary details (REALITY, WS, Vision, uTLS…) rendered as quiet outlined chips. */
@Composable
fun FeatureBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // A chip is a word, never a paragraph: squeezed below its width it must not break
        // "uTLS:edge" into a letter per line, which is what made rows four times their height.
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Protocol pill and feature chips, wrapping onto another line when the row runs out of width.
 *
 * This used to be a plain Row beside the protocol pill. A Row measures each child against whatever
 * the previous ones left over, so on a 360 dp phone a server with five chips (VLESS, Auto ×3,
 * REALITY, Vision, uTLS:edge) handed the last one a sliver a letter wide, and the card grew to fit
 * the word written vertically. The protocol pill is inside the flow rather than beside it so that
 * the chips can wrap underneath it instead of into a column of their own.
 */
@Composable
fun BadgeRow(protocol: Protocol, badges: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolBadge(protocol)
        badges.take(4).forEach { FeatureBadge(it) }
    }
}

/**
 * Latency readout. Colour buckets are coarse on purpose: users act on "fast / usable / bad",
 * and a continuous gradient would imply a precision a TCP handshake probe does not have.
 */
@Composable
fun LatencyChip(
    result: LatencyResult?,
    isMeasuring: Boolean,
    modifier: Modifier = Modifier,
    unmeasurableHint: Boolean = false,
) {
    val semantic = LocalSemanticColors.current

    val (label, color) = when {
        isMeasuring -> "…" to semantic.latencyDead
        result == null -> "—" to semantic.latencyDead
        result.failed && unmeasurableHint -> "UDP" to semantic.latencyDead
        result.failed -> stringResource(R.string.servers_latency_failed) to semantic.latencySlow
        result.millis < 150 ->
            stringResource(R.string.servers_latency_value, result.millis) to semantic.latencyFast
        result.millis < 400 ->
            stringResource(R.string.servers_latency_value, result.millis) to semantic.latencyMedium
        else ->
            stringResource(R.string.servers_latency_value, result.millis) to semantic.latencySlow
    }

    AnimatedContent(
        targetState = label,
        transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f)) togetherWith
                (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f))
        },
        label = "latency",
        modifier = modifier,
    ) { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Small filled dot used to mark the active server without stealing attention. */
