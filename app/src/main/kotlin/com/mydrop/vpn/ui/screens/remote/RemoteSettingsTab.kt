package com.mydrop.vpn.ui.screens.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.core.model.FragmentMode
import com.mydrop.vpn.core.model.FragmentScope
import com.mydrop.vpn.core.model.Palette
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.core.model.TlsFingerprintOverride
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.data.RemoteConsoleState
import com.mydrop.vpn.data.RemoteLinkState
import com.mydrop.vpn.remote.RemoteSettingsPatch
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.screens.settings.ChoiceButton
import com.mydrop.vpn.ui.screens.settings.DependentGroup
import com.mydrop.vpn.ui.screens.settings.FragmentPreset
import com.mydrop.vpn.ui.screens.settings.FragmentationSettings
import com.mydrop.vpn.ui.screens.settings.GroupDivider
import com.mydrop.vpn.ui.screens.settings.NavigationRow
import com.mydrop.vpn.ui.screens.settings.SettingsSection
import com.mydrop.vpn.ui.screens.settings.SwitchRow
import com.mydrop.vpn.ui.screens.settings.TlsFingerprintSetting
import com.mydrop.vpn.ui.screens.settings.ValidatedField
import com.mydrop.vpn.ui.theme.scheme

private val REMOTE_UPDATE_INTERVALS = listOf(
    30 to R.string.settings_interval_30m,
    60 to R.string.settings_interval_1h,
    180 to R.string.settings_interval_3h,
    360 to R.string.settings_interval_6h,
    720 to R.string.settings_interval_12h,
    1440 to R.string.settings_interval_1d,
)

@Composable
internal fun RemoteSettingsTab(
    state: RemoteConsoleState,
    darkTheme: Boolean,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onOpenRename: () -> Unit,
    onOpenForget: () -> Unit,
    onPatchSettings: (RemoteSettingsPatch) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val bond = state.active ?: return
    val online = state.link is RemoteLinkState.Online
    val tvSettings = state.snapshot?.settings

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                subtitle = bond.displayName,
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

        item(key = "tv-management") {
            SettingsSection(
                title = stringResource(R.string.remote_section_tv),
                icon = Icons.Rounded.Tv,
            ) {
                NavigationRow(
                    title = stringResource(R.string.remote_tv_alias),
                    subtitle = bond.displayName,
                    icon = Icons.Rounded.Tv,
                    onClick = onOpenRename,
                )
                GroupDivider()
                NavigationRow(
                    title = stringResource(R.string.remote_tv_address),
                    subtitle = if (bond.lastHost.isNotEmpty()) {
                        "${bond.lastHost}:${bond.lastPort}"
                    } else {
                        stringResource(R.string.remote_tv_address_unknown)
                    },
                    icon = Icons.Rounded.Refresh,
                    onClick = onReconnect,
                )
                GroupDivider()
                NavigationRow(
                    title = stringResource(R.string.remote_forget),
                    subtitle = bond.hostName,
                    icon = Icons.Rounded.LinkOff,
                    onClick = onOpenForget,
                )
            }
        }

        if (tvSettings == null) {
            item(key = "legacy-settings") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            if (online) R.string.remote_legacy_tv_hint else R.string.remote_offline,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            item(key = "appearance") {
                Column(Modifier.alpha(if (online) 1f else 0.58f)) {
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
                                ChoiceButton(
                                    label = stringResource(mode.labelRes),
                                    checked = tvSettings.themeMode == mode,
                                    onClick = {
                                        if (online) onPatchSettings(RemoteSettingsPatch(themeMode = mode))
                                    },
                                )
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
                                ChoiceButton(
                                    label = stringResource(language.labelRes),
                                    checked = tvSettings.language == language,
                                    onClick = {
                                        if (online) onPatchSettings(RemoteSettingsPatch(language = language))
                                    },
                                )
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
                                ChoiceButton(
                                    label = stringResource(visualizer.labelRes),
                                    checked = tvSettings.visualizer == visualizer,
                                    onClick = {
                                        if (online) onPatchSettings(RemoteSettingsPatch(visualizer = visualizer))
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = "${stringResource(R.string.remote_palette_title)} · ${
                                stringResource(tvSettings.palette.labelRes)
                            }",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Palette.entries.forEach { option ->
                                val swatchScheme = remember(option, darkTheme) { option.scheme(darkTheme) }
                                val chosen = option == tvSettings.palette
                                val label = stringResource(option.labelRes)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(swatchScheme.primary)
                                        .then(
                                            if (chosen) {
                                                Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .semantics {
                                            contentDescription = label
                                            selected = chosen
                                        }
                                        .clickable(role = Role.RadioButton) {
                                            if (online && !chosen) {
                                                onPatchSettings(RemoteSettingsPatch(palette = option))
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (chosen) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = swatchScheme.onPrimary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "routing") {
                Column(Modifier.alpha(if (online) 1f else 0.58f)) {
                    SettingsSection(
                        title = stringResource(R.string.settings_routing),
                        icon = Icons.Rounded.Router,
                    ) {
                        Text(
                            text = stringResource(R.string.remote_routing_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoutingMode.entries.forEach { mode ->
                                ChoiceButton(
                                    label = stringResource(mode.labelRes),
                                    checked = tvSettings.routingMode == mode,
                                    onClick = {
                                        if (online && tvSettings.routingMode != mode) {
                                            onPatchSettings(RemoteSettingsPatch(routingMode = mode))
                                        }
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        SwitchRow(
                            title = stringResource(R.string.settings_provider_routing),
                            subtitle = stringResource(R.string.settings_provider_routing_subtitle),
                            checked = tvSettings.providerRouting,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(providerRouting = it))
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_bypass_lan),
                            subtitle = stringResource(R.string.settings_bypass_lan_subtitle),
                            checked = tvSettings.bypassLan,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(bypassLan = it))
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_block_ads),
                            subtitle = stringResource(R.string.settings_block_ads_subtitle),
                            checked = tvSettings.blockAds,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(blockAds = it))
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_block_quic),
                            subtitle = stringResource(R.string.settings_block_quic_subtitle),
                            checked = tvSettings.blockQuic,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(blockQuic = it))
                            },
                        )
                    }
                }
            }

            item(key = "tunnel") {
                Column(Modifier.alpha(if (online) 1f else 0.58f)) {
                    SettingsSection(
                        title = stringResource(R.string.settings_tunnel),
                        icon = Icons.Rounded.Shield,
                    ) {
                        SwitchRow(
                            title = "IPv6",
                            subtitle = stringResource(R.string.settings_ipv6_subtitle),
                            checked = tvSettings.enableIpv6,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(enableIpv6 = it))
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_hijack_dns),
                            subtitle = stringResource(R.string.settings_hijack_dns_subtitle),
                            checked = tvSettings.hijackDns,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(hijackDns = it))
                            },
                        )

                        Spacer(Modifier.height(8.dp))

                        key(bond.hostId) {
                            ValidatedField(
                                initial = tvSettings.mtu.toString(),
                                label = "MTU",
                                hint = stringResource(R.string.settings_mtu_hint),
                                error = stringResource(R.string.settings_mtu_error),
                                isValid = { it.toIntOrNull() in 1280..9000 },
                                onCommit = { raw ->
                                    val mtu = raw.toIntOrNull() ?: return@ValidatedField
                                    if (online && mtu != tvSettings.mtu) {
                                        onPatchSettings(RemoteSettingsPatch(mtu = mtu))
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        TlsFingerprintSetting(
                            selected = tvSettings.tlsFingerprint,
                            onSelect = { fp ->
                                if (online && tvSettings.tlsFingerprint != fp) {
                                    onPatchSettings(RemoteSettingsPatch(tlsFingerprint = fp))
                                }
                            },
                        )
                        Spacer(Modifier.height(16.dp))
                        var presetEpoch by remember(bond.hostId) { mutableIntStateOf(0) }
                        var pendingPreset by remember(bond.hostId) { mutableStateOf<FragmentPreset?>(null) }
                        LaunchedEffect(
                            tvSettings.fragmentLength,
                            tvSettings.fragmentInterval,
                            tvSettings.fragmentMaxChunks,
                        ) {
                            val activePreset = pendingPreset
                            if (activePreset != null &&
                                tvSettings.fragmentLength == activePreset.length &&
                                tvSettings.fragmentInterval == activePreset.interval &&
                                tvSettings.fragmentMaxChunks == activePreset.maxChunks
                            ) {
                                pendingPreset = null
                            }
                        }
                        FragmentationSettings(
                            mode = tvSettings.fragmentMode,
                            scope = tvSettings.fragmentScope,
                            length = pendingPreset?.length ?: tvSettings.fragmentLength,
                            interval = pendingPreset?.interval ?: tvSettings.fragmentInterval,
                            maxChunks = pendingPreset?.maxChunks ?: tvSettings.fragmentMaxChunks,
                            resetKey = Pair(bond.hostId, presetEpoch),
                            onModeChange = { mode ->
                                if (online && tvSettings.fragmentMode != mode) {
                                    onPatchSettings(RemoteSettingsPatch(fragmentMode = mode))
                                }
                            },
                            onScopeChange = { scope ->
                                if (online && tvSettings.fragmentScope != scope) {
                                    onPatchSettings(RemoteSettingsPatch(fragmentScope = scope))
                                }
                            },
                            onPresetSelect = { preset ->
                                if (online) {
                                    pendingPreset = preset
                                    presetEpoch++
                                    onPatchSettings(
                                        RemoteSettingsPatch(
                                            fragmentLength = preset.length,
                                            fragmentInterval = preset.interval,
                                            fragmentMaxChunks = preset.maxChunks,
                                        ),
                                    )
                                }
                            },
                            onLengthCommit = { value ->
                                if (online) {
                                    pendingPreset = null
                                    if (value != tvSettings.fragmentLength) {
                                        onPatchSettings(RemoteSettingsPatch(fragmentLength = value))
                                    }
                                }
                            },
                            onIntervalCommit = { value ->
                                if (online) {
                                    pendingPreset = null
                                    if (value != tvSettings.fragmentInterval) {
                                        onPatchSettings(RemoteSettingsPatch(fragmentInterval = value))
                                    }
                                }
                            },
                            onMaxChunksCommit = { value ->
                                if (online) {
                                    pendingPreset = null
                                    if (value != tvSettings.fragmentMaxChunks) {
                                        onPatchSettings(RemoteSettingsPatch(fragmentMaxChunks = value))
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item(key = "dns") {
                Column(Modifier.alpha(if (online) 1f else 0.58f)) {
                    SettingsSection(
                        title = stringResource(R.string.settings_dns),
                        icon = Icons.Rounded.Dns,
                    ) {
                        key(bond.hostId) {
                            ValidatedField(
                                initial = tvSettings.remoteDns,
                                label = stringResource(R.string.settings_remote_dns),
                                hint = stringResource(R.string.settings_remote_dns_hint),
                                error = stringResource(R.string.settings_dns_empty_error),
                                isValid = { it.isNotBlank() },
                                onCommit = { value ->
                                    val clean = value.trim()
                                    if (online && clean != tvSettings.remoteDns) {
                                        onPatchSettings(RemoteSettingsPatch(remoteDns = clean))
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            ValidatedField(
                                initial = tvSettings.directDns,
                                label = stringResource(R.string.settings_direct_dns),
                                hint = stringResource(R.string.settings_direct_dns_hint),
                                error = stringResource(R.string.settings_dns_empty_error),
                                isValid = { it.isNotBlank() },
                                onCommit = { value ->
                                    val clean = value.trim()
                                    if (online && clean != tvSettings.directDns) {
                                        onPatchSettings(RemoteSettingsPatch(directDns = clean))
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(
                            title = stringResource(R.string.settings_dns_fallback),
                            subtitle = stringResource(R.string.settings_dns_fallback_subtitle),
                            checked = tvSettings.dnsFallback,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(dnsFallback = it))
                            },
                        )
                    }
                }
            }

            item(key = "behaviour") {
                Column(Modifier.alpha(if (online) 1f else 0.58f)) {
                    SettingsSection(
                        title = stringResource(R.string.settings_behaviour),
                        icon = Icons.Rounded.Bolt,
                    ) {
                        SwitchRow(
                            title = stringResource(R.string.settings_boot),
                            subtitle = stringResource(R.string.settings_boot_subtitle),
                            checked = tvSettings.autoConnectOnBoot,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(autoConnectOnBoot = it))
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_fastest),
                            subtitle = stringResource(R.string.settings_fastest_subtitle),
                            checked = tvSettings.autoSelectFastest,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(autoSelectFastest = it))
                            },
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_auto_update),
                            subtitle = stringResource(R.string.settings_auto_update_subtitle),
                            checked = tvSettings.subscriptionAutoUpdate,
                            onCheckedChange = {
                                if (online) onPatchSettings(RemoteSettingsPatch(subscriptionAutoUpdate = it))
                            },
                        )

                        AnimatedVisibility(visible = tvSettings.subscriptionAutoUpdate) {
                            DependentGroup {
                                Column(Modifier.padding(vertical = 8.dp)) {
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
                                        REMOTE_UPDATE_INTERVALS.forEach { (minutes, labelRes) ->
                                            ToggleButton(
                                                checked = tvSettings.subscriptionUpdateMinutes == minutes,
                                                onCheckedChange = {
                                                    if (online && tvSettings.subscriptionUpdateMinutes != minutes) {
                                                        onPatchSettings(
                                                            RemoteSettingsPatch(subscriptionUpdateMinutes = minutes),
                                                        )
                                                    }
                                                },
                                            ) {
                                                Text(
                                                    text = stringResource(labelRes),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(88.dp)) }
    }
}
