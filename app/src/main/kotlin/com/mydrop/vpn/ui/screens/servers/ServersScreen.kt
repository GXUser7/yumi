package com.mydrop.vpn.ui.screens.servers

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.format.pluralServers
import com.mydrop.vpn.core.format.pluralSources
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.ui.MainUiState
import com.mydrop.vpn.ui.components.BadgeRow
import com.mydrop.vpn.ui.components.LatencyChip
import com.mydrop.vpn.ui.components.ProtocolBadge
import com.mydrop.vpn.ui.components.QrShareDialog
import com.mydrop.vpn.ui.components.ScreenHeader

enum class ServerSort(val label: String) {
    Default("По порядку"),
    Latency("По задержке"),
    Name("По названию"),
    Protocol("По протоколу"),
}

private data class ServerGroup(
    val id: String,
    val title: String,
    val nodes: List<ProxyNode>,
)

@Composable
fun ServersScreen(
    state: MainUiState,
    onSelect: (String) -> Unit,
    onPing: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetTlsInsecure: (String, Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var sharing by remember { mutableStateOf<ProxyNode?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ServerSort.Default) }
    val collapsedGroups = remember { mutableStateOf(emptySet<String>()) }

    val groups = remember(state.nodes, state.subscriptions, state.latencies, query, sort) {
        buildGroups(state, query, sort)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = "Серверы",
                subtitle = "${pluralServers(state.nodes.size)} · ${pluralSources(state.subscriptions.size)}",
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        item(key = "search") {
            SearchAndSortRow(
                query = query,
                onQueryChange = { query = it },
                sort = sort,
                onSortChange = { sort = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (groups.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    hasNodes = state.nodes.isNotEmpty(),
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        groups.forEach { group ->
            val collapsed = group.id in collapsedGroups.value

            item(key = "header-${group.id}") {
                GroupHeader(
                    title = group.title,
                    count = group.nodes.size,
                    collapsed = collapsed,
                    onToggle = {
                        collapsedGroups.value = if (collapsed) {
                            collapsedGroups.value - group.id
                        } else {
                            collapsedGroups.value + group.id
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (!collapsed) {
                items(group.nodes, key = { it.id }) { node ->
                    ServerRow(
                        node = node,
                        selected = node.id == state.selectedNode?.id,
                        latency = state.latencies[node.id],
                        isMeasuring = node.id in state.pingingNodeIds,
                        onSelect = { onSelect(node.id) },
                        onPing = { onPing(node.id) },
                        onRemove = { onRemove(node.id) },
                        onSetTlsInsecure = node.tls
                            ?.takeIf { it.enabled && it.reality == null }
                            ?.let { tls -> { onSetTlsInsecure(node.id, !tls.insecure) } },
                        onShare = node.sourceUri?.let { { sharing = node } },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(88.dp)) }
    }

    sharing?.let { node ->
        QrShareDialog(
            title = node.name,
            link = node.sourceUri.orEmpty(),
            onDismiss = { sharing = null },
        )
    }
}

private fun buildGroups(
    state: MainUiState,
    query: String,
    sort: ServerSort,
): List<ServerGroup> {
    val normalizedQuery = query.trim().lowercase()

    fun matches(node: ProxyNode): Boolean =
        normalizedQuery.isEmpty() ||
            node.name.lowercase().contains(normalizedQuery) ||
            node.server.lowercase().contains(normalizedQuery) ||
            node.protocol.label.lowercase().contains(normalizedQuery)

    fun sorted(nodes: List<ProxyNode>): List<ProxyNode> = when (sort) {
        ServerSort.Default -> nodes
        ServerSort.Name -> nodes.sortedBy { it.name.lowercase() }
        ServerSort.Protocol -> nodes.sortedBy { it.protocol.label }
        // Unmeasured and dead servers sink to the bottom instead of pretending to be instant.
        ServerSort.Latency -> nodes.sortedBy { node ->
            val result = state.latencies[node.id]
            when {
                result == null -> Int.MAX_VALUE - 1
                result.failed -> Int.MAX_VALUE
                else -> result.millis
            }
        }
    }

    val subscriptionGroups = state.subscriptions.map { subscription ->
        ServerGroup(
            id = subscription.id,
            title = subscription.remoteTitle ?: subscription.name,
            nodes = sorted(
                state.nodes.filter { it.subscriptionId == subscription.id && matches(it) },
            ),
        )
    }

    val manual = sorted(state.manualNodes().filter(::matches))
    val manualGroup = if (manual.isEmpty()) {
        null
    } else {
        ServerGroup(id = "manual", title = "Добавлено вручную", nodes = manual)
    }

    return (subscriptionGroups + listOfNotNull(manualGroup)).filter { it.nodes.isNotEmpty() }
}

@Composable
private fun SearchAndSortRow(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: ServerSort,
    onSortChange: (ServerSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Поиск сервера") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
        )

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.SwapVert, contentDescription = "Сортировка")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ServerSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSortChange(option)
                            menuOpen = false
                        },
                        trailingIcon = {
                            if (option == sort) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The whole header toggles, and the arrow is a plain icon rather than an IconButton.
    // An IconButton centres its 24 dp glyph in a container whose size is a theme decision, so the
    // gap between glyph and screen edge was whatever that container happened to be — which is how
    // the arrow kept drifting inward. A bare icon ends exactly where the row ends, level with the
    // search field above it, and the row itself is a far bigger target than the button ever was.
    //
    // Title and badge share one weighted slot. Giving the title its own `weight(1f, fill = false)`
    // alongside a weighted spacer split the free space between the two of them, so the spacer could
    // only ever push the arrow halfway — which is where it sat, stranded in the middle of the row
    // with a screen's worth of gap to its right.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Icon(
            imageVector = if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            contentDescription = if (collapsed) "Развернуть" else "Свернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerRow(
    node: ProxyNode,
    selected: Boolean,
    latency: LatencyResult?,
    isMeasuring: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onRemove: () -> Unit,
    /**
     * Null when there is no certificate check to switch off: a plaintext server has none, and
     * REALITY authenticates by key exchange and never looks at the one it is shown, so the core
     * ignores the flag there and the menu entry would promise something it cannot do.
     */
    onSetTlsInsecure: (() -> Unit)?,
    /** Null when the server was not parsed from a link and so has nothing to hand over. */
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "row-color",
    )

    Card(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // No selection dot: the row's own container colour already states the selection, and
            // a marker that is invisible three quarters of the time still cost a permanent gutter.
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProtocolBadge(node.protocol)
                    BadgeRow(node.badges)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = node.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            LatencyChip(
                result = latency,
                isMeasuring = isMeasuring,
                unmeasurableHint = node.protocol.isQuicBased,
            )

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.NetworkPing, contentDescription = "Действия")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Проверить задержку") },
                        leadingIcon = { Icon(Icons.Rounded.NetworkPing, null) },
                        onClick = {
                            onPing()
                            menuOpen = false
                        },
                    )
                    if (onSetTlsInsecure != null) {
                        val skipping = node.tls?.insecure == true
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (skipping) {
                                        "Проверять сертификат"
                                    } else {
                                        "Не проверять сертификат"
                                    },
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (skipping) Icons.Rounded.GppGood else Icons.Rounded.GppMaybe,
                                    null,
                                )
                            },
                            onClick = {
                                onSetTlsInsecure()
                                menuOpen = false
                            },
                        )
                    }
                    if (onShare != null) {
                        DropdownMenuItem(
                            text = { Text("Показать QR-код") },
                            leadingIcon = { Icon(Icons.Rounded.QrCode2, null) },
                            onClick = {
                                onShare()
                                menuOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Удалить сервер") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = {
                            onRemove()
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasNodes: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (hasNodes) "Ничего не найдено" else "Серверов пока нет",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (hasNodes) {
                "Попробуйте изменить запрос"
            } else {
                "Добавьте подписку или вставьте ссылку на сервер"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ping-all affordance; shows the expressive loading indicator while probes are in flight. */
@Composable
fun PingAllButtonContent(isBusy: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (isBusy) 360f else 0f,
        label = "ping-rotation",
    )
    if (isBusy) {
        LoadingIndicator(modifier = Modifier.size(24.dp))
    } else {
        Icon(
            imageVector = Icons.Rounded.Bolt,
            contentDescription = "Проверить все",
            modifier = Modifier.rotate(rotation),
        )
    }
}
