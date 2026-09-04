package com.mydrop.vpn.ui.screens.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Spacer

internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

@Composable
fun SplitTunnelScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // Loading and rasterising a few hundred launcher icons is far too slow for the main thread.
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(context.packageManager) }
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader(
            title = stringResource(R.string.split_tunnel_title),
            titleStyle = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 10.dp),
            actions = {
                TonalIconButton(
                    Icons.Rounded.ArrowBack,
                    stringResource(R.string.action_back),
                    onBack,
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SplitTunnelMode.entries.forEach { mode ->
                ToggleButton(
                    checked = settings.splitTunnelMode == mode,
                    onCheckedChange = { onUpdate { it.copy(splitTunnelMode = mode) } },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(mode.labelRes), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Text(
            text = stringResource(
                when (settings.splitTunnelMode) {
                    SplitTunnelMode.Off -> R.string.split_tunnel_off_hint
                    SplitTunnelMode.AllowList -> R.string.split_tunnel_allow_hint
                    SplitTunnelMode.BlockList -> R.string.split_tunnel_block_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text(stringResource(R.string.split_tunnel_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            enabled = settings.splitTunnelMode != SplitTunnelMode.Off,
        )

        val loaded = apps
        if (loaded == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LoadingIndicator()
            }
            return@Column
        }

        val filtered = remember(loaded, query) {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty()) {
                loaded
            } else {
                loaded.filter {
                    it.label.lowercase().contains(normalized) ||
                        it.packageName.lowercase().contains(normalized)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in settings.splitTunnelPackages
                AppRow(
                    app = app,
                    checked = checked,
                    enabled = settings.splitTunnelMode != SplitTunnelMode.Off,
                    onToggle = {
                        onUpdate { current ->
                            current.copy(
                                splitTunnelPackages = if (checked) {
                                    current.splitTunnelPackages - app.packageName
                                } else {
                                    current.splitTunnelPackages + app.packageName
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.packageName)

        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

/**
 * The list, without the icons.
 *
 * Rasterising every launcher icon here produced a few hundred 96×96 bitmaps up front — some
 * eleven megabytes on a well-stocked phone — and held every one of them for as long as the screen
 * was open, including the ones scrolled far out of sight. [AppIcon] loads each one when its row is
 * actually composed. `GET_META_DATA` went with them: nothing here reads metadata.
 */
/** One icon, decoded when its row appears and dropped with it. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val painter by produceState<BitmapPainter?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapPainter(
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(96, 96)
                        .asImageBitmap(),
                )
            }.getOrNull()
        }
    }

    val current = painter
    if (current != null) {
        Image(painter = current, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        // A placeholder of the same size, so a row does not jump when its icon lands.
        Spacer(Modifier.size(36.dp))
    }
}

internal fun loadApps(packageManager: PackageManager): List<InstalledApp> {
    val installed = packageManager.getInstalledApplications(0)
    return installed
        .asSequence()
        // Only apps that can actually originate traffic are worth listing; without the INTERNET
        // permission a per-app rule would have nothing to act on.
        .filter { info ->
            packageManager.checkPermission(
                android.Manifest.permission.INTERNET,
                info.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        }
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = packageManager.getApplicationLabel(info).toString(),
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
        .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        .toList()
}
