package com.mydrop.vpn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.pairing.PairingInvite
import com.mydrop.vpn.shared.R

@Composable
fun PairingSendDialog(
    invite: PairingInvite,
    subscriptions: List<Subscription>,
    sending: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by rememberSaveable(invite.sessionId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_send_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.pairing_send_target, invite.deviceName.ifBlank { invite.host }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (subscriptions.isEmpty()) {
                    Text(stringResource(R.string.pairing_no_subscriptions))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(subscriptions, key = { it.id }) { subscription ->
                            Card(
                                onClick = { if (!sending) selectedId = subscription.id },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(subscription.remoteTitle ?: subscription.name)
                                        Text(
                                            subscription.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    RadioButton(
                                        selected = selectedId == subscription.id,
                                        onClick = { selectedId = subscription.id },
                                        enabled = !sending,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedId?.let(onSend) },
                enabled = selectedId != null && !sending,
            ) {
                if (sending) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.pairing_send_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
