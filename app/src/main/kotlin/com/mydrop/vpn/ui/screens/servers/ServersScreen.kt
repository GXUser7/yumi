package com.mydrop.vpn.ui.screens.servers

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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

enum class ServerSort(@StringRes val labelRes: Int) {
    Default(R.string.servers_sort_default),
    Latency(R.string.servers_sort_latency),
    Name(R.string.servers_sort_name),
    Protocol(R.string.servers_sort_protocol),
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

private enum class ServersContentType {
    ScreenHeader,
    SearchAndSort,
    ProtocolsFilter,
    CountriesFilterHeader,
    CountriesFilterRow,
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
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var sharing by remember { mutableStateOf<ProxyNode?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ServerSort.Default) }
    var selectedProtocols by rememberSaveable { mutableStateOf(emptySet<Protocol>()) }
    var selectedCountries by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var protocolsExpanded by rememberSaveable { mutableStateOf(false) }
    var countriesExpanded by rememberSaveable { mutableStateOf(false) }

    val protocolFacets = remember(state.nodes) { extractProtocolFacets(state.nodes) }
    val countryFacets = remember(state.nodes) { extractSubscriptionCountryFacets(state.nodes) }

    val availableProtocolSet = remember(protocolFacets) {
        protocolFacets.mapTo(HashSet(protocolFacets.size)) { it.protocol }
    }
    val availableCountrySet = remember(countryFacets) {
        countryFacets.mapTo(HashSet(countryFacets.size)) { it.code }
    }

    val activeProtocols = remember(selectedProtocols, availableProtocolSet) {
        if (selectedProtocols.isEmpty() || availableProtocolSet.isEmpty()) {
            emptySet()
        } else {
            selectedProtocols.filterTo(HashSet()) { it in availableProtocolSet }
        }
    }
    val activeCountries = remember(selectedCountries, availableCountrySet) {
        if (selectedCountries.isEmpty() || availableCountrySet.isEmpty()) {
            emptySet()
        } else {
            selectedCountries.filterTo(HashSet()) { it in availableCountrySet }
        }
    }

    // Read outside the remember block: it is not a composition, so it cannot resolve resources,
    // and the label is the only thing in there that depends on the language.
    val manualGroupTitle = stringResource(R.string.servers_group_manual)
    val latencyKey = if (sort == ServerSort.Latency) state.latencies else emptyMap()
    val groups = remember(
        state.nodes,
        state.subscriptions,
        latencyKey,
        query,
        sort,
        activeProtocols,
        activeCountries,
        manualGroupTitle,
    ) {
        buildGroups(
            nodes = state.nodes,
            subscriptions = state.subscriptions,
            latencies = latencyKey,
            query = query,
            sort = sort,
            selectedProtocols = activeProtocols,
            selectedCountries = activeCountries,
            manualGroupTitle = manualGroupTitle,
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val countryGridWidth = (
            maxWidth -
                contentPadding.calculateStartPadding(layoutDirection) -
                contentPadding.calculateEndPadding(layoutDirection)
            ).coerceAtLeast(0.dp)
        val countryColumns = countryGridColumns(countryGridWidth, density.fontScale)
        val countryRows = remember(countryFacets, countryColumns, countriesExpanded) {
            if (countriesExpanded && countryFacets.isNotEmpty()) {
                countryFacets.chunked(countryColumns)
            } else {
                emptyList()
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

            item(key = "search", contentType = ServersContentType.SearchAndSort) {
                SearchAndSortRow(
                    query = query,
                    onQueryChange = { query = it },
                    sort = sort,
                    onSortChange = { sort = it },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                )
            }

            if (protocolFacets.isNotEmpty()) {
                item(key = "filter-protocols", contentType = ServersContentType.ProtocolsFilter) {
                    ProtocolsFilterCard(
                        facets = protocolFacets,
                        selectedProtocols = activeProtocols,
                        expanded = protocolsExpanded,
                        onToggleExpand = { protocolsExpanded = !protocolsExpanded },
                        onToggleProtocol = { protocol ->
                            selectedProtocols = if (protocol in selectedProtocols) {
                                selectedProtocols - protocol
                            } else {
                                selectedProtocols + protocol
                            }
                        },
                        onClearProtocols = { selectedProtocols = emptySet() },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                }
            }

            if (countryFacets.isNotEmpty()) {
                item(
                    key = "filter-countries-header",
                    contentType = ServersContentType.CountriesFilterHeader,
                ) {
                    CountriesFilterHeader(
                        totalCount = countryFacets.size,
                        selectedCount = activeCountries.size,
                        expanded = countriesExpanded,
                        hasRows = countryRows.isNotEmpty(),
                        onToggleExpand = { countriesExpanded = !countriesExpanded },
                        onClearCountries = { selectedCountries = emptySet() },
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (countryRows.isEmpty()) 6.dp else 0.dp,
                        ),
                    )
                }

                if (countryRows.isNotEmpty()) {
                    itemsIndexed(
                        items = countryRows,
                        key = { _, row -> "country-row-${row.first().code}" },
                        contentType = { _, _ -> ServersContentType.CountriesFilterRow },
                    ) { rowIndex, row ->
                        CountryGridRow(
                            rowIndex = rowIndex,
                            row = row,
                            columns = countryColumns,
                            isFirstRow = rowIndex == 0,
                            isLastRow = rowIndex == countryRows.lastIndex,
                            selectedCountries = activeCountries,
                            onToggleCountry = { code ->
                                selectedCountries = if (code in selectedCountries) {
                                    selectedCountries - code
                                } else {
                                    selectedCountries + code
                                }
                            },
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = if (rowIndex == countryRows.lastIndex) 6.dp else 0.dp,
                            ),
                        )
                    }
                }
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
            if (code == null || code !in selectedCountries) return false
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

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Rounded.SwapVert,
                    contentDescription = stringResource(R.string.action_sort),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ServerSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
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
private fun FilterBlockHeader(
    title: String,
    totalCount: Int,
    selectedCount: Int,
    expanded: Boolean,
    headerShape: Shape,
    headerTestTag: String,
    onToggleExpand: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(headerShape)
            .testTag(headerTestTag)
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$totalCount",
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
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse else R.string.action_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("$headerTestTag-chevron"),
            )
        }
        if (selectedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.servers_filter_countries_selected, selectedCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("$headerTestTag-selected"),
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 8.dp)
                        .testTag("$headerTestTag-clear"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.servers_filter_clear),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolsFilterCard(
    facets: List<ProtocolFacet>,
    selectedProtocols: Set<Protocol>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleProtocol: (Protocol) -> Unit,
    onClearProtocols: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag("protocol-filter-block"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FilterBlockHeader(
                title = stringResource(R.string.servers_filter_protocols),
                totalCount = facets.size,
                selectedCount = selectedProtocols.size,
                expanded = expanded,
                headerShape = if (expanded) {
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                } else {
                    RoundedCornerShape(12.dp)
                },
                headerTestTag = "protocol-filter-header",
                onToggleExpand = onToggleExpand,
                onClear = onClearProtocols,
            )
            if (expanded) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    facets.forEach { facet ->
                        FilterChip(
                            selected = facet.protocol in selectedProtocols,
                            onClick = { onToggleProtocol(facet.protocol) },
                            label = {
                                Text(
                                    text = "${facet.protocol.label} · ${facet.count}",
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            modifier = Modifier.testTag("protocol-chip-${facet.protocol.name}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountriesFilterHeader(
    totalCount: Int,
    selectedCount: Int,
    expanded: Boolean,
    hasRows: Boolean,
    onToggleExpand: () -> Unit,
    onClearCountries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = if (expanded && hasRows) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    } else {
        RoundedCornerShape(12.dp)
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag("country-filter-block"),
    ) {
        FilterBlockHeader(
            title = stringResource(R.string.servers_filter_countries),
            totalCount = totalCount,
            selectedCount = selectedCount,
            expanded = expanded,
            headerShape = shape,
            headerTestTag = "country-filter-header",
            onToggleExpand = onToggleExpand,
            onClear = onClearCountries,
        )
    }
}

@Composable
private fun CountryGridRow(
    rowIndex: Int,
    row: List<CountryFacet>,
    columns: Int,
    isFirstRow: Boolean,
    isLastRow: Boolean,
    selectedCountries: Set<String>,
    onToggleCountry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = if (isLastRow) {
            RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        } else {
            RectangleShape
        },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag("country-grid-row-$rowIndex"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (isFirstRow) 0.dp else 3.dp,
                    bottom = if (isLastRow) 12.dp else 3.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(if (columns == 3) 6.dp else 8.dp),
        ) {
            row.forEach { facet ->
                CountryTile(
                    facet = facet,
                    selected = facet.code in selectedCountries,
                    onClick = { onToggleCountry(facet.code) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - row.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CountryTile(
    facet: CountryFacet,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .testTag("country-tile-${facet.code}")
            .clickable(onClick = onClick),
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = density.density,
                fontScale = minOf(density.fontScale, 1.15f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            ) {
                if (facet.flag.isNotEmpty()) {
                    Text(
                        text = facet.flag,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = facet.code,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = facet.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
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
private fun EmptyState(hasNodes: Boolean, modifier: Modifier = Modifier) {
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
