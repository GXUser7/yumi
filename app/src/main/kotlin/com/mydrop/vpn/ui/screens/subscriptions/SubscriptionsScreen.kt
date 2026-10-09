package com.mydrop.vpn.ui.screens.subscriptions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.format.daysUntil
import com.mydrop.vpn.core.model.AddKind
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.ui.MainUiState
import com.mydrop.vpn.ui.components.QrShareDialog
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.format.formatBytes
import com.mydrop.vpn.ui.format.formatRelativeTime
import com.mydrop.vpn.ui.format.pluralServers
import com.mydrop.vpn.ui.format.pluralSources

@Composable
fun SubscriptionsScreen(
    state: MainUiState,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var sharing by remember { mutableStateOf<Subscription?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.subscriptions_title),
                subtitle = pluralSources(state.subscriptions.size),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (state.subscriptions.isEmpty()) {
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

        items(state.subscriptions, key = { it.id }) { subscription ->
            SubscriptionCard(
                subscription = subscription,
                nodeCount = state.nodes.count { it.subscriptionId == subscription.id },
                isRefreshing = subscription.id in state.refreshingSubscriptionIds,
                onRefresh = { onRefresh(subscription.id) },
                onRemove = { onRemove(subscription.id) },
                onSetEnabled = { onSetEnabled(subscription.id, it) },
                onShare = { sharing = subscription },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item(key = "tail") { Spacer(Modifier.height(88.dp)) }
    }

    sharing?.let { subscription ->
        QrShareDialog(
            title = subscription.name,
            link = subscription.url,
            onDismiss = { sharing = null },
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    nodeCount: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onShare: () -> Unit,
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
                    )
                    Text(
                        text = subscription.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }

                RefreshButton(isRefreshing = isRefreshing, onClick = onRefresh)

                Switch(checked = subscription.enabled, onCheckedChange = onSetEnabled)
            }

            Spacer(Modifier.height(12.dp))

            TrafficQuota(subscription)

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.subscriptions_updated,
                        pluralServers(nodeCount),
                        formatRelativeTime(subscription.lastUpdatedEpochMillis),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = stringResource(R.string.subscriptions_show_qr),
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.subscriptions_delete),
                        tint = MaterialTheme.colorScheme.error,
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

/**
 * Quota bar. The wavy indicator is not decoration here: the wave amplitude drops to flat as the
 * plan is consumed, so a nearly-exhausted subscription looks visibly "dead" at a glance.
 */
@Composable
private fun TrafficQuota(subscription: Subscription) {
    val info = subscription.userInfo
    val fraction = info?.usedFraction

    if (info == null || fraction == null) {
        Text(
            text = stringResource(R.string.subscriptions_no_quota),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(700),
        label = "quota",
    )

    Column {
        LinearWavyProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            amplitude = { progress -> (1f - progress).coerceIn(0f, 1f) },
        )

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = run {
                    val used = formatBytes(info.usedBytes ?: 0).toString()
                    val total = info.totalBytes?.let { formatBytes(it).toString() }
                    if (total == null) {
                        used
                    } else {
                        stringResource(R.string.subscriptions_used_of, used, total)
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )

            val daysLeft = daysUntil(info.expiresAtEpochSeconds)
            if (daysLeft != null) {
                Text(
                    text = if (daysLeft > 0) {
                        stringResource(R.string.subscriptions_days_left, daysLeft)
                    } else {
                        stringResource(R.string.subscriptions_expired)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (daysLeft > 3) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun RefreshButton(isRefreshing: Boolean, onClick: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "refresh")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = { it }), RepeatMode.Restart),
        label = "spin",
    )

    IconButton(onClick = onClick, enabled = !isRefreshing) {
        Icon(
            imageVector = Icons.Rounded.Autorenew,
            contentDescription = stringResource(R.string.action_refresh),
            modifier = Modifier.rotate(if (isRefreshing) spin else 0f),
        )
    }
}

/** Sheet for adding a subscription by URL, or importing anything pasted from the clipboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionSheet(
    onDismiss: () -> Unit,
    onAdd: (text: String, name: String?, kind: AddKind) -> Unit,
    onScan: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AddKind.Auto) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.add_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = stringResource(R.string.add_sheet_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The same https link can be a subscription or a resolver, and only its owner knows
            // which. Guessing from the path got it right often enough to be trusted and wrong
            // often enough to be maddening, so the guess is offered as a default and the answer
            // stays with the user.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AddKind.entries.forEach { option ->
                    ToggleButton(
                        checked = kind == option,
                        onCheckedChange = { kind = option },
                    ) {
                        Text(stringResource(option.labelRes), style = MaterialTheme.typography.labelMedium)
                    }
                }
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
                    onClick = {
                        clipboard.getText()?.text?.let { url = it.trim() }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_paste))
                }

                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_scan))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }

                Button(
                    onClick = {
                        val trimmed = url.trim()
                        if (trimmed.isEmpty()) return@Button
                        onAdd(trimmed, name.takeIf(String::isNotBlank), kind)
                        onDismiss()
                    },
                    enabled = url.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_add))
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
