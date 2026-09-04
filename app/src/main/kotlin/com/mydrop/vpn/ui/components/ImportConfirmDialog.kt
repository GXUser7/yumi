package com.mydrop.vpn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.parse.DeepLinkPayload
import com.mydrop.vpn.ui.PendingImport

/**
 * The gate between a link another app opened and the server list.
 *
 * It states three things and nothing else: that the link came from outside, what exactly it wants
 * to add, and where it says it came from. That is the information a person needs to answer the
 * question — a preview of every server name would be longer and no more informative, and the
 * names come from the same untrusted place as the rest of the link.
 */
@Composable
fun ImportConfirmDialog(
    pending: PendingImport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = summarise(pending),
                    style = MaterialTheme.typography.bodyLarge,
                )
                insecureCount(pending)?.let { count ->
                    Text(
                        text = stringResource(R.string.import_confirm_insecure, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.import_confirm_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun summarise(pending: PendingImport): String = when (val payload = pending.payload) {
    is DeepLinkPayload.AddSubscription ->
        stringResource(R.string.import_confirm_subscription, pending.source)
    is DeepLinkPayload.AddNodes ->
        stringResource(R.string.import_confirm_servers, payload.nodes.size)
    is DeepLinkPayload.AddDns ->
        stringResource(R.string.import_confirm_dns, payload.profiles.size)
    // Never reached: an unreadable link is reported rather than confirmed.
    is DeepLinkPayload.Unsupported -> pending.source
}

/**
 * How many of the incoming servers ask for certificate checking to be skipped.
 *
 * Worth its own line in red: `allowInsecure=1` sits in a link\'s query string where nobody reads
 * it, and it is the one flag in there that decides whether the connection can be read by whoever
 * is carrying it. A subscription cannot be inspected before it is fetched, so only a direct list
 * of servers can be counted here.
 */
private fun insecureCount(pending: PendingImport): Int? {
    val nodes = (pending.payload as? DeepLinkPayload.AddNodes)?.nodes ?: return null
    val count = nodes.count { it.tls?.let { tls -> tls.enabled && tls.insecure } == true }
    return count.takeIf { it > 0 }
}
