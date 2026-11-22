package com.mydrop.vpn.ui.screens.rules

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.CustomRules
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.data.ConnectionLog
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CustomRulesScreen(
    settings: AppSettings,
    connections: List<ConnectionLog.Entry>,
    nodes: List<ProxyNode>,
    onAddRule: (CustomRules.Action, String) -> Boolean,
    onRemoveRule: (CustomRules.Action, String) -> Unit,
    onClearConnections: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        ScreenHeader(
            title = stringResource(R.string.custom_rules_title),
            subtitle = stringResource(R.string.custom_rules_subtitle),
            titleStyle = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 10.dp),
            actions = {
                TonalIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
                if (selectedTab == 1) {
                    Spacer(Modifier.width(8.dp))
                    TonalIconButton(
                        icon = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear),
                        onClick = onClearConnections,
                    )
                }
            },
        )

        val rulesLabel = stringResource(R.string.custom_rules_tab_rules).let { base ->
            if (settings.customRules.size > 0) "$base · ${settings.customRules.size}" else base
        }
        val connectionsLabel = stringResource(R.string.custom_rules_tab_connections).let { base ->
            if (connections.isNotEmpty()) "$base · ${connections.size}" else base
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(rulesLabel, connectionsLabel).forEachIndexed { index, label ->
                ToggleButton(
                    checked = selectedTab == index,
                    onCheckedChange = { selectedTab = index },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (selectedTab == 0) {
            RulesTab(
                rules = settings.customRules,
                onAddRule = onAddRule,
                onRemoveRule = onRemoveRule,
            )
        } else {
            ConnectionsTab(
                connections = connections,
                nodes = nodes,
                onAddRule = onAddRule,
            )
        }
    }
}

@Composable
private fun RulesTab(
    rules: CustomRules,
    onAddRule: (CustomRules.Action, String) -> Boolean,
    onRemoveRule: (CustomRules.Action, String) -> Unit,
) {
    var selectedActionIndex by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }

    val actions = CustomRules.Action.entries
    val selectedAction = actions[selectedActionIndex]
    val entries = rules.of(selectedAction)

    fun submit() {
        if (draft.isBlank()) {
            rejected = false
            return
        }
        if (onAddRule(selectedAction, draft)) {
            draft = ""
            rejected = false
        } else {
            rejected = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "buckets") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    val count = rules.of(action).size
                    val base = stringResource(action.labelRes())
                    val label = if (count > 0) "$base · $count" else base
                    ToggleButton(
                        checked = selectedActionIndex == index,
                        onCheckedChange = { selectedActionIndex = index },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item(key = "input") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            rejected = false
                        },
                        label = { Text(stringResource(R.string.custom_rules_input_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (rejected) R.string.custom_rules_input_error else R.string.custom_rules_input_hint,
                                ),
                            )
                        },
                        isError = rejected,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.custom_rules_priority_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(
                            onClick = ::submit,
                            enabled = draft.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.action_add))
                        }
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.custom_rules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 16.dp),
                )
            }
        } else {
            items(entries, key = { "${selectedAction.name}:$it" }) { entry ->
                RuleRow(
                    entry = entry,
                    onRemove = { onRemoveRule(selectedAction, entry) },
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    entry: String,
    onRemove: () -> Unit,
) {
    val (prefix, body) = remember(entry) { splitRuleEntry(entry) }
    val isIp = remember(entry) { CustomRules.isAddress(entry) && prefix == null }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (prefix != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = prefix,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (isIp) FontFamily.Monospace else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionsTab(
    connections: List<ConnectionLog.Entry>,
    nodes: List<ProxyNode>,
    onAddRule: (CustomRules.Action, String) -> Boolean,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }

    val nodeNames = remember(nodes) {
        nodes.flatMap { listOf(it) + it.group?.members.orEmpty() }
            .associate { it.id to it.name }
    }

    val shown = remember(connections, filter) {
        connections.filter { entry ->
            when (filter) {
                1 -> entry.route == ConnectionLog.Route.Proxy
                2 -> entry.route == ConnectionLog.Route.Direct
                3 -> entry.route == ConnectionLog.Route.Block || !entry.accepted
                else -> true
            }
        }
    }

    val filterLabels = listOf(
        stringResource(R.string.custom_rules_connections_filter_all),
        stringResource(R.string.custom_rules_connections_filter_proxy),
        stringResource(R.string.custom_rules_connections_filter_direct),
        stringResource(R.string.custom_rules_connections_filter_block),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "filters") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filterLabels.forEachIndexed { index, label ->
                    ToggleButton(
                        checked = filter == index,
                        onCheckedChange = { filter = index },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.custom_rules_connections_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 16.dp),
                )
            }
        } else {
            itemsIndexed(shown) { _, entry ->
                ConnectionRow(
                    entry = entry,
                    nodeNames = nodeNames,
                    onAddRule = onAddRule,
                    onCopyHost = { host -> copyHostToClipboard(context, host) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    entry: ConnectionLog.Entry,
    nodeNames: Map<String, String>,
    onAddRule: (CustomRules.Action, String) -> Boolean,
    onCopyHost: (String) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    val (routeLabel, dotColor) = when {
        !entry.accepted -> stringResource(R.string.custom_rules_route_rejected) to MaterialTheme.colorScheme.error
        entry.route == ConnectionLog.Route.Proxy ->
            (entry.nodeId?.let(nodeNames::get) ?: stringResource(R.string.custom_rules_connections_filter_proxy)) to
                semantic.connected
        entry.route == ConnectionLog.Route.Direct ->
            stringResource(R.string.custom_rules_route_direct) to MaterialTheme.colorScheme.onSurfaceVariant
        entry.route == ConnectionLog.Route.Block ->
            stringResource(R.string.custom_rules_route_block) to MaterialTheme.colorScheme.error
        entry.route == ConnectionLog.Route.Dns ->
            "DNS" to MaterialTheme.colorScheme.outline
        else -> entry.outbound to MaterialTheme.colorScheme.outline
    }

    val detail = buildString {
        append(entry.network.uppercase(Locale.US))
        append(" · ")
        append(routeLabel)
        if (!entry.accepted && entry.reason.isNotEmpty()) {
            append(" · ")
            append(entry.reason)
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { menuExpanded = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${entry.host}:${entry.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = connectionTimeFormatter.format(Date(entry.atMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.custom_rules_menu_always_proxy)) },
                onClick = {
                    onAddRule(CustomRules.Action.Proxy, entry.host)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.custom_rules_menu_always_direct)) },
                onClick = {
                    onAddRule(CustomRules.Action.Direct, entry.host)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.custom_rules_menu_block)) },
                onClick = {
                    onAddRule(CustomRules.Action.Block, entry.host)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.custom_rules_menu_copy)) },
                onClick = {
                    onCopyHost(entry.host)
                    menuExpanded = false
                },
            )
        }
    }
}

private fun CustomRules.Action.labelRes(): Int = when (this) {
    CustomRules.Action.Proxy -> R.string.custom_rules_action_proxy
    CustomRules.Action.Direct -> R.string.custom_rules_action_direct
    CustomRules.Action.Block -> R.string.custom_rules_action_block
}

private fun splitRuleEntry(entry: String): Pair<String?, String> {
    if (entry.startsWith("domain:")) return null to entry.removePrefix("domain:")
    val colon = entry.indexOf(':')
    if (colon in 1..10) {
        val prefix = entry.substring(0, colon)
        if (prefix in setOf("full", "keyword", "regexp", "geosite", "geoip")) {
            return prefix to entry.substring(colon + 1)
        }
    }
    return null to entry
}

private fun copyHostToClipboard(context: Context, host: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Yumi", host))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.custom_rules_copied), Toast.LENGTH_SHORT).show()
    }
}

private val connectionTimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
