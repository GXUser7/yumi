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
    /**
     * Rejects QUIC on 443 so browsers fall back to TLS over TCP.
     *
     * UDP is throttled hard enough on Russian networks that a QUIC request usually neither
     * succeeds nor fails quickly — it sits until a timeout, which is what
     * `listen packet connection ... context deadline exceeded` in the journal is. A rejected
     * attempt is answered immediately and Chrome switches protocol on the spot, so blocking it
     * is faster than letting it through. Off by default: on a network that does not throttle
     * UDP, QUIC is the better transport and this only takes it away.
     */
    val blockQuic: Boolean = false,

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
     * Whether a resolver that stops answering is replaced with a known-good one.
     *
     * A dead resolver is the one outage the app cannot fix by changing servers, and the one the
     * user is least likely to diagnose: every name stops resolving at once, so the symptom is
     * "the internet is gone" while the tunnel itself is provably carrying traffic. On, the app
     * notices and swaps in a numeric DoH endpoint until the connection is next restarted.
     *
     * On by default, but a switch rather than a certainty: a resolver is chosen for what it does
     * beyond resolving — ad filtering, family rules, something a workplace requires — and quietly
     * substituting a plain one takes all of that away. Somebody who chose theirs for a reason may
     * prefer the outage.
     */
    val dnsFallback: Boolean = true,

    /**
     * Identifier some subscription panels require before they hand over the server list, sent as
     * `x-hwid`. Generated once per installation and random — a real device identifier would
     * follow the user across every panel they ever subscribe to.
     */
    val deviceId: String = "",

    // Behaviour
    /**
     * TLS rather than TCP, because a bare handshake answers the wrong question here.
     *
     * Russian DPI routinely lets the TCP connection to a blocked server complete and kills the
     * session after the ClientHello. A TCP probe sees the SYN-ACK, calls the server alive, and
     * [com.mydrop.vpn.data.FailoverWatchdog] never moves off a server that carries nothing. A
     * full handshake is the cheapest probe that fails when the thing the user cares about fails —
     * and for REALITY it is the difference between "the port is open" and "the disguise works".
     */
    val pingMode: PingMode = PingMode.Tls,
    val autoConnectOnBoot: Boolean = false,
    val autoSelectFastest: Boolean = false,
    // Hands the core a group of servers instead of one, so a server that stops answering is
    // stepped over without the tunnel dropping. Off by default: it makes the core probe several
    // servers on a schedule, and the server actually carrying traffic stops being the one the
    // user picked.
    val autoFailover: Boolean = false,
    /**
     * Whether an automatic switch is provisional or final.
     *
     * On, the server the user chose is remembered while the watchdog is riding a replacement, and
     * the tunnel goes back to it once it answers again — otherwise one bad minute silently becomes
     * permanent, and the only clue is that the connect screen now names a server nobody picked,
     * possibly in another country.
     *
     * Off, a switch is simply where the tunnel now lives until someone chooses otherwise. Which is
     * a defensible preference rather than a wrong one: going back interrupts a tunnel that is
     * working, on the evidence of a probe that only proves a port is open.
     */
    val returnHome: Boolean = true,
    /**
     * Servers the tunnel may switch to. Empty means "decide for me", and the group is filled with
     * neighbours from the chosen server's own subscription.
     */
    val failoverNodeIds: Set<String> = emptySet(),
    /**
     * Servers the tunnel may use while the phone is on a cellular network.
     *
     * Empty means the feature does not exist: one pool for every network, exactly as before. That
     * is deliberate — a list nobody filled in must not change anybody's behaviour.
     *
     * Filled, it becomes the *only* pool while the phone is on cellular: the tunnel moves onto one
     * of these when the network becomes cellular, and a failure there is replaced from this list
     * and no other. Providers sell servers tuned for mobile networks, and the point of naming them
     * is to actually be on one when it matters.
     *
     * Cellular means cellular — [android.net.NetworkCapabilities.TRANSPORT_CELLULAR] — and not
     * "metered". A 5G plan can report itself temporarily unmetered, and a home Wi-Fi can be marked
     * metered by its owner; keying off the meter would switch the feature off and on for reasons
     * that have nothing to do with which network the phone is on.
     */
    val mobileNodeIds: Set<String> = emptySet(),
    /**
     * Whether returning to Wi-Fi puts the tunnel back on the exact server it left.
     *
     * Off, coming home simply runs the ordinary choice, which may land somewhere else and is one
     * fewer thing to reason about. On, the server chosen before leaving is remembered and restored
     * — predictable, at the cost of one more switch.
     */
    val restoreWifiNodeOnWifi: Boolean = false,
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
    /**
     * Whether the app says out loud that it fixed something.
     *
     * The whole value of automatic failover is that nobody is watching when it fires, which is
     * exactly why the user never learns that it did: the only lasting symptom is a connect screen
     * naming a server they did not choose, discovered hours later. Same for a resolver that was
     * swapped out from under them.
     *
     * On by default, and a switch rather than a build flag: how chatty a VPN should be about its
     * own plumbing is a matter of taste, and the honest way to settle a matter of taste is to let
     * the person whose phone it is settle it.
     */
    val faultAlerts: Boolean = true,

    // Updates
    /**
     * Whether the app asks GitHub twice a day whether a newer release exists.
     *
     * On by default. The app is distributed outside any store, so nothing updates it unless it
     * updates itself, and the people running the oldest builds are by definition the ones who
     * never saw the announcement of the fix they need.
     */
    val updateAutoCheck: Boolean = true,
    /**
     * When the last check happened, wall clock. Persisted so that opening the app six times a day
     * still amounts to one check rather than six — see [com.mydrop.vpn.data.UpdateScheduler].
     */
    val lastUpdateCheckEpochMillis: Long = 0L,
)
