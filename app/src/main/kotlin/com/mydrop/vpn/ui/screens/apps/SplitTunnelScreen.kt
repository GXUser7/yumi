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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: BitmapPainter?,
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
            title = "Раздельное\nтуннелирование",
            titleStyle = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 10.dp),
            actions = { TonalIconButton(Icons.Rounded.ArrowBack, "Назад", onBack) },
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
                    Text(mode.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Text(
            text = when (settings.splitTunnelMode) {
                SplitTunnelMode.Off -> "Через туннель идут все приложения"
                SplitTunnelMode.AllowList -> "Через туннель пойдут только отмеченные приложения"
                SplitTunnelMode.BlockList -> "Отмеченные приложения пойдут мимо туннеля"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Поиск приложения") },
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
        if (app.icon != null) {
            Image(
                painter = app.icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }

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

internal fun loadApps(packageManager: PackageManager): List<InstalledApp> {
    val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
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
                icon = runCatching {
                    BitmapPainter(
                        packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap(),
                    )
                }.getOrNull(),
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
        .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        .toList()
}
