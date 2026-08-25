package com.mydrop.vpn.ui.screens.failover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.FailoverGroup
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.ui.components.LatencyChip
import com.mydrop.vpn.ui.components.ProtocolBadge
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.format.pluralServers

/**
 * Chooses which servers the tunnel is allowed to move onto.
 *
 * Left empty, the group is filled from the current server's own subscription. Naming servers
 * explicitly is what makes the feature usable across providers, and it is also the only way to
 * say "not that one" about a server that technically works.
 */
@Composable
fun FailoverScreen(
    settings: AppSettings,
    nodes: List<ProxyNode>,
    latencies: Map<String, LatencyResult>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // A direct outbound would take traffic out of the tunnel entirely, so it is not offered.
    val candidates = nodes.filter { it.settings != ProxySettings.Direct }
    val chosen = settings.failoverNodeIds

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.failover_title),
                titleStyle = MaterialTheme.typography.headlineLarge,
                subtitle = if (chosen.isEmpty()) {
                    stringResource(R.string.failover_automatic)
                } else {
                    stringResource(R.string.failover_chosen, pluralServers(chosen.size))
                },
                modifier = Modifier.padding(bottom = 10.dp),
                actions = {
                    TonalIconButton(
                        Icons.Rounded.ArrowBack,
                        stringResource(R.string.action_back),
                        onBack,
                    )
                },
            )
        }

        item(key = "explainer") {
            Text(
                text = stringResource(R.string.failover_explanation, FailoverGroup.MAX_GROUP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (chosen.isNotEmpty()) {
            item(key = "reset") {
                TextButton(
                    onClick = { onUpdate { it.copy(failoverNodeIds = emptySet()) } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text(stringResource(R.string.failover_clear_selection))
                }
            }
        }

        if (candidates.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.failover_no_servers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        items(candidates, key = { it.id }) { node ->
            NodeRow(
                node = node,
                latency = latencies[node.id],
                checked = node.id in chosen,
                onToggle = {
                    onUpdate { current ->
                        val ids = current.failoverNodeIds
                        current.copy(
                            failoverNodeIds = if (node.id in ids) ids - node.id else ids + node.id,
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item(key = "tail") { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun NodeRow(
    node: ProxyNode,
    latency: LatencyResult?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })

            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProtocolBadge(node.protocol)
                    Text(
                        text = node.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }

            LatencyChip(
                result = latency,
                isMeasuring = false,
                unmeasurableHint = node.protocol.isQuicBased,
            )
        }
    }
}
