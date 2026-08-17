package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val label: String) {
    System("Системная"),
    Light("Светлая"),
    Dark("Тёмная"),
}

@Serializable
enum class RoutingMode(val label: String, val description: String) {
    Global("Глобально", "Весь трафик идёт через прокси"),
    Rules("По правилам", "Локальные и российские адреса — напрямую, остальное через прокси"),
    Direct(
        "Напрямую",
        "Прокси не используется: трафик идёт с обычного адреса, но имена разрешает выбранный DNS",
    ),
}

@Serializable
enum class SplitTunnelMode(val label: String) {
    Off("Выключено"),
    AllowList("Только выбранные"),
    BlockList("Кроме выбранных"),
}

@Serializable
enum class LogLevel { Trace, Debug, Info, Warn, Error }

/**
 * How a server's latency gets measured. QUIC nodes ignore this and always use a UDP probe —
 * they have no TCP port to handshake with.
 */
@Serializable
enum class PingMode(val label: String, val description: String) {
    Tcp("TCP", "Одно рукопожатие TCP. Быстро, годится для длинных списков"),
    Tls(
        "TLS",
        "Полное рукопожатие TLS: дороже на пару обменов, зато видно, что узел действительно " +
            "отвечает шифрованием, а не просто держит порт открытым",
    ),
    Median("Медиана", "Три пробы, берётся средняя. Медленнее, но число не пляшет"),
}

@Serializable
data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.System,
    // Off by default: wallpaper colours replace the ice accent, and the tunnel screen leans on
    // that accent meaning "protected". Still a switch — the choice belongs to the user.
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,

    // Routing
    val routingMode: RoutingMode = RoutingMode.Rules,
    val bypassLan: Boolean = true,
    val blockAds: Boolean = false,

    // Tunnel
    val enableIpv6: Boolean = false,
    val mtu: Int = 9000,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.Off,
    val splitTunnelPackages: Set<String> = emptySet(),

    // DNS
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "8.8.8.8",
    val hijackDns: Boolean = true,

    /**
     * Identifier some subscription panels require before they hand over the server list, sent as
     * `x-hwid`. Generated once per installation and random — a real device identifier would
     * follow the user across every panel they ever subscribe to.
     */
    val deviceId: String = "",

    // Behaviour
    val pingMode: PingMode = PingMode.Tcp,
    val autoConnectOnBoot: Boolean = false,
    val autoSelectFastest: Boolean = false,
    // Hands the core a group of servers instead of one, so a server that stops answering is
    // stepped over without the tunnel dropping. Off by default: it makes the core probe several
    // servers on a schedule, and the server actually carrying traffic stops being the one the
    // user picked.
    val autoFailover: Boolean = false,
    /**
     * Servers the tunnel may switch to. Empty means "decide for me", and the group is filled with
     * neighbours from the chosen server's own subscription.
     */
    val failoverNodeIds: Set<String> = emptySet(),
    val subscriptionAutoUpdate: Boolean = true,
    /**
     * How often subscriptions refresh themselves, in minutes.
     *
     * Kept here rather than per subscription: the question a user has is "how fresh should my
     * server lists be", asked once, not once per provider. The per-subscription field that used to
     * sit in [Subscription] answered it in hours and was never read by anything.
     */
    val subscriptionUpdateMinutes: Int = 360,
    val logLevel: LogLevel = LogLevel.Info,
)
