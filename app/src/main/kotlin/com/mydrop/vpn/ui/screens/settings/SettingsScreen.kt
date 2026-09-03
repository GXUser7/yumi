package com.mydrop.vpn.ui.screens.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.DnsProfile
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.core.model.UpdateState
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.ShapeSpinner
import com.mydrop.vpn.vpn.TunnelTileService

/**
 * A text setting that only reaches storage once it is valid.
 *
 * Writing every keystroke straight into persisted settings is how both DNS fields ended up empty:
 * clearing one to retype it saved a blank address, the generated config carried `"server": ""`,
 * and sing-box refused to start — which looked like the tunnel dropping the instant it connected.
 * The text being edited lives here instead, and only a value that passes [isValid] is committed,
 * so the last good setting survives an unfinished edit.
 */
@Composable
private fun ValidatedField(
    initial: String,
    label: String,
    hint: String,
    error: String,
    isValid: (String) -> Boolean,
    onCommit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = isValid(text)

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (isValid(it)) onCommit(it)
        },
        label = { Text(label) },
        supportingText = { Text(if (valid) hint else error) },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
}

/** Offered cadences. Anything under half an hour re-reads a list that changes daily. */
private val UPDATE_INTERVALS = listOf(
    30 to R.string.settings_interval_30m,
    60 to R.string.settings_interval_1h,
    180 to R.string.settings_interval_3h,
    360 to R.string.settings_interval_6h,
    720 to R.string.settings_interval_12h,
    1440 to R.string.settings_interval_1d,
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    splitTunnelAppCount: Int,
    dnsProfiles: List<DnsProfile>,
    selectedDnsId: String?,
    onSelectDns: (String?) -> Unit,
    onRemoveDns: (String) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onOpenFailover: () -> Unit,
    onOpenMobileNodes: () -> Unit,
    updates: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: (Context) -> Unit,
    onDismissUpdate: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        item("appearance") {
            SettingsSection(
                title = stringResource(R.string.settings_appearance),
                icon = Icons.Rounded.Palette,
            ) {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        ToggleButton(
                            checked = settings.themeMode == mode,
                            onCheckedChange = { onUpdate { it.copy(themeMode = mode) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(mode.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { language ->
                        ToggleButton(
                            checked = settings.language == language,
                            onCheckedChange = { onUpdate { it.copy(language = language) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            // The endonyms stay in their own language in every locale: somebody
                            // looking for their language recognises "Русский", not "Russian".
                            Text(
                                stringResource(language.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_visualizer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Visualizer.entries.forEach { visualizer ->
                        ToggleButton(
                            checked = settings.visualizer == visualizer,
                            onCheckedChange = { onUpdate { it.copy(visualizer = visualizer) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(visualizer.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                    checked = settings.dynamicColor,
                    onCheckedChange = { onUpdate { s -> s.copy(dynamicColor = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_amoled),
                    subtitle = stringResource(R.string.settings_amoled_subtitle),
                    checked = settings.amoled,
                    onCheckedChange = { onUpdate { s -> s.copy(amoled = it) } },
                )
            }
        }

        item("routing") {
            SettingsSection(
                title = stringResource(R.string.settings_routing),
                icon = Icons.Rounded.Router,
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_bypass_lan),
                    subtitle = stringResource(R.string.settings_bypass_lan_subtitle),
                    checked = settings.bypassLan,
                    onCheckedChange = { onUpdate { s -> s.copy(bypassLan = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_block_ads),
                    subtitle = stringResource(R.string.settings_block_ads_subtitle),
                    checked = settings.blockAds,
                    onCheckedChange = { onUpdate { s -> s.copy(blockAds = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_block_quic),
                    subtitle = stringResource(R.string.settings_block_quic_subtitle),
                    checked = settings.blockQuic,
                    onCheckedChange = { onUpdate { s -> s.copy(blockQuic = it) } },
                )

                NavigationRow(
                    title = stringResource(R.string.settings_split_tunnel),
                    subtitle = when (settings.splitTunnelMode) {
                        SplitTunnelMode.Off -> stringResource(R.string.settings_split_tunnel_off)
                        SplitTunnelMode.AllowList ->
                            stringResource(R.string.settings_split_tunnel_allow, splitTunnelAppCount)
                        SplitTunnelMode.BlockList ->
                            stringResource(R.string.settings_split_tunnel_block, splitTunnelAppCount)
                    },
                    icon = Icons.Rounded.Apps,
                    onClick = onOpenSplitTunnel,
                )
            }
        }

        item("tunnel") {
            SettingsSection(
                title = stringResource(R.string.settings_tunnel),
                icon = Icons.Rounded.Shield,
            ) {
                SwitchRow(
                    title = "IPv6",
                    subtitle = stringResource(R.string.settings_ipv6_subtitle),
                    checked = settings.enableIpv6,
                    onCheckedChange = { onUpdate { s -> s.copy(enableIpv6 = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_hijack_dns),
                    subtitle = stringResource(R.string.settings_hijack_dns_subtitle),
                    checked = settings.hijackDns,
                    onCheckedChange = { onUpdate { s -> s.copy(hijackDns = it) } },
                )

                Spacer(Modifier.height(8.dp))

                ValidatedField(
                    initial = settings.mtu.toString(),
                    label = "MTU",
                    hint = stringResource(R.string.settings_mtu_hint),
                    // Anything below the IPv6 minimum silently breaks large packets.
                    error = stringResource(R.string.settings_mtu_error),
                    isValid = { it.toIntOrNull() in 1280..9000 },
                    onCommit = { raw -> onUpdate { s -> s.copy(mtu = raw.toInt()) } },
                )
            }
        }

        item("dns") {
            SettingsSection(
                title = stringResource(R.string.settings_dns),
                icon = Icons.Rounded.Dns,
            ) {
                ValidatedField(
                    initial = settings.remoteDns,
                    label = stringResource(R.string.settings_remote_dns),
                    hint = stringResource(R.string.settings_remote_dns_hint),
                    error = stringResource(R.string.settings_dns_empty_error),
                    isValid = { it.isNotBlank() },
                    onCommit = { value -> onUpdate { s -> s.copy(remoteDns = value) } },
                )
                Spacer(Modifier.height(8.dp))
                ValidatedField(
                    initial = settings.directDns,
                    label = stringResource(R.string.settings_direct_dns),
                    hint = stringResource(R.string.settings_direct_dns_hint),
                    error = stringResource(R.string.settings_dns_empty_error),
                    isValid = { it.isNotBlank() },
                    onCommit = { value -> onUpdate { s -> s.copy(directDns = value) } },
                )

                Spacer(Modifier.height(8.dp))

                SwitchRow(
                    title = stringResource(R.string.settings_dns_fallback),
                    subtitle = stringResource(R.string.settings_dns_fallback_subtitle),
                    checked = settings.dnsFallback,
                    onCheckedChange = { onUpdate { s -> s.copy(dnsFallback = it) } },
                )

                if (dnsProfiles.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_dns_resolvers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // "From settings" is an entry of its own rather than a switch: with a resolver
                    // selected, the fields above are not what the tunnel uses, and a list you
                    // cannot step out of would leave no way to say that.
                    DnsRow(
                        title = stringResource(R.string.settings_dns_from_settings),
                        subtitle = settings.remoteDns,
                        badge = null,
                        selected = selectedDnsId == null,
                        onSelect = { onSelectDns(null) },
                        onRemove = null,
                    )
                    dnsProfiles.forEach { profile ->
                        DnsRow(
                            title = profile.name,
                            subtitle = profile.url,
                            badge = profile.kind,
                            selected = profile.id == selectedDnsId,
                            onSelect = { onSelectDns(profile.id) },
                            onRemove = { onRemoveDns(profile.id) },
                        )
                    }
                }
            }
        }

        item("ping") {
            SettingsSection(
                title = stringResource(R.string.settings_latency),
                icon = Icons.Rounded.NetworkPing,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PingMode.entries.forEach { mode ->
                        ToggleButton(
                            checked = settings.pingMode == mode,
                            onCheckedChange = { onUpdate { it.copy(pingMode = mode) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(mode.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(settings.pingMode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_quic_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("behaviour") {
            SettingsSection(
                title = stringResource(R.string.settings_behaviour),
                icon = Icons.Rounded.Bolt,
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_boot),
                    subtitle = stringResource(R.string.settings_boot_subtitle),
                    checked = settings.autoConnectOnBoot,
                    onCheckedChange = { onUpdate { s -> s.copy(autoConnectOnBoot = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_fastest),
                    subtitle = stringResource(R.string.settings_fastest_subtitle),
                    checked = settings.autoSelectFastest,
                    onCheckedChange = { onUpdate { s -> s.copy(autoSelectFastest = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_update),
                    subtitle = stringResource(R.string.settings_auto_update_subtitle),
                    checked = settings.subscriptionAutoUpdate,
                    onCheckedChange = { onUpdate { s -> s.copy(subscriptionAutoUpdate = it) } },
                )

                AnimatedVisibility(visible = settings.subscriptionAutoUpdate) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_interval),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            UPDATE_INTERVALS.forEach { (minutes, labelRes) ->
                                ToggleButton(
                                    checked = settings.subscriptionUpdateMinutes == minutes,
                                    onCheckedChange = {
                                        onUpdate { s -> s.copy(subscriptionUpdateMinutes = minutes) }
                                    },
                                ) {
                                    Text(
                                        stringResource(labelRes),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_interval_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }


        /*
         * Своя секция, а не три строки в общем списке.
         *
         * Раньше «Уходить с упавшего сервера», «Возвращаться на мой сервер» и «Серверы для подмены»
         * лежали в «Поведении» вперемешку с автозапуском и обновлением подписок — одинаковыми
         * строками, ничем не показывая, что две из них подчинены третьей и что все три вообще про
         * одно и то же. Владелец так и сказал: непонятно, относятся они к замене сервера или нет.
         *
         * Поэтому: отдельная карточка со своим заголовком, а подчинённые настройки — во вложенной
         * поверхности на тон выше. Вложенность здесь несёт смысл, а не украшает: она ровно и
         * означает «это работает, только пока включён переключатель сверху», и исчезает вместе с
         * ним.
         */
        item("failover") {
            SettingsSection(
                title = stringResource(R.string.settings_failover_section),
                icon = Icons.Rounded.SwapHoriz,
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_failover),
                    subtitle = stringResource(R.string.settings_failover_subtitle),
                    checked = settings.autoFailover,
                    onCheckedChange = { onUpdate { s -> s.copy(autoFailover = it) } },
                )
                AnimatedVisibility(visible = settings.autoFailover) {
                    DependentGroup {
                        NavigationRow(
                            title = stringResource(R.string.settings_failover_servers),
                            subtitle = if (settings.failoverNodeIds.isEmpty()) {
                                stringResource(R.string.settings_failover_auto)
                            } else {
                                stringResource(
                                    R.string.settings_failover_manual,
                                    settings.failoverNodeIds.size,
                                )
                            },
                            icon = Icons.Rounded.Dns,
                            onClick = onOpenFailover,
                        )
                        GroupDivider()
                        NavigationRow(
                            title = stringResource(R.string.settings_mobile_nodes),
                            subtitle = if (settings.mobileNodeIds.isEmpty()) {
                                stringResource(R.string.settings_mobile_nodes_off)
                            } else {
                                stringResource(
                                    R.string.settings_mobile_nodes_chosen,
                                    settings.mobileNodeIds.size,
                                )
                            },
                            icon = Icons.Rounded.SignalCellularAlt,
                            onClick = onOpenMobileNodes,
                        )
                        // Only once the list means something: a switch about coming back from a
                        // network the tunnel never leaves is a switch about nothing.
                        AnimatedVisibility(visible = settings.mobileNodeIds.isNotEmpty()) {
                            Column {
                                SwitchRow(
                                    title = stringResource(R.string.settings_prefer_ordinary_cellular),
                                    subtitle = stringResource(
                                        R.string.settings_prefer_ordinary_cellular_description,
                                    ),
                                    checked = settings.preferOrdinaryOnCellular,
                                    onCheckedChange = {
                                        onUpdate { s -> s.copy(preferOrdinaryOnCellular = it) }
                                    },
                                )
                                SwitchRow(
                                    title = stringResource(R.string.settings_restore_wifi),
                                    subtitle = stringResource(R.string.settings_restore_wifi_subtitle),
                                    checked = settings.restoreWifiNodeOnWifi,
                                    onCheckedChange = {
                                        onUpdate { s -> s.copy(restoreWifiNodeOnWifi = it) }
                                    },
                                )
                            }
                        }
                        GroupDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_return_home),
                            subtitle = stringResource(R.string.settings_return_home_subtitle),
                            checked = settings.returnHome,
                            onCheckedChange = { onUpdate { s -> s.copy(returnHome = it) } },
                        )
                    }
                }
            }
        }

        item("diagnostics") {
            SettingsSection(
                title = stringResource(R.string.settings_diagnostics),
                icon = Icons.Rounded.Article,
            ) {
                NavigationRow(
                    title = stringResource(R.string.settings_logs),
                    subtitle = stringResource(R.string.settings_logs_subtitle),
                    icon = Icons.Rounded.Article,
                    onClick = onOpenLogs,
                )
                QuickTileRow()
            }
        }

        /*
         * Four switches rather than one, because the events differ in how often they arrive and in
         * what they cost to miss. A replaced server can happen several times on a bad evening; a
         * hand-picked list emptying itself happens once and quietly disarms something the user set
         * up. Somebody who has silenced the first still wants the second.
         */
        item("alerts") {
            SettingsSection(
                title = stringResource(R.string.settings_alerts_section),
                icon = Icons.Rounded.NotificationsActive,
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_alert_server),
                    subtitle = stringResource(R.string.settings_alert_server_subtitle),
                    checked = settings.alertServer,
                    onCheckedChange = { onUpdate { s -> s.copy(alertServer = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_alert_dns),
                    subtitle = stringResource(R.string.settings_alert_dns_subtitle),
                    checked = settings.alertDns,
                    onCheckedChange = { onUpdate { s -> s.copy(alertDns = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_alert_lists),
                    subtitle = stringResource(R.string.settings_alert_lists_subtitle),
                    checked = settings.alertLists,
                    onCheckedChange = { onUpdate { s -> s.copy(alertLists = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_alert_update),
                    subtitle = stringResource(R.string.settings_alert_update_subtitle),
                    checked = settings.alertUpdate,
                    onCheckedChange = { onUpdate { s -> s.copy(alertUpdate = it) } },
                )
            }
        }

        item("updates") {
            SettingsSection(
                title = stringResource(R.string.settings_updates),
                icon = Icons.Rounded.SystemUpdate,
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_update_auto),
                    subtitle = stringResource(R.string.settings_update_auto_subtitle),
                    checked = settings.updateAutoCheck,
                    onCheckedChange = { onUpdate { s -> s.copy(updateAutoCheck = it) } },
                )
                Spacer(Modifier.height(8.dp))
                UpdateRow(
                    state = updates,
                    onCheck = onCheckUpdate,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                    onDismiss = onDismissUpdate,
                )
            }
        }

        item("version") {
            val context = LocalContext.current
            // Read from the package rather than written here: a hand-maintained string on this
            // line claimed the core was "coming in the next stage" for as long as the core had
            // been carrying traffic.
            val version = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
                    // The build type appends its own suffix, and "0.3.5-debug" on screen is a
                    // detail of how the APK was made, not something the reader has any use for.
                    ?.substringBefore('-')
                    .orEmpty()
            }

            Text(
                text = if (version.isEmpty()) "Yumi" else "Yumi $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        item("tail") { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Настройки, подчинённые переключателю над ними.
 *
 * Тон выше, чем у карточки-секции, и заметный скруглённый край: на плоском списке невозможно
 * увидеть, что три строки — это одна мысль, а не три соседние. Отступ сверху отделяет группу от
 * своего переключателя настолько, чтобы читалось «внутри», а не «следом».
 */
@Composable
private fun DependentGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        content = content,
    )
}

/** Волосяная линия внутри группы: строки разные, но принадлежат одному. */
@Composable
private fun GroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavigationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailingIcon: ImageVector = Icons.Rounded.ChevronRight,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Asks the system to put the tunnel tile in the shade.
 *
 * The tile is declared in the manifest and Pixel offers it in the shade's own editor, but some
 * shells — HyperOS among them — do not list third-party tiles there dependably, and a tile the
 * user cannot find is a tile that does not exist. Android 13 added a request that raises the
 * system's own "add this tile?" dialog, which does not depend on what the shell's editor chooses
 * to show. Below that version the row explains where to look instead of pretending to act.
 */
/**
 * One button that always does the next thing: check, then download, then install.
 *
 * Deliberately the connect button from the tunnel screen — same pill, same primary colour, same
 * `titleLarge` label that changes with the state while the geometry stays put. That control
 * already teaches the one idea this needs: a single large thing whose wording tells you where you
 * are. Two of them in one app should not look like two different ideas.
 *
 * Shorter than the original 116 dp, because this one lives inside a settings card rather than
 * being the whole point of its screen.
 *
 * Nothing here installs anything. The last press opens Android’s own installer, which asks again.
 */
@Composable
private fun UpdateRow(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (Context) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading

    Column(Modifier.fillMaxWidth()) {
        // Whatever the button cannot say in three words goes above it: the version, the notes,
        // the reason it failed.
        when (state) {
            is UpdateState.Available -> {
                Text(
                    text = stringResource(R.string.settings_update_available, state.release.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.release.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            is UpdateState.Downloading -> {
                // Indeterminate when the size is unknown: a bar sitting at zero because there is
                // nothing to divide by reads as a download that has stalled.
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${megabytes(state.downloaded)} / ${megabytes(state.total)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
            }

            is UpdateState.Ready -> {
                Text(
                    text = stringResource(R.string.settings_update_ready, state.release.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Nothing above the button for these two. Both are answers rather than situations —
            // there is no version to read about and nothing to do next — and both used to push the
            // button down at the exact moment somebody was looking at it. They are announced in
            // passing instead; see MainViewModel.
            is UpdateState.Failed, is UpdateState.UpToDate -> Unit

            UpdateState.Idle, UpdateState.Checking -> Unit
        }

        Button(
            onClick = {
                when (state) {
                    is UpdateState.Available -> onDownload()
                    is UpdateState.Ready -> onInstall(context)
                    else -> onCheck()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(UpdateButtonHeight),
            // Half the height, so it is a pill rather than a rounded rectangle — the same
            // relationship the tunnel control keeps when it is offering to connect.
            shape = RoundedCornerShape(UpdateButtonHeight / 2),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                // A disabled pill that fades to grey reads as broken rather than busy, and busy is
                // the only reason this is ever disabled.
                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (busy) {
                ShapeSpinner(color = MaterialTheme.colorScheme.primary, size = 24.dp)
            } else {
                Icon(
                    imageVector = when (state) {
                        is UpdateState.Available -> Icons.Rounded.Download
                        is UpdateState.Ready -> Icons.Rounded.SystemUpdate
                        is UpdateState.Failed -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Refresh
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = when (state) {
                    is UpdateState.Available -> if (state.release.sizeBytes > 0) {
                        stringResource(
                            R.string.settings_update_download_sized,
                            megabytes(state.release.sizeBytes),
                        )
                    } else {
                        stringResource(R.string.settings_update_download)
                    }

                    is UpdateState.Checking -> stringResource(R.string.settings_update_checking)
                    is UpdateState.Downloading ->
                        stringResource(R.string.settings_update_downloading, state.release.version)

                    is UpdateState.Ready -> stringResource(R.string.settings_update_install)
                    is UpdateState.Failed -> stringResource(R.string.settings_update_retry)
                    else -> stringResource(R.string.settings_update_check)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        // Only where there is something to put off. "Later" under a check button would be an
        // instruction to do nothing.
        if (state is UpdateState.Available) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_update_later))
            }
        }
    }
}

/** Two-thirds of the tunnel screen’s control: the same shape, sized for a settings card. */
private val UpdateButtonHeight = 76.dp

private fun megabytes(bytes: Long): String =
    ((bytes * 10 / (1024 * 1024)) / 10.0).toString()

/**
 * One resolver in the list: tap to use it, cross to forget it.
 *
 * A radio rather than a switch, because exactly one resolver is in use at a time and the entry
 * that says "the one from settings" has to be reachable the same way as the others.
 */
@Composable
private fun DnsRow(
    title: String,
    subtitle: String,
    badge: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        badge?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        onRemove?.let {
            IconButton(onClick = it) {
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
private fun QuickTileRow() {
    val context = LocalContext.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    NavigationRow(
        title = stringResource(R.string.settings_tile),
        subtitle = if (supported) {
            stringResource(R.string.settings_tile_subtitle)
        } else {
            stringResource(R.string.settings_tile_manual)
        },
        icon = Icons.Rounded.Widgets,
        trailingIcon = if (supported) Icons.Rounded.AddCircleOutline else Icons.Rounded.ChevronRight,
        onClick = { if (supported) requestQuickTile(context) },
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestQuickTile(context: Context) {
    runCatching {
        context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
            ComponentName(context, TunnelTileService::class.java),
            context.getString(R.string.app_name),
            // Fully qualified: `Icon` in this file is the Compose composable.
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification),
            context.mainExecutor,
            {},
        )
    }
}

/** The project's channel: builds and announcements. */
