package com.mydrop.vpn.ui.screens.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.CountryFilterMode
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.data.RemoteConsoleState
import com.mydrop.vpn.data.RemoteLinkState
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteSubscription
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.BadgeRow
import com.mydrop.vpn.ui.components.LatencyChip
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.ShapeSpinner
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.format.pluralServers
import com.mydrop.vpn.ui.format.pluralSources
import com.mydrop.vpn.ui.screens.servers.CountryFacet
import com.mydrop.vpn.ui.screens.servers.EmptyState
import com.mydrop.vpn.ui.screens.servers.GroupHeader
import com.mydrop.vpn.ui.screens.servers.ProtocolFacet
import com.mydrop.vpn.ui.screens.servers.ResolvedServerFilters
import com.mydrop.vpn.ui.screens.servers.SearchAndFilterRow
import com.mydrop.vpn.ui.screens.servers.ServerFilterState
import com.mydrop.vpn.ui.screens.servers.ServerSort
import com.mydrop.vpn.ui.screens.servers.ServersFilterBottomSheet

internal data class RemoteServerGroup(
    val id: String,
    val title: String,
    val nodes: List<RemoteNode>,
)

internal fun extractRemoteProtocolFacets(nodes: List<RemoteNode>): List<ProtocolFacet> {
    if (nodes.isEmpty()) return emptyList()
    val protocolCount = Protocol.entries.size
    val counts = IntArray(protocolCount)
    val lastSeenNode = IntArray(protocolCount) { -1 }

    nodes.forEachIndexed { nodeIndex, node ->
        val primaryOrdinal = node.protocol.ordinal
        lastSeenNode[primaryOrdinal] = nodeIndex
        counts[primaryOrdinal]++

        for (member in node.memberProtocols) {
            val memberOrdinal = member.ordinal
            if (lastSeenNode[memberOrdinal] != nodeIndex) {
                lastSeenNode[memberOrdinal] = nodeIndex
                counts[memberOrdinal]++
            }
        }
    }

    return buildList {
        for (protocol in Protocol.entries) {
            val count = counts[protocol.ordinal]
            if (count > 0) add(ProtocolFacet(protocol = protocol, count = count))
        }
    }
}

internal fun extractRemoteCountryFacets(nodes: List<RemoteNode>): List<CountryFacet> {
    if (nodes.isEmpty()) return emptyList()
    val counts = LinkedHashMap<String, Int>()
    for (node in nodes) {
        val code = WorldMap.countryCodeOf(node.name) ?: continue
        counts[code] = (counts[code] ?: 0) + 1
    }
    if (counts.isEmpty()) return emptyList()
    return counts.entries
        .map { (code, count) ->
            CountryFacet(
                code = code,
                flag = WorldMap.flagOf(code).orEmpty(),
                count = count,
            )
        }
        .sortedWith(compareByDescending<CountryFacet> { it.count }.thenBy { it.code })
}

internal fun resolveRemoteServerFilters(
    nodes: List<RemoteNode>,
    filter: ServerFilterState,
): ResolvedServerFilters {
    val availableProtocols = extractRemoteProtocolFacets(nodes).mapTo(HashSet()) { it.protocol }
    val availableCountries = extractRemoteCountryFacets(nodes).mapTo(HashSet()) { it.code }
    val activeProtocols = if (filter.selectedProtocols.isEmpty() || availableProtocols.isEmpty()) {
        emptySet()
    } else {
        filter.selectedProtocols.filterTo(HashSet()) { it in availableProtocols }
    }
    val activeCountries = if (filter.selectedCountries.isEmpty() || availableCountries.isEmpty()) {
        emptySet()
    } else {
        filter.selectedCountries.filterTo(HashSet()) { it in availableCountries }
    }
    val count = activeProtocols.size +
        activeCountries.size +
        if (filter.sort != ServerSort.Default) 1 else 0
    return ResolvedServerFilters(
        activeProtocols = activeProtocols,
        activeCountries = activeCountries,
        activeFilterCount = count,
    )
}

internal fun buildRemoteServerGroups(
    nodes: List<RemoteNode>,
    subscriptions: List<RemoteSubscription>,
    filter: ServerFilterState,
    manualGroupTitle: String,
): List<RemoteServerGroup> {
    val trimmedQuery = filter.query.trim()
    val resolved = resolveRemoteServerFilters(nodes, filter)
    val activeProtocols = resolved.activeProtocols
    val activeCountries = resolved.activeCountries
    val hasProtocols = activeProtocols.isNotEmpty()
    val hasCountries = activeCountries.isNotEmpty()
    val hasQuery = trimmedQuery.isNotEmpty()

    fun matches(node: RemoteNode): Boolean {
        if (hasProtocols) {
            val protocolMatches = node.protocol in activeProtocols ||
                node.memberProtocols.any { it in activeProtocols }
            if (!protocolMatches) return false
        }
        if (hasCountries) {
            val code = WorldMap.countryCodeOf(node.name)
            when (filter.countryMode) {
                CountryFilterMode.Include -> if (code == null || code !in activeCountries) return false
                CountryFilterMode.Exclude -> if (code != null && code in activeCountries) return false
            }
        }
        if (hasQuery) {
            return node.name.contains(trimmedQuery, ignoreCase = true) ||
                node.group.contains(trimmedQuery, ignoreCase = true) ||
                node.protocol.label.contains(trimmedQuery, ignoreCase = true)
        }
        return true
    }

    fun sorted(groupNodes: List<RemoteNode>): List<RemoteNode> = when (filter.sort) {
        ServerSort.Default -> groupNodes
        ServerSort.Name -> groupNodes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        ServerSort.Protocol -> groupNodes.sortedBy { it.protocol.label }
        ServerSort.Latency -> groupNodes.sortedBy { node ->
            when {
                node.latencyFailed -> Int.MAX_VALUE
                node.latencyMillis == null -> Int.MAX_VALUE - 1
                else -> node.latencyMillis
            }
        }
    }

    val filtered = nodes.filter(::matches)
    if (filtered.isEmpty()) return emptyList()

    val matchedIds = HashSet<String>()
    val result = mutableListOf<RemoteServerGroup>()

    if (subscriptions.isNotEmpty()) {
        val bySubId = filtered.groupBy { it.subscriptionId }
        for (sub in subscriptions) {
            val subNodes = bySubId[sub.id]
            if (!subNodes.isNullOrEmpty()) {
                subNodes.forEach { matchedIds.add(it.id) }
                result += RemoteServerGroup(
                    id = sub.id,
                    title = sub.remoteTitle ?: sub.name,
                    nodes = sorted(subNodes),
                )
            }
        }
    }

    val remaining = filtered.filterNot { it.id in matchedIds }
    if (remaining.isNotEmpty()) {
        val byName = LinkedHashMap<String, MutableList<RemoteNode>>()
        for (node in remaining) {
            val key = node.group.ifBlank { "" }
            byName.getOrPut(key) { mutableListOf() }.add(node)
        }
        for ((groupName, groupNodes) in byName) {
            val id = if (groupName.isEmpty()) "manual" else "group:$groupName"
            val title = groupName.ifEmpty { manualGroupTitle }
            result += RemoteServerGroup(
                id = id,
                title = title,
                nodes = sorted(groupNodes),
            )
        }
    }

    return result
}

internal fun filteredRemoteNodeIdsForPing(
    nodes: List<RemoteNode>,
    subscriptions: List<RemoteSubscription>,
    filter: ServerFilterState,
    collapsedGroupIds: Set<String> = emptySet(),
): List<String> = buildRemoteServerGroups(
    nodes = nodes,
    subscriptions = subscriptions,
    filter = filter,
    manualGroupTitle = "",
)
    .filterNot { it.id in collapsedGroupIds }
    .flatMap { group -> group.nodes.map { it.id } }

@Composable
internal fun RemoteServersTab(
    state: RemoteConsoleState,
    filter: ServerFilterState,
    collapsedGroupIds: Set<String>,
    onBack: () -> Unit,
    onSelectNode: (String) -> Unit,
    onPingNode: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ServerSort) -> Unit,
    onToggleProtocol: (Protocol) -> Unit,
    onClearProtocols: () -> Unit,
    onToggleCountry: (String) -> Unit,
    onCountryModeChange: (CountryFilterMode) -> Unit,
    onClearCountries: () -> Unit,
    onResetFilters: () -> Unit,
    onToggleGroup: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }

    val bond = state.active
    val online = state.link is RemoteLinkState.Online
    val snapshot = state.snapshot
    val nodes = state.nodes
    val subscriptions = state.subscriptions

    val protocolFacets = remember(nodes) { extractRemoteProtocolFacets(nodes) }
    val countryFacets = remember(nodes) { extractRemoteCountryFacets(nodes) }
    val resolvedFilters = remember(nodes, filter) { resolveRemoteServerFilters(nodes, filter) }
    val activeProtocols = resolvedFilters.activeProtocols
    val activeCountries = resolvedFilters.activeCountries
    val activeFilterCount = resolvedFilters.activeFilterCount

    val manualGroupTitle = stringResource(R.string.servers_group_manual)
    val groups = remember(nodes, subscriptions, filter, manualGroupTitle) {
        buildRemoteServerGroups(
            nodes = nodes,
            subscriptions = subscriptions,
            filter = filter,
            manualGroupTitle = manualGroupTitle,
        )
    }
    val filteredServerCount = remember(groups) { groups.sumOf { it.nodes.size } }
    val pingingNodeIds = snapshot?.pingingNodeIds.orEmpty()

    val sourceCount = subscriptions.size.takeIf { it > 0 }
        ?: groups.count { it.id != "manual" }
    val subtitle = listOfNotNull(
        bond?.displayName,
        stringResource(
            R.string.servers_subtitle,
            pluralServers(nodes.size),
            pluralSources(sourceCount),
        ),
    ).joinToString(" · ")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.servers_title),
                subtitle = subtitle,
                modifier = Modifier.padding(bottom = 12.dp),
                actions = {
                    TonalIconButton(Icons.Rounded.Close, stringResource(R.string.action_back), onBack)
                },
            )
        }

        item(key = "link") {
            LinkBanner(
                link = state.link,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }

        item(key = "search") {
            SearchAndFilterRow(
                query = filter.query,
                onQueryChange = onQueryChange,
                activeFilterCount = activeFilterCount,
                onOpenFilters = { filterSheetOpen = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            )
        }

        if (groups.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    hasNodes = nodes.isNotEmpty(),
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        groups.forEach { group ->
            val collapsed = group.id in collapsedGroupIds
            item(key = "header-${group.id}") {
                GroupHeader(
                    title = group.title,
                    count = group.nodes.size,
                    collapsed = collapsed,
                    onToggle = { onToggleGroup(group.id) },
                    chevronTestTag = "remote-group-${group.id}-chevron",
                    headerTestTag = "remote-group-${group.id}",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                )
            }

            if (!collapsed) {
                items(items = group.nodes, key = { it.id }) { node ->
                    RemoteServerRow(
                        node = node,
                        selected = node.id == snapshot?.selectedNodeId,
                        isMeasuring = node.id in pingingNodeIds,
                        enabled = online,
                        onSelect = { onSelectNode(node.id) },
                        onPing = { onPingNode(node.id) },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(88.dp)) }
    }

    if (filterSheetOpen) {
        ServersFilterBottomSheet(
            totalServersCount = nodes.size,
            filteredServersCount = filteredServerCount,
            protocolFacets = protocolFacets,
            countryFacets = countryFacets,
            selectedProtocols = activeProtocols,
            selectedCountries = activeCountries,
            countryMode = filter.countryMode,
            sort = filter.sort,
            activeFilterCount = activeFilterCount,
            onToggleProtocol = onToggleProtocol,
            onClearProtocols = onClearProtocols,
            onToggleCountry = onToggleCountry,
            onCountryModeChange = onCountryModeChange,
            onClearCountries = onClearCountries,
            onSortChange = onSortChange,
            onResetAll = onResetFilters,
            onDismiss = { filterSheetOpen = false },
        )
    }
}

@Composable
private fun RemoteServerRow(
    node: RemoteNode,
    selected: Boolean,
    isMeasuring: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "remote-server-row-color",
    )

    val millis = node.latencyMillis
    val latencyResult = remember(node.id, millis, node.latencyFailed) {
        when {
            node.latencyFailed -> LatencyResult(node.id, 0, 0L, failed = true)
            millis != null -> LatencyResult(node.id, millis, 0L, failed = false)
            else -> null
        }
    }

    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                BadgeRow(node.protocol, node.badges)
            }

            LatencyChip(
                result = latencyResult,
                isMeasuring = isMeasuring,
                unmeasurableHint = node.protocol.isQuicBased,
            )

            IconButton(
                onClick = onPing,
                enabled = enabled && !isMeasuring,
            ) {
                if (isMeasuring) {
                    ShapeSpinner(MaterialTheme.colorScheme.primary, size = 20.dp)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.NetworkPing,
                        contentDescription = stringResource(R.string.servers_measure_latency),
                    )
                }
            }
        }
    }
}
