package com.mydrop.vpn.ui.screens.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.data.RemoteConsoleState
import com.mydrop.vpn.data.RemoteLinkState
import com.mydrop.vpn.remote.RemoteSubscription
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.format.formatRelativeTime
import com.mydrop.vpn.ui.format.pluralServers
import com.mydrop.vpn.ui.format.pluralSources
import com.mydrop.vpn.ui.screens.subscriptions.RefreshButton
import com.mydrop.vpn.ui.screens.subscriptions.TrafficQuota

@Composable
internal fun RemoteSubscriptionsTab(
    state: RemoteConsoleState,
    onBack: () -> Unit,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val bond = state.active
    val online = state.link is RemoteLinkState.Online
    val subscriptions = state.subscriptions
    val legacyTv = online && state.snapshot?.settings == null && subscriptions.isEmpty()
    var confirmRemove by remember { mutableStateOf<RemoteSubscription?>(null) }

    val subtitle = listOfNotNull(
        bond?.displayName,
        pluralSources(subscriptions.size),
    ).joinToString(" · ")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.subscriptions_title),
                subtitle = subtitle,
                modifier = Modifier.padding(bottom = 8.dp),
                actions = {
                    TonalIconButton(Icons.Rounded.Close, stringResource(R.string.action_back), onBack)
                },
            )
        }

        item(key = "link") {
            LinkBanner(
                link = state.link,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (legacyTv) {
            item(key = "legacy") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.remote_legacy_tv_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else if (subscriptions.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.subscriptions_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.subscriptions_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(subscriptions, key = { it.id }) { sub ->
            RemoteSubscriptionCard(
                subscription = sub,
                enabled = online,
                onRefresh = { onRefresh(sub.id) },
                onSetEnabled = { onSetEnabled(sub.id, it) },
                onRemove = { confirmRemove = sub },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item(key = "tail") { Spacer(Modifier.height(88.dp)) }
    }

    confirmRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.remote_sub_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.remote_sub_delete_body,
                        target.remoteTitle ?: target.name,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove(target.id)
                        confirmRemove = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RemoteSubscriptionCard(
    subscription: RemoteSubscription,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = subscription.remoteTitle ?: subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subscription.urlHost.isNotEmpty()) {
                        Text(
                            text = subscription.urlHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                RefreshButton(
                    isRefreshing = subscription.refreshing,
                    onClick = { if (enabled) onRefresh() },
                )

                Switch(
                    checked = subscription.enabled,
                    enabled = enabled,
                    onCheckedChange = onSetEnabled,
                )
            }

            Spacer(Modifier.height(12.dp))

            TrafficQuota(subscription.userInfo)

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.subscriptions_updated,
                        pluralServers(subscription.nodeCount),
                        formatRelativeTime(subscription.lastUpdatedEpochMillis),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.subscriptions_delete),
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            AnimatedVisibility(visible = subscription.lastError != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = subscription.lastError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoteAddSubscriptionSheet(
    phoneSubscriptions: List<Subscription>,
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String?) -> Unit,
    onInvalidUrl: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.add_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            if (phoneSubscriptions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.remote_sub_send_from_phone),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    phoneSubscriptions.forEach { sub ->
                        Surface(
                            onClick = {
                                onAdd(sub.url, sub.remoteTitle ?: sub.name)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = sub.remoteTitle ?: sub.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = pluralServers(sub.nodeIds.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = stringResource(R.string.remote_sub_add_manual),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_sheet_link_label)) },
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_sheet_name_label)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { url = it.trim() } },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_paste))
                }

                Button(
                    onClick = {
                        val trimmed = url.trim()
                        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
                            onInvalidUrl()
                            return@Button
                        }
                        onAdd(trimmed, name.trim().takeIf { it.isNotEmpty() })
                        onDismiss()
                    },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_add))
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
