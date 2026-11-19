package com.mydrop.vpn.ui.screens.servers

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.ui.ServersUiState
import com.mydrop.vpn.ui.components.BadgeRow
import com.mydrop.vpn.ui.components.LatencyChip
import com.mydrop.vpn.ui.components.QrShareDialog
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.format.pluralServers
import com.mydrop.vpn.ui.format.pluralSources
import java.text.Collator
import java.util.Locale

typealias ServerSort = com.mydrop.vpn.core.model.ServerSort
typealias CountryFilterMode = com.mydrop.vpn.core.model.CountryFilterMode

@Immutable
data class ServerFilterState(
    val query: String = "",
    val sort: ServerSort = ServerSort.Default,
    val selectedProtocols: Set<Protocol> = emptySet(),
    val selectedCountries: Set<String> = emptySet(),
    val countryMode: CountryFilterMode = CountryFilterMode.Include,
)

internal data class ResolvedServerFilters(
    val activeProtocols: Set<Protocol>,
    val activeCountries: Set<String>,
    val activeFilterCount: Int,
)

internal fun resolveServerFilters(
    nodes: List<ProxyNode>,
    filter: ServerFilterState,
): ResolvedServerFilters {
    val availableProtocols = extractProtocolFacets(nodes).mapTo(HashSet()) { it.protocol }
    val availableCountries = extractSubscriptionCountryFacets(nodes).mapTo(HashSet()) { it.code }
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

internal fun filteredNodesForPing(
    nodes: List<ProxyNode>,
    subscriptions: List<Subscription>,
    latencies: Map<String, LatencyResult>,
    filter: ServerFilterState,
    collapsedGroupIds: Set<String> = emptySet(),
): List<ProxyNode> {
    val disabledIds = subscriptions.asSequence()
        .filterNot { it.enabled }
        .mapTo(HashSet()) { it.id }
    val enabledNodes = if (disabledIds.isEmpty()) {
        nodes
    } else {
        nodes.filterNot { it.subscriptionId in disabledIds }
    }
    val resolved = resolveServerFilters(enabledNodes, filter)
    return buildGroups(
        nodes = enabledNodes,
        subscriptions = subscriptions,
        latencies = latencies,
        query = filter.query,
        sort = filter.sort,
        selectedProtocols = resolved.activeProtocols,
        selectedCountries = resolved.activeCountries,
        countryMode = filter.countryMode,
        manualGroupTitle = "",
    )
        .filterNot { it.id in collapsedGroupIds }
        .flatMap { it.nodes }
}

internal data class ProtocolFacet(
    val protocol: Protocol,
    val count: Int,
)

internal data class CountryFacet(
    val code: String,
    val flag: String,
    val count: Int,
)

internal data class ServerGroup(
    val id: String,
    val title: String,
    val nodes: List<ProxyNode>,
)

private enum class FilterSheetPage {
    All,
    Country,
}

private enum class ServersContentType {
    ScreenHeader,
    SearchAndFilter,
    EmptyState,
    GroupHeader,
    ServerRow,
    TailSpacer,
}

@Composable
fun ServersScreen(
    state: ServersUiState,
    onSelect: (String) -> Unit,
    onPing: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetTlsInsecure: (String, Boolean) -> Unit,
    onToggleGroup: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ServerSort) -> Unit,
    onToggleProtocol: (Protocol) -> Unit,
    onClearProtocols: () -> Unit,
    onToggleCountry: (String) -> Unit,
    onCountryModeChange: (CountryFilterMode) -> Unit,
    onClearCountries: () -> Unit,
    onResetFilters: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var sharing by remember { mutableStateOf<ProxyNode?>(null) }
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }

    val filter = state.filter
    val protocolFacets = remember(state.nodes) { extractProtocolFacets(state.nodes) }
    val countryFacets = remember(state.nodes) { extractSubscriptionCountryFacets(state.nodes) }
    val resolvedFilters = remember(state.nodes, filter) {
        resolveServerFilters(state.nodes, filter)
    }
    val activeProtocols = resolvedFilters.activeProtocols
    val activeCountries = resolvedFilters.activeCountries
    val activeFilterCount = resolvedFilters.activeFilterCount

    // Read outside the remember block: it is not a composition, so it cannot resolve resources,
    // and the label is the only thing in there that depends on the language.
    val manualGroupTitle = stringResource(R.string.servers_group_manual)
    val latencyKey = if (filter.sort == ServerSort.Latency) state.latencies else emptyMap()
    val groups = remember(
        state.nodes,
        state.subscriptions,
        latencyKey,
        filter.query,
        filter.sort,
        activeProtocols,
        activeCountries,
        filter.countryMode,
        manualGroupTitle,
    ) {
        buildGroups(
            nodes = state.nodes,
            subscriptions = state.subscriptions,
            latencies = latencyKey,
            query = filter.query,
            sort = filter.sort,
            selectedProtocols = activeProtocols,
            selectedCountries = activeCountries,
            countryMode = filter.countryMode,
            manualGroupTitle = manualGroupTitle,
        )
    }
    val filteredServerCount = remember(groups) { groups.sumOf { it.nodes.size } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "header", contentType = ServersContentType.ScreenHeader) {
            ScreenHeader(
                title = stringResource(R.string.servers_title),
                subtitle = stringResource(
                    R.string.servers_subtitle,
                    pluralServers(state.nodes.size),
                    pluralSources(state.subscriptions.size),
                ),
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        item(key = "search", contentType = ServersContentType.SearchAndFilter) {
            SearchAndFilterRow(
                query = filter.query,
                onQueryChange = onQueryChange,
                activeFilterCount = activeFilterCount,
                onOpenFilters = { filterSheetOpen = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            )
        }

        if (groups.isEmpty()) {
            item(key = "empty", contentType = ServersContentType.EmptyState) {
                EmptyState(
                    hasNodes = state.nodes.isNotEmpty(),
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        groups.forEach { group ->
            // Settings rather than local state: folding a three-hundred-server subscription
            // shut is a decision, and it used to come undone on every trip to another tab.
            val collapsed = group.id in state.collapsedGroupIds

            item(key = "header-${group.id}", contentType = ServersContentType.GroupHeader) {
                GroupHeader(
                    title = group.title,
                    count = group.nodes.size,
                    collapsed = collapsed,
                    onToggle = { onToggleGroup(group.id) },
                    headerTestTag = "group-header-${group.id}",
                    chevronTestTag = "group-header-${group.id}-chevron",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                )
            }

            if (!collapsed) {
                items(
                    items = group.nodes,
                    key = { it.id },
                    contentType = { ServersContentType.ServerRow },
                ) { node ->
                    ServerRow(
                        node = node,
                        selected = node.id == state.selectedNodeId,
                        latency = state.latencies[node.id],
                        isMeasuring = node.id in state.pingingNodeIds,
                        onSelect = { onSelect(node.id) },
                        onPing = { onPing(node.id) },
                        onRemove = { onRemove(node.id) },
                        onSetTlsInsecure = node.tls
                            ?.takeIf { it.enabled && it.reality == null }
                            ?.let { tls -> { onSetTlsInsecure(node.id, !tls.insecure) } },
                        onShare = node.sourceUri?.let { { sharing = node } },
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                            .testTag("server-row-${node.id}"),
                    )
                }
            }
        }

        item(key = "tail", contentType = ServersContentType.TailSpacer) {
            Spacer(Modifier.height(88.dp))
        }
    }

    if (filterSheetOpen) {
        ServersFilterBottomSheet(
            totalServersCount = state.nodes.size,
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

    sharing?.let { node ->
        QrShareDialog(
            title = node.name,
            link = node.sourceUri.orEmpty(),
            onDismiss = { sharing = null },
        )
    }
}

internal fun extractProtocolFacets(nodes: List<ProxyNode>): List<ProtocolFacet> {
    if (nodes.isEmpty()) return emptyList()
    val protocolCount = Protocol.entries.size
    val counts = IntArray(protocolCount)
    val lastSeenNode = IntArray(protocolCount) { -1 }

    nodes.forEachIndexed { nodeIndex, node ->
        val primaryOrdinal = node.protocol.ordinal
        lastSeenNode[primaryOrdinal] = nodeIndex
        counts[primaryOrdinal]++

        val members = node.group?.members
        if (!members.isNullOrEmpty()) {
            for (i in members.indices) {
                val memberOrdinal = members[i].protocol.ordinal
                if (lastSeenNode[memberOrdinal] != nodeIndex) {
                    lastSeenNode[memberOrdinal] = nodeIndex
                    counts[memberOrdinal]++
                }
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

internal fun extractSubscriptionCountryFacets(nodes: List<ProxyNode>): List<CountryFacet> {
    if (nodes.isEmpty()) return emptyList()
    val counts = LinkedHashMap<String, Int>()
    for (node in nodes) {
        if (node.subscriptionId == null) continue
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

internal fun countryGridColumns(availableWidth: Dp, fontScale: Float): Int =
    if (availableWidth >= 420.dp && fontScale <= 1.15f) 4 else 3

internal fun buildGroups(
    nodes: List<ProxyNode>,
    subscriptions: List<Subscription>,
    latencies: Map<String, LatencyResult>,
    query: String,
    sort: ServerSort,
    selectedProtocols: Set<Protocol>,
    selectedCountries: Set<String>,
    countryMode: CountryFilterMode = CountryFilterMode.Include,
    manualGroupTitle: String,
): List<ServerGroup> {
    val trimmedQuery = query.trim()
    val hasProtocols = selectedProtocols.isNotEmpty()
    val hasCountries = selectedCountries.isNotEmpty()
    val hasQuery = trimmedQuery.isNotEmpty()

    fun matches(node: ProxyNode): Boolean {
        if (hasProtocols) {
            val protocolMatches = node.protocol in selectedProtocols ||
                node.group?.members?.any { it.protocol in selectedProtocols } == true
            if (!protocolMatches) return false
        }
        if (hasCountries) {
            val code = WorldMap.countryCodeOf(node.name)
            when (countryMode) {
                CountryFilterMode.Include -> if (code == null || code !in selectedCountries) return false
                CountryFilterMode.Exclude -> if (code != null && code in selectedCountries) return false
            }
        }
        if (hasQuery) {
            return node.name.contains(trimmedQuery, ignoreCase = true) ||
                node.server.contains(trimmedQuery, ignoreCase = true) ||
                node.protocol.label.contains(trimmedQuery, ignoreCase = true)
        }
        return true
    }

    fun sorted(groupNodes: List<ProxyNode>): List<ProxyNode> = when (sort) {
        ServerSort.Default -> groupNodes
        ServerSort.Name -> groupNodes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        ServerSort.Protocol -> groupNodes.sortedBy { it.protocol.label }
        // Unmeasured and dead servers sink to the bottom instead of pretending to be instant.
        ServerSort.Latency -> groupNodes.sortedBy { node ->
            val result = latencies[node.id]
            when {
                result == null -> Int.MAX_VALUE - 1
                result.failed -> Int.MAX_VALUE
                else -> result.millis
            }
        }
    }

    val bySubscriptionId = nodes
        .asSequence()
        .filter(::matches)
        .groupBy { it.subscriptionId }

    val knownSubscriptionIds = subscriptions.mapTo(HashSet(subscriptions.size)) { it.id }

    return buildList {
        for (subscription in subscriptions) {
            val subNodes = bySubscriptionId[subscription.id]
            if (!subNodes.isNullOrEmpty()) {
                add(
                    ServerGroup(
                        id = subscription.id,
                        title = subscription.remoteTitle ?: subscription.name,
                        nodes = sorted(subNodes),
                    ),
                )
            }
        }

        val manualNodes = buildList {
            bySubscriptionId[null]?.let(::addAll)
            for ((subId, list) in bySubscriptionId) {
                if (subId != null && subId !in knownSubscriptionIds) {
                    addAll(list)
                }
            }
        }
        if (manualNodes.isNotEmpty()) {
            add(ServerGroup(id = "manual", title = manualGroupTitle, nodes = sorted(manualNodes)))
        }
    }
}

@Composable
internal fun SearchAndFilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    activeFilterCount: Int,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.servers_search_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_clear),
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
        )

        FilterTriggerButton(
            activeFilterCount = activeFilterCount,
            onClick = onOpenFilters,
        )
    }
}

internal data class FilterTriggerStyle(
    val containerColor: Color,
    val contentColor: Color,
    val badgeContainerColor: Color,
    val badgeContentColor: Color,
    val hasBorder: Boolean,
)

internal fun filterTriggerStyle(
    active: Boolean,
    colorScheme: ColorScheme,
): FilterTriggerStyle = if (active) {
    FilterTriggerStyle(
        containerColor = colorScheme.primary,
        contentColor = colorScheme.onPrimary,
        badgeContainerColor = colorScheme.onPrimary,
        badgeContentColor = colorScheme.primary,
        hasBorder = false,
    )
} else {
    FilterTriggerStyle(
        containerColor = colorScheme.surfaceContainerHigh,
        contentColor = colorScheme.onSurface,
        badgeContainerColor = Color.Transparent,
        badgeContentColor = Color.Transparent,
        hasBorder = false,
    )
}

@Composable
private fun FilterTriggerButton(
    activeFilterCount: Int,
    onClick: () -> Unit,
) {
    val active = activeFilterCount > 0
    val targetStyle = filterTriggerStyle(active, MaterialTheme.colorScheme)
    val shape = RoundedCornerShape(18.dp)
    val containerColor by animateColorAsState(
        targetValue = targetStyle.containerColor,
        label = "filter-trigger-bg",
    )
    val contentColor by animateColorAsState(
        targetValue = targetStyle.contentColor,
        label = "filter-trigger-fg",
    )

    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = null,
        modifier = Modifier
            .fillMaxHeight()
            .heightIn(min = OutlinedTextFieldDefaults.MinHeight)
            .widthIn(min = OutlinedTextFieldDefaults.MinHeight)
            .clip(shape)
            .testTag("servers-filter-button")
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (active) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.servers_filters_title),
                modifier = Modifier.size(22.dp),
            )
            if (active) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(targetStyle.badgeContainerColor)
                        .testTag("servers-filter-button-count"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = activeFilterCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = targetStyle.badgeContentColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ServersFilterBottomSheet(
    totalServersCount: Int,
    filteredServersCount: Int,
    protocolFacets: List<ProtocolFacet>,
    countryFacets: List<CountryFacet>,
    selectedProtocols: Set<Protocol>,
    selectedCountries: Set<String>,
    countryMode: CountryFilterMode,
    sort: ServerSort,
    activeFilterCount: Int,
    onToggleProtocol: (Protocol) -> Unit,
    onClearProtocols: () -> Unit,
    onToggleCountry: (String) -> Unit,
    onCountryModeChange: (CountryFilterMode) -> Unit,
    onClearCountries: () -> Unit,
    onSortChange: (ServerSort) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by rememberSaveable { mutableStateOf(FilterSheetPage.All) }
    var countryQuery by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            when (page) {
                FilterSheetPage.All -> {
                    MainFilterSheetContent(
                        totalServersCount = totalServersCount,
                        filteredServersCount = filteredServersCount,
                        protocolFacets = protocolFacets,
                        countryFacets = countryFacets,
                        selectedProtocols = selectedProtocols,
                        selectedCountries = selectedCountries,
                        countryMode = countryMode,
                        sort = sort,
                        activeFilterCount = activeFilterCount,
                        onToggleProtocol = onToggleProtocol,
                        onClearProtocols = onClearProtocols,
                        onOpenCountries = {
                            countryQuery = ""
                            page = FilterSheetPage.Country
                        },
                        onSortChange = onSortChange,
                        onResetAll = onResetAll,
                        onApply = onDismiss,
                    )
                }

                FilterSheetPage.Country -> {
                    CountryPickerSheetContent(
                        filteredServersCount = filteredServersCount,
                        countryFacets = countryFacets,
                        selectedCountries = selectedCountries,
                        countryMode = countryMode,
                        query = countryQuery,
                        onQueryChange = { countryQuery = it },
                        onToggleCountry = onToggleCountry,
                        onCountryModeChange = onCountryModeChange,
                        onClearCountries = onClearCountries,
                        onBack = { page = FilterSheetPage.All },
                        onApply = onDismiss,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainFilterSheetContent(
    totalServersCount: Int,
    filteredServersCount: Int,
    protocolFacets: List<ProtocolFacet>,
    countryFacets: List<CountryFacet>,
    selectedProtocols: Set<Protocol>,
    selectedCountries: Set<String>,
    countryMode: CountryFilterMode,
    sort: ServerSort,
    activeFilterCount: Int,
    onToggleProtocol: (Protocol) -> Unit,
    onClearProtocols: () -> Unit,
    onOpenCountries: () -> Unit,
    onSortChange: (ServerSort) -> Unit,
    onResetAll: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.servers_filters_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        if (activeFilterCount > 0) {
            TextButton(
                onClick = onResetAll,
                modifier = Modifier.testTag("servers-filter-reset-all"),
            ) {
                Text(stringResource(R.string.servers_filters_reset_all))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (protocolFacets.isNotEmpty()) {
            SheetSectionTitle(title = stringResource(R.string.servers_filter_protocol_label))
            val totalButtons = protocolFacets.size + 1
            if (totalButtons <= 3) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("servers-filter-protocols"),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ProtocolGroupButton(
                        label = stringResource(R.string.servers_filter_all),
                        count = totalServersCount,
                        selected = selectedProtocols.isEmpty(),
                        isFirst = true,
                        isLast = protocolFacets.isEmpty(),
                        onClick = onClearProtocols,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("servers-filter-protocol-all"),
                    )
                    protocolFacets.forEachIndexed { index, facet ->
                        ProtocolGroupButton(
                            label = facet.protocol.label,
                            count = facet.count,
                            selected = facet.protocol in selectedProtocols,
                            isFirst = false,
                            isLast = index == protocolFacets.lastIndex,
                            onClick = { onToggleProtocol(facet.protocol) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("protocol-chip-${facet.protocol.name}"),
                        )
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("servers-filter-protocols"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProtocolGroupButton(
                        label = stringResource(R.string.servers_filter_all),
                        count = totalServersCount,
                        selected = selectedProtocols.isEmpty(),
                        isFirst = true,
                        isLast = false,
                        onClick = onClearProtocols,
                        modifier = Modifier.testTag("servers-filter-protocol-all"),
                    )
                    protocolFacets.forEachIndexed { index, facet ->
                        ProtocolGroupButton(
                            label = facet.protocol.label,
                            count = facet.count,
                            selected = facet.protocol in selectedProtocols,
                            isFirst = false,
                            isLast = index == protocolFacets.lastIndex,
                            onClick = { onToggleProtocol(facet.protocol) },
                            modifier = Modifier.testTag("protocol-chip-${facet.protocol.name}"),
                        )
                    }
                }
            }
        }

        if (countryFacets.isNotEmpty()) {
            val sortedSelectedCodes = remember(selectedCountries) { selectedCountries.sorted() }
            val hasSelectedCountries = sortedSelectedCodes.isNotEmpty()
            val isExcluding = hasSelectedCountries && countryMode == CountryFilterMode.Exclude
            SheetSectionTitle(
                title = stringResource(R.string.servers_filter_countries),
                accentColor = if (isExcluding) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("servers-filter-countries-row")
                    .clickable(onClick = onOpenCountries)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(modifier = Modifier.size(42.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    isExcluding -> MaterialTheme.colorScheme.errorContainer
                                    hasSelectedCountries -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            tint = when {
                                isExcluding -> MaterialTheme.colorScheme.onErrorContainer
                                hasSelectedCountries -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (isExcluding) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Remove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !hasSelectedCountries -> stringResource(R.string.servers_filter_countries_all)
                            isExcluding -> stringResource(
                                R.string.servers_filter_countries_except,
                                sortedSelectedCodes.joinToString(", "),
                            )
                            else -> sortedSelectedCodes.joinToString(", ")
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            !hasSelectedCountries -> stringResource(
                                R.string.servers_filter_countries_available,
                                countryFacets.size,
                            )
                            isExcluding -> stringResource(
                                R.string.servers_filter_countries_excluded,
                                sortedSelectedCodes.size,
                            )
                            else -> stringResource(
                                R.string.servers_filter_countries_chosen,
                                sortedSelectedCodes.size,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isExcluding) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SheetSectionTitle(title = stringResource(R.string.servers_filter_sort_label))
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp)
                .testTag("servers-filter-sort-bar"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ServerSort.entries.forEach { option ->
                val isSelected = option == sort
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    label = "sort-seg-bg",
                )
                val fgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "sort-seg-fg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(bgColor)
                        .testTag("servers-filter-sort-${option.name}")
                        .clickable { onSortChange(option) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(option.shortLabelRes),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = fgColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    SheetApplyFooter(
        filteredServersCount = filteredServersCount,
        onClick = onApply,
    )
}

@Composable
private fun ProtocolGroupButton(
    label: String,
    count: Int,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outer = 23.dp
    val inner = 10.dp
    val startCorner by animateDpAsState(if (selected || isFirst) outer else inner, label = "pg-start")
    val endCorner by animateDpAsState(if (selected || isLast) outer else inner, label = "pg-end")
    val shape = RoundedCornerShape(
        topStart = startCorner,
        bottomStart = startCorner,
        topEnd = endCorner,
        bottomEnd = endCorner,
    )
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "pg-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "pg-fg",
    )

    Surface(
        shape = shape,
        color = bg,
        contentColor = fg,
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.72f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun CountryPickerSheetContent(
    filteredServersCount: Int,
    countryFacets: List<CountryFacet>,
    selectedCountries: Set<String>,
    countryMode: CountryFilterMode,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleCountry: (String) -> Unit,
    onCountryModeChange: (CountryFilterMode) -> Unit,
    onClearCountries: () -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit,
) {
    val config = LocalConfiguration.current
    val displayLocale = remember(config) {
        config.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    val excludeMode = countryMode == CountryFilterMode.Exclude

    val localizedCountries = remember(countryFacets, displayLocale) {
        val collator = Collator.getInstance(displayLocale)
        countryFacets
            .map { facet ->
                facet to countryDisplayName(facet.code, displayLocale)
            }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
    val trimmedQuery = query.trim()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("servers-filter-country-back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Text(
            text = stringResource(R.string.servers_filter_country_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        if (selectedCountries.isNotEmpty()) {
            TextButton(
                onClick = onClearCountries,
                modifier = Modifier.testTag("servers-filter-countries-clear"),
            ) {
                Text(
                    text = stringResource(R.string.servers_filter_reset_count, selectedCountries.size),
                    color = if (excludeMode) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }

    CountryFilterModeBar(
        mode = countryMode,
        onModeChange = onCountryModeChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )

    CompactSheetSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.servers_filter_country_search_placeholder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        if (trimmedQuery.isNotEmpty()) {
            val matched = localizedCountries.filter { (facet, name) ->
                matchesCountrySearch(facet.code, name, trimmedQuery)
            }
            if (matched.isEmpty()) {
                item(key = "country-empty") {
                    Text(
                        text = stringResource(R.string.servers_filter_country_not_found, trimmedQuery),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                    )
                }
            } else {
                items(
                    items = matched,
                    key = { (facet, _) -> "search-${facet.code}" },
                ) { (facet, name) ->
                    CountrySheetRow(
                        facet = facet,
                        displayName = name,
                        selected = facet.code in selectedCountries,
                        excludeMode = excludeMode,
                        onClick = { onToggleCountry(facet.code) },
                    )
                }
            }
        } else {
            val selectedItems = localizedCountries.filter { (facet, _) -> facet.code in selectedCountries }
            if (selectedItems.isNotEmpty()) {
                item(key = "sect-selected") {
                    SheetSectionTitle(
                        title = stringResource(
                            if (excludeMode) {
                                R.string.servers_filter_excluded_section
                            } else {
                                R.string.servers_filter_selected_section
                            },
                        ),
                        accentColor = if (excludeMode) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                items(
                    items = selectedItems,
                    key = { (facet, _) -> "sel-${facet.code}" },
                ) { (facet, name) ->
                    CountrySheetRow(
                        facet = facet,
                        displayName = name,
                        selected = true,
                        excludeMode = excludeMode,
                        onClick = { onToggleCountry(facet.code) },
                    )
                }
            }

            val popularRows = countryFacets.take(9).chunked(3)
            if (popularRows.isNotEmpty()) {
                item(key = "sect-popular") {
                    SheetSectionTitle(title = stringResource(R.string.servers_filter_popular_section))
                }
                items(
                    items = popularRows,
                    key = { row -> "pop-row-${row.first().code}" },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { facet ->
                            PopularCountryTile(
                                facet = facet,
                                selected = facet.code in selectedCountries,
                                excludeMode = excludeMode,
                                onClick = { onToggleCountry(facet.code) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            val remaining = localizedCountries.filter { (facet, _) -> facet.code !in selectedCountries }
            if (remaining.isNotEmpty()) {
                item(key = "sect-all") {
                    SheetSectionTitle(
                        title = stringResource(R.string.servers_filter_all_countries_az),
                        badgeCount = countryFacets.size,
                    )
                }
                items(
                    items = remaining,
                    key = { (facet, _) -> "all-${facet.code}" },
                ) { (facet, name) ->
                    CountrySheetRow(
                        facet = facet,
                        displayName = name,
                        selected = false,
                        excludeMode = excludeMode,
                        onClick = { onToggleCountry(facet.code) },
                    )
                }
            }
        }
    }

    SheetApplyFooter(
        filteredServersCount = filteredServersCount,
        onClick = onApply,
    )
}

@Composable
private fun CountryFilterModeBar(
    mode: CountryFilterMode,
    onModeChange: (CountryFilterMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val includeSelected = mode == CountryFilterMode.Include
    val excludeSelected = mode == CountryFilterMode.Exclude

    val includeBg by animateColorAsState(
        targetValue = if (includeSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "country-mode-inc-bg",
    )
    val includeFg by animateColorAsState(
        targetValue = if (includeSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "country-mode-inc-fg",
    )
    val excludeBg by animateColorAsState(
        targetValue = if (excludeSelected) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "country-mode-exc-bg",
    )
    val excludeFg by animateColorAsState(
        targetValue = if (excludeSelected) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "country-mode-exc-fg",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .testTag("servers-filter-country-mode-bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(includeBg)
                .testTag("servers-filter-country-mode-include")
                .clickable { onModeChange(CountryFilterMode.Include) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            if (includeSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = includeFg,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = stringResource(R.string.servers_filter_country_mode_include),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = includeFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(excludeBg)
                .testTag("servers-filter-country-mode-exclude")
                .clickable { onModeChange(CountryFilterMode.Exclude) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            if (excludeSelected) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = null,
                    tint = excludeFg,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = stringResource(R.string.servers_filter_country_mode_exclude),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = excludeFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactSheetSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("servers-filter-country-search"),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PopularCountryTile(
    facet: CountryFacet,
    selected: Boolean,
    excludeMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner by animateDpAsState(if (selected) 24.dp else 16.dp, label = "pop-tile-radius")
    val shape = RoundedCornerShape(corner)
    val bg by animateColorAsState(
        targetValue = when {
            selected && excludeMode -> MaterialTheme.colorScheme.errorContainer
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "pop-tile-bg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            selected && excludeMode -> MaterialTheme.colorScheme.onErrorContainer
            selected -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "pop-tile-fg",
    )

    Surface(
        shape = shape,
        color = bg,
        contentColor = fg,
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(shape)
            .testTag("country-tile-${facet.code}")
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (facet.flag.isNotEmpty()) {
                Text(
                    text = facet.flag,
                    fontSize = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = facet.code,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            if (selected && excludeMode) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(15.dp),
                )
            } else {
                Text(
                    text = facet.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.72f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun CountrySheetRow(
    facet: CountryFacet,
    displayName: String,
    selected: Boolean,
    excludeMode: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("country-row-${facet.code}")
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (facet.flag.isNotEmpty()) {
            Text(
                text = facet.flag,
                fontSize = 24.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = facet.code,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = facet.count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .then(
                    when {
                        selected && excludeMode -> Modifier.background(MaterialTheme.colorScheme.error)
                        selected -> Modifier.background(MaterialTheme.colorScheme.primary)
                        else -> Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(7.dp),
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = if (excludeMode) Icons.Rounded.Remove else Icons.Rounded.Check,
                    contentDescription = null,
                    tint = if (excludeMode) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(
    title: String,
    badgeCount: Int? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = accentColor,
        )
        if (badgeCount != null) {
            Text(
                text = badgeCount.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SheetApplyFooter(
    filteredServersCount: Int,
    onClick: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("servers-filter-apply"),
        ) {
            Text(
                text = stringResource(
                    R.string.servers_filter_show_results,
                    pluralServers(filteredServersCount),
                ),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

private fun countryDisplayName(code: String, locale: Locale): String =
    runCatching {
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: code

private fun matchesCountrySearch(
    code: String,
    localizedName: String,
    query: String,
): Boolean {
    if (code.contains(query, ignoreCase = true)) return true
    if (localizedName.contains(query, ignoreCase = true)) return true
    val ruName = countryDisplayName(code, LOCALE_RU)
    if (ruName.contains(query, ignoreCase = true)) return true
    val enName = countryDisplayName(code, Locale.ENGLISH)
    return enName.contains(query, ignoreCase = true)
}

private val LOCALE_RU: Locale = Locale.forLanguageTag("ru")

@Composable
internal fun GroupHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    chevronTestTag: String,
    modifier: Modifier = Modifier,
    headerTestTag: String? = null,
) {
    // The whole header toggles, and the arrow is a plain icon rather than an IconButton.
    // An IconButton centres its 24 dp glyph in a container whose size is a theme decision, so the
    // gap between glyph and screen edge was whatever that container happened to be — which is how
    // the arrow kept drifting inward. A bare icon ends exactly where the row ends, level with the
    // filter card chevrons above it, and the row itself is a far bigger target than the button ever was.
    //
    // Title and badge share one weighted slot. Giving the title its own `weight(1f, fill = false)`
    // alongside a weighted spacer split the free space between the two of them, so the spacer could
    // only ever push the arrow halfway — which is where it sat, stranded in the middle of the row
    // with a screen's worth of gap to its right.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (headerTestTag != null) Modifier.testTag(headerTestTag) else Modifier)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            contentDescription = stringResource(
                if (collapsed) R.string.action_expand else R.string.action_collapse,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .testTag(chevronTestTag),
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
                // Two lines, not one: providers put the country last ("🇳🇱 ⚡️ ⭐️ LTE Авто -
                // Нидерланды"), and on a 360 dp phone a single line broke after the dash and
                // dropped the one word that told these servers apart, without even an ellipsis.
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                BadgeRow(node.protocol, node.badges)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = node.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LatencyChip(
                result = latency,
                isMeasuring = isMeasuring,
                unmeasurableHint = node.protocol.isQuicBased,
            )

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.NetworkPing,
                        contentDescription = stringResource(R.string.servers_actions),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_measure_latency)) },
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
                                    stringResource(
                                        if (skipping) {
                                            R.string.servers_verify_certificate
                                        } else {
                                            R.string.servers_skip_certificate
                                        },
                                    ),
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
                            text = { Text(stringResource(R.string.servers_show_qr)) },
                            leadingIcon = { Icon(Icons.Rounded.QrCode2, null) },
                            onClick = {
                                onShare()
                                menuOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_delete)) },
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
internal fun EmptyState(hasNodes: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (hasNodes) R.string.servers_empty_filtered else R.string.servers_empty,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (hasNodes) {
                stringResource(R.string.servers_empty_filtered_hint)
            } else {
                stringResource(R.string.servers_empty_hint)
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
            contentDescription = stringResource(R.string.servers_ping_all),
            modifier = Modifier.rotate(rotation),
        )
    }
}
