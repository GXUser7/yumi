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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        result.failed -> "нет" to semantic.latencySlow
        result.millis < 150 -> "${result.millis} мс" to semantic.latencyFast
        result.millis < 400 -> "${result.millis} мс" to semantic.latencyMedium
        else -> "${result.millis} мс" to semantic.latencySlow
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
