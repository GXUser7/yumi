package com.mydrop.vpn.core.model

import androidx.annotation.StringRes
import com.mydrop.vpn.R
import kotlinx.serialization.Serializable

/*
 * Why these enums carry resource ids rather than text.
 *
 * They used to hold the Russian words themselves, which put user-visible copy in the one layer
 * that has no way to know what language the user reads. Serialisation is unaffected: kotlinx
 * writes the entry name (`Rules`), never the constructor arguments, so a stored profile survives
 * this change and would survive a retranslation too.
 */

@Serializable
enum class ThemeMode(@StringRes val labelRes: Int) {
    System(R.string.theme_system),
    Light(R.string.theme_light),
    Dark(R.string.theme_dark),
}

/**
 * The language the interface is drawn in.
 *
 * [System] follows the phone, which resolves to Russian on a Russian phone and to English
 * everywhere else — those are the two the app ships. A named choice overrides that, because the
 * language of a phone and the language its owner wants an app in are not always the same.
 *
 * The tag is what [java.util.Locale.forLanguageTag] takes; null means "do not override".
 */
@Serializable
enum class AppLanguage(val tag: String?, @StringRes val labelRes: Int) {
    System(null, R.string.language_system),
    Russian("ru", R.string.language_russian),
    English("en", R.string.language_english),
}

@Serializable
enum class RoutingMode(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    Global(R.string.routing_global, R.string.routing_global_description),
    Rules(R.string.routing_rules, R.string.routing_rules_description),
    Direct(R.string.routing_direct, R.string.routing_direct_description),
}

@Serializable
enum class SplitTunnelMode(@StringRes val labelRes: Int) {
    Off(R.string.split_tunnel_off),
    AllowList(R.string.split_tunnel_allow_list),
    BlockList(R.string.split_tunnel_block_list),
}

@Serializable
enum class LogLevel { Trace, Debug, Info, Warn, Error }

/**
 * How a server's latency gets measured. QUIC nodes ignore this and always use a UDP probe —
 * they have no TCP port to handshake with.
 */
@Serializable
enum class PingMode(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    Tcp(R.string.ping_tcp, R.string.ping_tcp_description),
    Tls(R.string.ping_tls, R.string.ping_tls_description),
    Median(R.string.ping_median, R.string.ping_median_description),
}

@Serializable
data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.System,
    // Off by default: wallpaper colours replace the ice accent, and the tunnel screen leans on
    // that accent meaning "protected". Still a switch — the choice belongs to the user.
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,
    val language: AppLanguage = AppLanguage.System,

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
