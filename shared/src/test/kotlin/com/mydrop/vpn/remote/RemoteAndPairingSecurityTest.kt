package com.mydrop.vpn.remote

import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.core.model.Palette
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SubscriptionUserInfo
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.data.sanitizeRemoteMessage
import com.mydrop.vpn.pairing.PairingCrypto
import com.mydrop.vpn.pairing.PairingInvite
import com.mydrop.vpn.pairing.SubscriptionTransfer
import java.io.File
import java.net.InetAddress
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F14: Verifies TV remote/pairing wire payloads and Android manifests/services expose zero
 * external VPN IP, TUN interface IP, or proxy server entry IP.
 */
class RemoteAndPairingSecurityTest {

    @Test
    fun `RemoteSnapshot and RemoteNode serialize zero server entry IP, port, credentials, or TUN IP`() {
        val snapshot = RemoteSnapshot(
            hostName = "Yumi TV · Living room",
            state = RemoteTunnelState.On,
            selectedNodeId = "n1",
            connectedAtEpochMillis = 1_757_000_000_000L,
            downloadBytesPerSecond = 2048,
            uploadBytesPerSecond = 1024,
            nodesRevision = 7,
            nodes = listOf(
                RemoteNode(
                    id = "n1",
                    name = "🇩🇪 Berlin",
                    group = "Provider",
                    latencyMillis = 42,
                    latencyFailed = false,
                ),
            ),
        )
        val encoded = RemoteCrypto.json.encodeToString(snapshot)
        assertFalse(encoded.contains("server"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("uuid"))
        assertFalse(encoded.contains(XrayConfigFactory.TUN_ADDRESS_V4))
        assertFalse(encoded.contains(XrayConfigFactory.TUN_DNS_V4))
        assertFalse(encoded.contains(XrayConfigFactory.TUN_ADDRESS_V6))
        assertFalse(encoded.contains("203.0.113."))
    }

    @Test
    fun `Enriched RemoteSnapshot with subscriptions and settings serializes zero banned tokens or TUN IPs`() {
        val snapshot = RemoteSnapshot(
            hostName = "Yumi TV · Living room",
            state = RemoteTunnelState.On,
            selectedNodeId = "n1",
            connectedAtEpochMillis = 1_757_000_000_000L,
            downloadBytesPerSecond = 8192,
            uploadBytesPerSecond = 2048,
            nodesRevision = 12,
            nodes = listOf(
                RemoteNode(
                    id = "n1",
                    name = "🇩🇪 Berlin",
                    group = "Quattro",
                    latencyMillis = 36,
                    latencyFailed = false,
                    protocol = Protocol.VLESS,
                    badges = listOf("REALITY", "Vision", "uTLS:chrome"),
                    subscriptionId = "sub-1",
                    memberProtocols = listOf(Protocol.VLESS, Protocol.TROJAN),
                ),
            ),
            pingingNodeIds = setOf("n2"),
            subscriptionsRevision = 3,
            subscriptions = listOf(
                RemoteSubscription(
                    id = "sub-1",
                    name = "Quattro",
                    remoteTitle = "Quattro VPN",
                    urlHost = "panel.example.com",
                    enabled = true,
                    nodeCount = 50,
                    lastUpdatedEpochMillis = 1_757_000_000_000L,
                    lastError = "dial tcp [redacted]: timeout",
                    userInfo = SubscriptionUserInfo(100L, 200L, 1000L),
                ),
            ),
            settings = RemoteSettings(
                visualizer = Visualizer.Planet,
                themeMode = ThemeMode.Dark,
                language = AppLanguage.Russian,
                palette = Palette.Mint,
                routingMode = RoutingMode.Rules,
                bypassLan = true,
                blockAds = true,
                remoteDns = "https://1.1.1.1/dns-query",
                directDns = "8.8.8.8",
                mtu = 1500,
            ),
        )
        val encoded = RemoteCrypto.json.encodeToString(snapshot)
        assertFalse(encoded.contains("server", ignoreCase = true))
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertFalse(encoded.contains("uuid", ignoreCase = true))
        assertFalse(encoded.contains(XrayConfigFactory.TUN_ADDRESS_V4))
        assertFalse(encoded.contains(XrayConfigFactory.TUN_DNS_V4))
        assertFalse(encoded.contains(XrayConfigFactory.TUN_ADDRESS_V6))
        assertFalse(encoded.contains("203.0.113."))
    }

    @Test
    fun `RemoteInvite and PairingInvite expose only LAN host and reject TUN or external metadata`() {
        val remoteKey = RemoteCrypto.encode(RemoteCrypto.keyPair().public)
        val remoteInvite = RemoteInvite(
            version = REMOTE_VERSION,
            host = "192.168.1.50",
            port = REMOTE_PORT,
            code = RemoteCrypto.token(),
            hostPublicKey = remoteKey,
            hostId = "tv-1",
            hostName = "Yumi TV",
        ).encode()
        assertFalse(remoteInvite.contains(XrayConfigFactory.TUN_ADDRESS_V4))
        assertFalse(remoteInvite.contains(XrayConfigFactory.TUN_DNS_V4))
        assertFalse(remoteInvite.contains("vless"))

        val pairingKey = PairingCrypto.publicKey(PairingCrypto.keyPair().public)
        val pairingInvite = PairingInvite(
            version = com.mydrop.vpn.pairing.VERSION,
            host = "192.168.1.50",
            port = 43210,
            sessionId = PairingCrypto.sessionId(),
            receiverPublicKey = pairingKey,
            deviceName = "Yumi TV",
        ).encode()
        assertFalse(pairingInvite.contains(XrayConfigFactory.TUN_ADDRESS_V4))
        assertFalse(pairingInvite.contains(XrayConfigFactory.TUN_DNS_V4))
    }

    @Test
    fun `SubscriptionTransfer rejects x-hwid headers and non-http schemes`() {
        assertNull(
            SubscriptionTransfer(
                name = "Sub",
                url = "https://example.com/sub",
                headers = mapOf("x-hwid" to "secret-id"),
            ).validate(),
        )
        assertNull(
            SubscriptionTransfer(
                name = "Sub",
                url = "socks://127.0.0.1:1080",
            ).validate(),
        )
    }

    @Test
    fun `shared AndroidManifest enforces non-exported MyDropVpnService and FileProvider`() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("shared/src/main/AndroidManifest.xml"),
        ).first { it.isFile }
        val text = manifestFile.readText()
        assertTrue(text.contains("MyDropVpnService"))
        assertTrue(text.contains("android:permission=\"android.permission.BIND_VPN_SERVICE\""))
        assertTrue(text.contains("android:exported=\"false\""))
    }

    @Test
    fun `Tier5 LAN transport and address selection rejects TRANSPORT_VPN and TUN subnet 172_19_0_0_30`() {
        assertFalse(isLanTransport(NetworkTransportFlags(hasWifi = true, hasEthernet = false, hasVpn = true)))
        assertFalse(isLanTransport(NetworkTransportFlags(hasWifi = false, hasEthernet = true, hasVpn = true)))
        assertFalse(isLanTransport(NetworkTransportFlags(hasWifi = false, hasEthernet = false, hasVpn = false)))
        assertTrue(isLanTransport(NetworkTransportFlags(hasWifi = true, hasEthernet = false, hasVpn = false)))
        assertTrue(isLanTransport(NetworkTransportFlags(hasWifi = false, hasEthernet = true, hasVpn = false)))

        val tunCandidate = LanNetworkCandidate(
            value = "tun0",
            transports = NetworkTransportFlags(hasWifi = true, hasEthernet = false, hasVpn = true),
            addresses = listOf(InetAddress.getByName(XrayConfigFactory.TUN_ADDRESS_V4)),
        )
        val wlanCandidate = LanNetworkCandidate(
            value = "wlan0",
            transports = NetworkTransportFlags(hasWifi = true, hasEthernet = false, hasVpn = false),
            addresses = listOf(InetAddress.getByName("192.168.1.50")),
        )
        val filtered = filterLanCandidates(listOf(tunCandidate, wlanCandidate))
        assertEquals(listOf("wlan0"), filtered.map { it.value })
        assertEquals("192.168.1.50", selectLanAddress(listOf(tunCandidate, wlanCandidate))?.hostAddress)

        // Even if a non-VPN network carries the TUN subnet address, reject 172.19.0.0/30
        val spoofedTunCandidate = LanNetworkCandidate(
            value = "spoof",
            transports = NetworkTransportFlags(hasWifi = true, hasEthernet = false, hasVpn = false),
            addresses = listOf(
                InetAddress.getByName(XrayConfigFactory.TUN_ADDRESS_V4),
                InetAddress.getByName(XrayConfigFactory.TUN_DNS_V4),
            ),
        )
        assertNull(selectLanAddress(listOf(spoofedTunCandidate)))
        assertFalse(isPrivateIpv4(XrayConfigFactory.TUN_ADDRESS_V4))
        assertFalse(isPrivateIpv4(XrayConfigFactory.TUN_DNS_V4))
        assertFalse(isPrivateIpv4("172.19.0.0"))
        assertFalse(isPrivateIpv4("172.19.0.3"))
        assertFalse(isPrivateIpv4("127.0.0.1"))
        assertFalse(isPrivateIpv4("8.8.8.8"))
        assertTrue(isPrivateIpv4("192.168.1.50"))
        assertTrue(isPrivateIpv4("10.0.0.5"))
        assertTrue(isPrivateIpv4("172.16.0.5"))
        assertTrue(isPrivateIpv4("172.19.1.5"))
    }

    @Test
    fun `Tier5 sanitizeRemoteMessage redacts IPv4, IPv6, and host_port while keeping readable context`() {
        val rawV4 = "failed to dial tcp:203.0.113.77:8443: i/o timeout"
        val cleanV4 = sanitizeRemoteMessage(rawV4)
        assertFalse(cleanV4.contains("203.0.113.77"))
        assertFalse(cleanV4.contains("8443"))
        assertTrue(cleanV4.contains("i/o timeout"))

        val rawV6 = "dial udp [2001:db8::1]:443 and fdfe:dcba:9876::1: network unreachable"
        val cleanV6 = sanitizeRemoteMessage(rawV6)
        assertFalse(cleanV6.contains("2001:db8::1"))
        assertFalse(cleanV6.contains("fdfe:dcba:9876::1"))
        assertTrue(cleanV6.contains("network unreachable"))

        val rawHostPort = "dial tcp edge.secret-vpn.example:8443: connection refused"
        val cleanHostPort = sanitizeRemoteMessage(rawHostPort)
        assertFalse(cleanHostPort.contains("edge.secret-vpn.example"))
        assertFalse(cleanHostPort.contains("8443"))
        assertTrue(cleanHostPort.contains("connection refused"))

        // Colon-prefixed bare IPv6 (Go/Xray dial errors) and punycode TLDs must be redacted
        val colonV6 = sanitizeRemoteMessage("failed to dial tcp:2001:db8::1: i/o timeout")
        assertEquals("failed to dial tcp:[redacted]: i/o timeout", colonV6)
        assertFalse("Colon-prefixed bare IPv6 leaked: $colonV6", colonV6.contains("2001:db8::1"))
        assertTrue(colonV6.contains("i/o timeout"))

        val colonMappedV6 = sanitizeRemoteMessage("failed to dial tcp:::ffff:203.0.113.77: i/o timeout")
        assertEquals("failed to dial tcp:[redacted]: i/o timeout", colonMappedV6)
        assertFalse("Colon-prefixed v4-mapped IPv6 leaked: $colonMappedV6", colonMappedV6.contains("203.0.113.77"))
        assertFalse("Colon-prefixed v4-mapped IPv6 leaked trailing octets: $colonMappedV6", colonMappedV6.contains("0.113.77"))
        assertFalse(colonMappedV6.contains("ffff"))

        val bareMappedTcpV6 = sanitizeRemoteMessage("dial tcp ::ffff:203.0.113.77: i/o timeout")
        assertEquals("dial tcp [redacted]: i/o timeout", bareMappedTcpV6)
        assertFalse("Bare v4-mapped IPv6 leaked trailing octets: $bareMappedTcpV6", bareMappedTcpV6.contains("0.113.77"))

        val bareMappedUdpV6 = sanitizeRemoteMessage("dial udp ::ffff:198.51.100.42: error")
        assertEquals("dial udp [redacted]: error", bareMappedUdpV6)
        assertFalse("Bare v4-mapped UDP IPv6 leaked trailing octets: $bareMappedUdpV6", bareMappedUdpV6.contains("51.100.42"))

        val doubleColonTerminatedV6 = sanitizeRemoteMessage("dial tcp:2001:db8::: timeout")
        assertEquals("dial tcp:[redacted]: timeout", doubleColonTerminatedV6)
        assertFalse("Double-colon-terminated IPv6 leaked: $doubleColonTerminatedV6", doubleColonTerminatedV6.contains("2001:db8"))

        val bareDoubleColonTerminatedV6 = sanitizeRemoteMessage("dial tcp:2001:db8::")
        assertEquals("dial tcp:[redacted]", bareDoubleColonTerminatedV6)

        val zonedBareV6 = sanitizeRemoteMessage("dial tcp fe80::1%eth0: timeout")
        assertEquals("dial tcp [redacted]: timeout", zonedBareV6)

        val bracketedZonedV6 = sanitizeRemoteMessage("dial tcp [2001:db8::1%25eth0]:443: timeout")
        assertEquals("dial tcp [redacted]: timeout", bracketedZonedV6)

        val colonLoopbackV6 = sanitizeRemoteMessage("dial udp:::1: refused")
        assertEquals("dial udp:[redacted]: refused", colonLoopbackV6)
        assertFalse(colonLoopbackV6.contains("::1"))
        assertTrue(colonLoopbackV6.contains("refused"))

        val punycodeHost = sanitizeRemoteMessage("dial tcp server.xn--p1ai:8443: timeout")
        assertEquals("dial tcp [redacted]: timeout", punycodeHost)
        assertFalse("Punycode host:port leaked: $punycodeHost", punycodeHost.contains("xn--p1ai"))
        assertFalse(punycodeHost.contains("8443"))
        assertTrue(punycodeHost.contains("timeout"))

        // Bare IPv6 + 5-digit port and full 8-hextet bare IPv6 + port must be redacted
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:db8::1:12345: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:db8::1:10000: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:db8::1:65535: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: refused",
            sanitizeRemoteMessage("dial tcp ::1:12345: refused"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:0db8:85a3:0000:0000:8a2e:0370:7334:443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:0db8:0:0:0:0:0:1:8443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:0db8:85a3:0000:0000:8a2e:0370:7334: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:db8::1:8443: timeout"),
        )

        // Trailing-dot FQDNs and localhost + port must be redacted
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp edge.secret-vpn.example.:8443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp edge.secret-vpn.example.:443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp localhost.:8443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp localhost:8443: timeout"),
        )

        // Compressed :: immediately preceding dotted-quad IPv4 suffix and trailing-dot IPv4 + port
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 2001:db8:abcd::203.0.113.77:8443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 64:ff9b::203.0.113.77:8443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp ::203.0.113.77:12345: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp ::203.0.113.77: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]: timeout",
            sanitizeRemoteMessage("dial tcp 127.0.0.1.:8443: timeout"),
        )
        assertEquals(
            "dial tcp [redacted]. timeout",
            sanitizeRemoteMessage("dial tcp 127.0.0.1. timeout"),
        )

        // Non-domain word:port (no dot) and timestamps remain intact
        assertEquals("error code foo:80 at 12:34:56", sanitizeRemoteMessage("error code foo:80 at 12:34:56"))
        assertEquals("VPN permission revoked at 12:34:56", sanitizeRemoteMessage("VPN permission revoked at 12:34:56"))
        assertEquals("time 12:34:56:78", sanitizeRemoteMessage("time 12:34:56:78"))
    }
}
