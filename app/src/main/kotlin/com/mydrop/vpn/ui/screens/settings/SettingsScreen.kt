package com.mydrop.vpn.ui.screens.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.BRAWL_STARS_PACKAGE
import com.mydrop.vpn.core.model.DnsProfile
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.ui.components.ScreenHeader
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
    30 to "30 мин",
    60 to "1 час",
    180 to "3 часа",
    360 to "6 часов",
    720 to "12 часов",
    1440 to "Раз в сутки",
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    splitTunnelAppCount: Int,
    dnsProfiles: List<DnsProfile>,
    selectedDnsId: String?,
    onSelectDns: (String?) -> Unit,
    onRemoveDns: (String) -> Unit,
    /** Separate from [onUpdate]: this one reloads a running core instead of only storing. */
    onSetBrawlStarsMode: (Boolean) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onOpenFailover: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            ScreenHeader(title = "Настройки", modifier = Modifier.padding(bottom = 8.dp))
        }

        item("appearance") {
            SettingsSection(title = "Внешний вид", icon = Icons.Rounded.Palette) {
                Text(
                    text = "Тема",
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
                            Text(mode.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                SwitchRow(
                    title = "Динамические цвета",
                    subtitle = "Палитра из обоев системы (Android 12+)",
                    checked = settings.dynamicColor,
                    onCheckedChange = { onUpdate { s -> s.copy(dynamicColor = it) } },
                )
                SwitchRow(
                    title = "AMOLED-чёрный",
                    subtitle = "Чистый чёрный фон в тёмной теме",
                    checked = settings.amoled,
                    onCheckedChange = { onUpdate { s -> s.copy(amoled = it) } },
                )
            }
        }

        item("routing") {
            SettingsSection(title = "Маршрутизация", icon = Icons.Rounded.Router) {
                SwitchRow(
                    title = "Локальная сеть напрямую",
                    subtitle = "Не заворачивать в туннель адреса 192.168.x.x и подобные",
                    checked = settings.bypassLan,
                    onCheckedChange = { onUpdate { s -> s.copy(bypassLan = it) } },
                )
                SwitchRow(
                    title = "Блокировать рекламу",
                    subtitle = "Отбрасывать запросы к известным рекламным доменам",
                    checked = settings.blockAds,
                    onCheckedChange = { onUpdate { s -> s.copy(blockAds = it) } },
                )

                NavigationRow(
                    title = "Раздельное туннелирование",
                    subtitle = when (settings.splitTunnelMode) {
                        SplitTunnelMode.Off -> "Все приложения через VPN"
                        SplitTunnelMode.AllowList -> "Только выбранные: $splitTunnelAppCount"
                        SplitTunnelMode.BlockList -> "Исключено приложений: $splitTunnelAppCount"
                    },
                    icon = Icons.Rounded.Apps,
                    onClick = onOpenSplitTunnel,
                )
            }
        }

        item("tunnel") {
            SettingsSection(title = "Туннель", icon = Icons.Rounded.Shield) {
                SwitchRow(
                    title = "IPv6",
                    subtitle = "Поднимать IPv6-адрес на интерфейсе",
                    checked = settings.enableIpv6,
                    onCheckedChange = { onUpdate { s -> s.copy(enableIpv6 = it) } },
                )
                SwitchRow(
                    title = "Перехват DNS",
                    subtitle = "Заворачивать запросы к порту 53 в собственный резолвер",
                    checked = settings.hijackDns,
                    onCheckedChange = { onUpdate { s -> s.copy(hijackDns = it) } },
                )

                Spacer(Modifier.height(8.dp))

                ValidatedField(
                    initial = settings.mtu.toString(),
                    label = "MTU",
                    hint = "От 1280 до 9000",
                    // Anything below the IPv6 minimum silently breaks large packets.
                    error = "Допустимо от 1280 до 9000",
                    isValid = { it.toIntOrNull() in 1280..9000 },
                    onCommit = { raw -> onUpdate { s -> s.copy(mtu = raw.toInt()) } },
                )
            }
        }

        item("dns") {
            SettingsSection(title = "DNS", icon = Icons.Rounded.Dns) {
                ValidatedField(
                    initial = settings.remoteDns,
                    label = "DNS через прокси",
                    hint = "Используется для проксируемых доменов",
                    error = "Адрес не может быть пустым",
                    isValid = { it.isNotBlank() },
                    onCommit = { value -> onUpdate { s -> s.copy(remoteDns = value) } },
                )
                Spacer(Modifier.height(8.dp))
                ValidatedField(
                    initial = settings.directDns,
                    label = "Прямой DNS",
                    hint = "Для доменов, идущих мимо прокси",
                    error = "Адрес не может быть пустым",
                    isValid = { it.isNotBlank() },
                    onCommit = { value -> onUpdate { s -> s.copy(directDns = value) } },
                )

                if (dnsProfiles.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Добавленные резолверы",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // "From settings" is an entry of its own rather than a switch: with a resolver
                    // selected, the fields above are not what the tunnel uses, and a list you
                    // cannot step out of would leave no way to say that.
                    DnsRow(
                        title = "Из настроек",
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

        item("brawl") {
            SettingsSection(title = "Brawl Stars", icon = Icons.Rounded.SportsEsports) {
                SwitchRow(
                    title = "Разблокировать через xbox-dns.ru",
                    subtitle = "Игра резолвится через xbox-dns.ru, а её трафик идёт мимо прокси. " +
                        "Действует только при поднятом туннеле",
                    checked = settings.brawlStarsMode,
                    onCheckedChange = onSetBrawlStarsMode,
                )

                // The switch quietly wins over split tunnelling, because it has to: outside the
                // tunnel the core never sees the game and neither half of this can apply. Saying
                // so is the difference between a deliberate override and a setting that lies.
                val overrulesSplitTunnel = when (settings.splitTunnelMode) {
                    SplitTunnelMode.Off -> false
                    SplitTunnelMode.AllowList ->
                        BRAWL_STARS_PACKAGE !in settings.splitTunnelPackages
                    SplitTunnelMode.BlockList ->
                        BRAWL_STARS_PACKAGE in settings.splitTunnelPackages
                }
                if (settings.brawlStarsMode && overrulesSplitTunnel) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Раздельное туннелирование оставляло Brawl Stars вне туннеля — " +
                            "пока переключатель включён, игра держится внутри, иначе её DNS " +
                            "достаётся системному резолверу и разблокировка не сработает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item("ping") {
            SettingsSection(title = "Замер задержки", icon = Icons.Rounded.NetworkPing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PingMode.entries.forEach { mode ->
                        ToggleButton(
                            checked = settings.pingMode == mode,
                            onCheckedChange = { onUpdate { it.copy(pingMode = mode) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(mode.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = settings.pingMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Hysteria2 и TUIC работают поверх QUIC и TCP-порта не имеют — " +
                        "их всегда проверяет UDP-проба, независимо от выбора выше.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("behaviour") {
            SettingsSection(title = "Поведение", icon = Icons.Rounded.Bolt) {
                SwitchRow(
                    title = "Подключаться при загрузке",
                    subtitle = "Поднимать туннель после перезагрузки телефона",
                    checked = settings.autoConnectOnBoot,
                    onCheckedChange = { onUpdate { s -> s.copy(autoConnectOnBoot = it) } },
                )
                SwitchRow(
                    title = "Выбирать быстрый сервер",
                    subtitle = "Перед подключением брать сервер с наименьшей задержкой",
                    checked = settings.autoSelectFastest,
                    onCheckedChange = { onUpdate { s -> s.copy(autoSelectFastest = it) } },
                )
                SwitchRow(
                    title = "Уходить с упавшего сервера",
                    subtitle = "Проверять текущий сервер и переходить на живой, если он упал",
                    checked = settings.autoFailover,
                    onCheckedChange = { onUpdate { s -> s.copy(autoFailover = it) } },
                )
                AnimatedVisibility(visible = settings.autoFailover) {
                    NavigationRow(
                        title = "Серверы для подмены",
                        subtitle = if (settings.failoverNodeIds.isEmpty()) {
                            "Выбираются автоматически из текущей подписки"
                        } else {
                            "Выбрано вручную: ${settings.failoverNodeIds.size}"
                        },
                        icon = Icons.Rounded.SwapHoriz,
                        onClick = onOpenFailover,
                    )
                }
                SwitchRow(
                    title = "Автообновление подписок",
                    subtitle = "Периодически перечитывать список серверов",
                    checked = settings.subscriptionAutoUpdate,
                    onCheckedChange = { onUpdate { s -> s.copy(subscriptionAutoUpdate = it) } },
                )

                AnimatedVisibility(visible = settings.subscriptionAutoUpdate) {
                    Column {
                        Text(
                            text = "Как часто",
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
                            UPDATE_INTERVALS.forEach { (minutes, label) ->
                                ToggleButton(
                                    checked = settings.subscriptionUpdateMinutes == minutes,
                                    onCheckedChange = {
                                        onUpdate { s -> s.copy(subscriptionUpdateMinutes = minutes) }
                                    },
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Text(
                            text = "Обновление происходит, пока приложение запущено — будильник " +
                                "ради перечитывания списка серверов не стоит расхода батареи",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }


        item("diagnostics") {
            SettingsSection(title = "Диагностика", icon = Icons.Rounded.Article) {
                NavigationRow(
                    title = "Журнал",
                    subtitle = "Логи ядра и подписок",
                    icon = Icons.Rounded.Article,
                    onClick = onOpenLogs,
                )
                QuickTileRow()
            }
        }

        item("about") {
            val context = LocalContext.current
            // Read from the package rather than written here: the line above it claimed the core
            // was "coming in the next stage" for as long as the core had been carrying traffic,
            // which is what a hand-maintained string does.
            val version = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
                    // The build type appends its own suffix, and "0.2.0-debug" on screen is a
                    // detail of how the APK was made, not something the reader has any use for.
                    ?.substringBefore('-')
                    .orEmpty()
            }

            SettingsSection(title = "О приложении", icon = Icons.Rounded.Contrast) {
                Text(
                    text = if (version.isEmpty()) "Yumi" else "Yumi $version",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Material 3 Expressive · трафик несёт sing-box",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Проверка скорости — бета: замер идёт через выбранный сервер, " +
                        "и числа ещё уточняются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(4.dp))

                NavigationRow(
                    title = "Канал в Telegram",
                    subtitle = "t.me/MaterialYouCloud — сборки и новости",
                    icon = Icons.Rounded.Send,
                    // An arrow out rather than a chevron: this row leaves the app, and the two
                    // should not look like the same kind of tap.
                    trailingIcon = Icons.Rounded.OpenInNew,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, TELEGRAM_URL.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
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
                    contentDescription = "Удалить",
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
        title = "Плитка в шторке",
        subtitle = if (supported) {
            "Добавить переключатель туннеля в быстрые настройки"
        } else {
            "Добавляется вручную: шторка → карандаш → перетащить «Yumi»"
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
private const val TELEGRAM_URL = "https://t.me/MaterialYouCloud"
