package com.mydrop.vpn.e2e

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.data.SettingsRepository
import com.mydrop.vpn.data.TunnelHealthCheck
import com.mydrop.vpn.pairing.PairingCrypto
import com.mydrop.vpn.pairing.PairingInvite
import com.mydrop.vpn.pairing.PairingSessionGuard
import com.mydrop.vpn.pairing.SubscriptionTransfer
import com.mydrop.vpn.remote.REMOTE_PORT
import com.mydrop.vpn.remote.REMOTE_VERSION
import com.mydrop.vpn.remote.RemoteAuthority
import com.mydrop.vpn.remote.RemoteBeacon
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.remote.RemoteCrypto
import com.mydrop.vpn.remote.RemoteIdentity
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemotePeer
import com.mydrop.vpn.remote.RemoteServer
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteTunnelState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

/**
 * Tier 1 & Tier 2 Opaque-Box E2E Tests for:
 * - Area A7: Elimination of `127.0.0.1` loopback SOCKS proxy (`probe-in`) across `TunnelHealthCheck`,
 *   `SpeedTester`, `GeoAssetStore`, `SubscriptionService`, `MapTileStore`, `TunnelConfigBuilder`,
 *   and migration to in-process `core.Dial` (`XrayCore` bridge).
 * - Area A9: Server entry IP / VPN IP non-disclosure across `RemoteSnapshot`/`RemoteBeacon`/
 *   `RemoteServer`/`PairingReceiver`, Android Manifests IPC surfaces, and Always-On zero-toggle
 *   invariant in `AppSettings`, `TvScreens`, and string resources.
 */
class LoopbackAndIpcLeakE2eTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testEntryIp = "203.0.113.77"
    private val testEntryPort = 8443
    private val testVpnExitIp = "198.51.100.42"
    private val testSecretUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    // ------------------------------------------------------------------ Area A7: Loopback Removal

    @Test
    fun t1_a7_04_tunnelHealthCheckDoesNotDialLoopbackSocksProxy() = runBlocking {
        E2eContractAdapters.LoopbackTrapServer().use { trap ->
            val healthCheck = TunnelHealthCheck()
            val fakeProbe = ProbeEndpoint(port = trap.port, username = "u", password = "p")

            E2eContractAdapters.invokeHealthCheckPasses(healthCheck, fakeProbe)
            E2eContractAdapters.invokeHealthCheckResolves(healthCheck, fakeProbe)

            assertEquals(
                "TunnelHealthCheck must NOT dial 127.0.0.1 loopback proxy (F10 — must use in-process core.Dial): " +
                    "observed ${trap.acceptCount.get()} loopback proxy connection(s)",
                0,
                trap.acceptCount.get(),
            )
        }
        // Also verify XrayCore exposes the M1/M2 in-process core.Dial bridge + TrafficGuard
        E2eContractAdapters.requireXrayCoreTrafficGuardAndDialBridge()
    }

    @Test
    fun t1_a7_05_speedTesterAndTunnelConfigBuilderDoNotUseLoopbackSocksProxy() {
        val speedTesterSrc = E2eContractAdapters.stripKotlinComments(
            E2eContractAdapters.readRepoFile(
                "shared/src/main/kotlin/com/mydrop/vpn/data/SpeedTester.kt",
            ),
        )
        val builderSrc = E2eContractAdapters.stripKotlinComments(
            E2eContractAdapters.readRepoFile(
                "shared/src/main/kotlin/com/mydrop/vpn/data/TunnelConfigBuilder.kt",
            ),
        )

        assertFalse(
            "SpeedTester must migrate off 127.0.0.1 loopback proxy (F10)",
            speedTesterSrc.contains("127.0.0.1") || speedTesterSrc.contains("Proxy-Authorization"),
        )
        assertFalse(
            "TunnelConfigBuilder must NOT bind newProbeEndpoint() on 127.0.0.1 (F10)",
            builderSrc.contains("newProbeEndpoint") || builderSrc.contains("127.0.0.1"),
        )
        E2eContractAdapters.requireXrayCoreTrafficGuardAndDialBridge()
    }

    @Test
    fun t2_a7_04_tunnelHealthCheckReturnsStoppedSignalWithoutLoopbackDialWhenCoreStopped() = runBlocking {
        val healthCheck = TunnelHealthCheck()
        val passes = E2eContractAdapters.invokeHealthCheckPasses(healthCheck, null)
        val resolves = E2eContractAdapters.invokeHealthCheckResolves(healthCheck, null)

        // When core is stopped, passes() is null (direct fallback) or false — never true, never throws
        assertTrue(
            "Stopped core must yield null or false from passes(), got $passes",
            passes == null || passes == false,
        )
        assertTrue(
            "Stopped core must yield null or false from resolves(), got $resolves",
            resolves == null || resolves == false,
        )
    }

    @Test
    fun t2_a7_05_geoAssetStoreSubscriptionServiceAndMapTileStoreDoNotUseLoopbackSocksProxy() {
        val files = listOf(
            "shared/src/main/kotlin/com/mydrop/vpn/data/GeoAssetStore.kt",
            "shared/src/main/kotlin/com/mydrop/vpn/data/SubscriptionService.kt",
            "shared/src/main/kotlin/com/mydrop/vpn/data/MapTileStore.kt",
        )
        for (path in files) {
            val src = E2eContractAdapters.stripKotlinComments(E2eContractAdapters.readRepoFile(path))
            assertFalse(
                "$path must NOT open Proxy.Type.SOCKS to 127.0.0.1 (F10 loopback proxy removal)",
                src.contains("Proxy.Type.SOCKS") || src.contains("127.0.0.1"),
            )
        }
    }

    // ------------------------------------------------------------------ Area A9: Remote / Pairing / IPC / Zero Toggle

    @Test
    fun t1_a9_01_remoteSnapshotAndRemoteNodeStripServerEntryIpPortAndCredentials() {
        val node = RemoteNode(
            id = "node-1",
            name = "🇩🇪 Frankfurt",
            group = "Provider",
            latencyMillis = 42,
            latencyFailed = false,
        )
        val snapshot = RemoteSnapshot(
            hostName = "Yumi TV",
            state = RemoteTunnelState.On,
            message = "",
            needsConsentOnTv = false,
            selectedNodeId = node.id,
            connectedAtEpochMillis = 1_757_000_000_000L,
            downloadBytesPerSecond = 2048,
            uploadBytesPerSecond = 512,
            nodesRevision = 1,
            nodes = listOf(node),
        )

        val encoded = RemoteCrypto.json.encodeToString(snapshot)
        assertFalse(encoded.contains(testEntryIp))
        assertFalse(encoded.contains(testEntryPort.toString()))
        assertFalse(encoded.contains(testVpnExitIp))
        assertFalse(encoded.contains(testSecretUuid))
        assertFalse(encoded.contains("server", ignoreCase = true))
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertFalse(encoded.contains("uuid", ignoreCase = true))
        assertFalse(encoded.contains("exitIp", ignoreCase = true))
    }

    @Test
    fun t1_a9_02_remoteBeaconUdpReplyDisclosesZeroVpnIpOrServerEntryIp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keys = RemoteCrypto.keyPair()
        val cleanIdentity = RemoteIdentity(
            id = "tv-host-1",
            name = "Yumi TV",
            publicKey = keys.public,
            privateKey = keys.private,
        )
        val beacon = RemoteBeacon(
            scope = scope,
            identity = { cleanIdentity },
            port = { REMOTE_PORT },
        )
        beacon.start()
        try {
            // Poll briefly until UDP socket binds on 47655 (or skip if port already occupied on host)
            var replyText: String? = null
            DatagramSocket().use { client ->
                client.soTimeout = 400
                val payload = "yumi-remote-probe-v1".toByteArray()
                val loopback = InetAddress.getByName("127.0.0.1")
                repeat(10) {
                    if (replyText != null) return@repeat
                    client.send(DatagramPacket(payload, payload.size, loopback, com.mydrop.vpn.remote.REMOTE_BEACON_PORT))
                    val buf = ByteArray(512)
                    val resp = DatagramPacket(buf, buf.size)
                    runCatching {
                        client.receive(resp)
                        replyText = String(resp.data, resp.offset, resp.length, Charsets.UTF_8)
                    }
                    if (replyText == null) Thread.sleep(100)
                }
            }
            val text = replyText
                ?: fail("RemoteBeacon did not reply on UDP ${com.mydrop.vpn.remote.REMOTE_BEACON_PORT}")
            assertTrue((text as String).contains("\"id\":\"tv-host-1\""))
            assertFalse(text.contains(testEntryIp))
            assertFalse(text.contains(testVpnExitIp))
            assertFalse(text.contains(testSecretUuid))
        } finally {
            beacon.stop()
            scope.cancel()
        }
    }

    @Test
    fun t1_a9_03_remoteServerRejectsUnauthenticatedAndMalformedTcpClients() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keys = RemoteCrypto.keyPair()
        val authority = object : RemoteAuthority {
            override fun identity() = RemoteIdentity("tv-1", "Yumi TV", keys.public, keys.private)
            override fun openPairingCode(): String? = null
            override fun acceptPairing(deviceName: String): RemotePeer? = null
            override fun secretOf(peerId: String): String? = null
            override fun seen(peerId: String) = Unit
        }
        val snapshots = MutableStateFlow(
            RemoteSnapshot(
                hostName = "Yumi TV",
                state = RemoteTunnelState.On,
                nodesRevision = 1,
                nodes = listOf(RemoteNode("n1", "Germany")),
            ),
        )
        val commands = CopyOnWriteArrayList<RemoteCommand>()
        val server = RemoteServer(scope, authority, snapshots) { commands.add(it) }
        server.start()
        try {
            val boundPort = runBlocking {
                withTimeoutOrNull(5_000L) { server.port.first { it > 0 } }
            } ?: 0
            assertTrue("RemoteServer must bind a port", boundPort > 0)

            // 1. Garbage frame
            Socket("127.0.0.1", boundPort).use { client ->
                client.soTimeout = 1500
                val out = DataOutputStream(client.getOutputStream())
                val bad = "{\"garbage\":true}".toByteArray()
                out.writeInt(bad.size)
                out.write(bad)
                out.flush()
                assertEquals(-1, client.getInputStream().read())
            }

            // 2. Unauthenticated greeting with unknown peerId
            Socket("127.0.0.1", boundPort).use { client ->
                client.soTimeout = 1500
                val phoneKeys = RemoteCrypto.keyPair()
                val greeting = com.mydrop.vpn.remote.RemoteGreeting(
                    version = REMOTE_VERSION,
                    sessionId = RemoteCrypto.token(),
                    peerId = "unknown-peer",
                    senderPublicKey = RemoteCrypto.encode(phoneKeys.public),
                    iv = "AAAAAAAAAAAAAAAA",
                    ciphertext = "AAAA",
                )
                val bytes = RemoteCrypto.json.encodeToString(greeting).toByteArray()
                val out = DataOutputStream(client.getOutputStream())
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                assertEquals(-1, client.getInputStream().read())
            }
            assertTrue(commands.isEmpty())
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun t1_a9_04_appSettingsSchemaAndDefaultsHaveZeroSplitTunnelGuardToggle() {
        val names = AppSettings.serializer().descriptor.let { desc ->
            (0 until desc.elementsCount).map { desc.getElementName(it) }
        }
        val splitTunnelFields = names.filter {
            it.contains("split", ignoreCase = true) ||
                it.contains("guard", ignoreCase = true) ||
                it.contains("leak", ignoreCase = true)
        }
        assertEquals(
            "AppSettings must have ONLY splitTunnelMode and splitTunnelPackages (F12 no-toggle invariant), found: $splitTunnelFields",
            setOf("splitTunnelMode", "splitTunnelPackages"),
            splitTunnelFields.toSet(),
        )
    }

    @Test
    fun t1_a9_05_androidManifestsExportZeroVpnOrServerIpIpcSurfaces() {
        val manifests = listOf(
            "shared/src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "tv/src/main/AndroidManifest.xml",
        )
        val allowedExportedActivities = setOf(".MainActivity", ".TvActivity")
        val allowedExportedReceivers = setOf(
            "com.mydrop.vpn.vpn.BootReceiver",
            ".vpn.BootReceiver",
            ".vpn.TunnelWidgetProvider",
        )

        val dbf = DocumentBuilderFactory.newInstance()
        for (path in manifests) {
            val xml = File(E2eContractAdapters.repoRoot(), path)
            assertTrue("Missing manifest $path", xml.isFile)
            val doc = dbf.newDocumentBuilder().parse(xml)

            val providers = doc.getElementsByTagName("provider")
            for (i in 0 until providers.length) {
                val el = providers.item(i) as Element
                assertEquals(
                    "$path must not export ContentProvider ${el.getAttribute("android:name")}",
                    "false",
                    el.getAttribute("android:exported"),
                )
            }

            val services = doc.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val el = services.item(i) as Element
                val name = el.getAttribute("android:name")
                val exported = el.getAttribute("android:exported")
                val permission = el.getAttribute("android:permission")
                if (name.endsWith("MyDropVpnService")) {
                    assertEquals("$path MyDropVpnService must be exported=false", "false", exported)
                    assertEquals("android.permission.BIND_VPN_SERVICE", permission)
                } else if (exported == "true") {
                    assertEquals(
                        "$path exported service $name must require BIND_QUICK_SETTINGS_TILE",
                        "android.permission.BIND_QUICK_SETTINGS_TILE",
                        permission,
                    )
                }
            }

            val activities = doc.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val el = activities.item(i) as Element
                if (el.getAttribute("android:exported") == "true") {
                    assertTrue(
                        "$path unexpected exported activity: ${el.getAttribute("android:name")}",
                        el.getAttribute("android:name") in allowedExportedActivities,
                    )
                }
            }

            val receivers = doc.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                val el = receivers.item(i) as Element
                if (el.getAttribute("android:exported") == "true") {
                    assertTrue(
                        "$path unexpected exported receiver: ${el.getAttribute("android:name")}",
                        el.getAttribute("android:name") in allowedExportedReceivers,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ Tier 2: Area A9

    @Test
    fun t2_a9_01_remoteCryptoAndLinkRejectReplayAndCrossDirectionFrames() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val sid = RemoteCrypto.token()
        val sealed = RemoteCrypto.seal(
            RemoteCrypto.json.encodeToString<RemoteCommand>(RemoteCommand.Look).toByteArray(),
            key,
            RemoteCrypto.aad(sid, fromPhone = true, counter = 1),
        )

        // Replay counter=2 must fail AEAD verification
        assertThrows(AEADBadTagException::class.java) {
            RemoteCrypto.open(sealed, key, RemoteCrypto.aad(sid, fromPhone = true, counter = 2))
        }
        // Cross-direction fromPhone=false must fail AEAD verification
        assertThrows(AEADBadTagException::class.java) {
            RemoteCrypto.open(sealed, key, RemoteCrypto.aad(sid, fromPhone = false, counter = 1))
        }
    }

    @Test
    fun t2_a9_02_remoteServerRejectsWrongPskAndClosedPairingWindow() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keys = RemoteCrypto.keyPair()
        val currentCode = AtomicReference<String?>(RemoteCrypto.token())
        val authority = object : RemoteAuthority {
            override fun identity() = RemoteIdentity("tv-1", "Yumi TV", keys.public, keys.private)
            override fun openPairingCode(): String? = currentCode.get()
            override fun acceptPairing(deviceName: String): RemotePeer? =
                if (currentCode.get() != null) RemotePeer("p1", deviceName, "secret") else null
            override fun secretOf(peerId: String): String? = null
            override fun seen(peerId: String) = Unit
        }
        val snapshots = MutableStateFlow(RemoteSnapshot(hostName = "Yumi TV"))
        val server = RemoteServer(scope, authority, snapshots) {}
        server.start()
        try {
            val boundPort = runBlocking {
                withTimeoutOrNull(5_000L) { server.port.first { it > 0 } }
            } ?: 0
            assertTrue(boundPort > 0)

            // Wrong PSK while pairing window is open
            Socket("127.0.0.1", boundPort).use { client ->
                client.soTimeout = 1500
                val phoneKeys = RemoteCrypto.keyPair()
                val sid = RemoteCrypto.token()
                val wrongKey = RemoteCrypto.sessionKey(phoneKeys.private, keys.public, "wrong-psk", sid)
                val opening = RemoteCrypto.seal(
                    "{\"deviceName\":\"Attacker\"}".toByteArray(),
                    wrongKey,
                    RemoteCrypto.aad(sid, fromPhone = true, counter = 0),
                )
                val greeting = com.mydrop.vpn.remote.RemoteGreeting(
                    version = REMOTE_VERSION,
                    sessionId = sid,
                    peerId = "",
                    senderPublicKey = RemoteCrypto.encode(phoneKeys.public),
                    iv = opening.iv,
                    ciphertext = opening.ciphertext,
                )
                val bytes = RemoteCrypto.json.encodeToString(greeting).toByteArray()
                val out = DataOutputStream(client.getOutputStream())
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                assertEquals(-1, client.getInputStream().read())
            }

            // Closed pairing window with any code
            currentCode.set(null)
            Socket("127.0.0.1", boundPort).use { client ->
                client.soTimeout = 1500
                val phoneKeys = RemoteCrypto.keyPair()
                val sid = RemoteCrypto.token()
                val key = RemoteCrypto.sessionKey(phoneKeys.private, keys.public, "expired-code", sid)
                val opening = RemoteCrypto.seal(
                    "{\"deviceName\":\"Phone\"}".toByteArray(),
                    key,
                    RemoteCrypto.aad(sid, fromPhone = true, counter = 0),
                )
                val greeting = com.mydrop.vpn.remote.RemoteGreeting(
                    version = REMOTE_VERSION,
                    sessionId = sid,
                    peerId = "",
                    senderPublicKey = RemoteCrypto.encode(phoneKeys.public),
                    iv = opening.iv,
                    ciphertext = opening.ciphertext,
                )
                val bytes = RemoteCrypto.json.encodeToString(greeting).toByteArray()
                val out = DataOutputStream(client.getOutputStream())
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                assertEquals(-1, client.getInputStream().read())
            }
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun t2_a9_03_pairingTransferStripsXhwidAndRejectsExpiredSession() {
        val transferWithHwid = SubscriptionTransfer(
            name = "Sub",
            url = "https://example.com/sub",
            headers = mapOf("x-hwid" to "leak-id"),
        )
        assertNull("SubscriptionTransfer.validate must reject x-hwid", transferWithHwid.validate())

        var now = 10_000L
        val guard = PairingSessionGuard("sid-1", expiresAtMillis = 15_000L) { now }
        assertTrue(guard.isCurrent("sid-1"))
        now = 15_001L
        assertFalse("Expired pairing session must be rejected", guard.isCurrent("sid-1"))
        assertFalse("Expired pairing session cannot be claimed", guard.claim())

        // Verify valid PairingCrypto request round-trip does not embed server entry IP or VPN IP
        val receiverKeys = PairingCrypto.keyPair()
        val invite = PairingInvite(
            version = com.mydrop.vpn.pairing.VERSION,
            host = "192.168.1.10",
            port = 40000,
            sessionId = PairingCrypto.sessionId(),
            receiverPublicKey = PairingCrypto.publicKey(receiverKeys.public),
            deviceName = "Yumi TV",
        )
        val validTransfer = SubscriptionTransfer(name = "Sub", url = "https://example.com/sub")
        val (envelope, _) = PairingCrypto.request(invite, validTransfer)
        val wireText = PairingCrypto.json.encodeToString(envelope)
        assertFalse(wireText.contains(testEntryIp))
        assertFalse(wireText.contains("x-hwid", ignoreCase = true))
    }

    @Test
    fun t2_a9_04_legacyAppSettingsJsonWithUnknownGuardToggleKeyIsIgnoredSafely() {
        val dir = tempFolder.newFolder("settings-legacy")
        File(dir, "settings.json").writeText(
            """
            {
              "splitTunnelMode": "AllowList",
              "splitTunnelPackages": ["org.mozilla.firefox"],
              "disableSplitTunnelGuard": true,
              "splitTunnelLeakProtection": false
            }
            """.trimIndent(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = SettingsRepository(dir, scope)
            assertEquals(SplitTunnelMode.AllowList, repo.value.splitTunnelMode)
            assertEquals(setOf("org.mozilla.firefox"), repo.value.splitTunnelPackages)
            // Re-encoding AppSettings must not preserve injected unknown keys
            val serialized = Json.encodeToString(AppSettings.serializer(), repo.value)
            assertFalse(serialized.contains("disableSplitTunnelGuard"))
            assertFalse(serialized.contains("splitTunnelLeakProtection"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun t2_a9_05_sharedStringsAndTvScreensContainZeroSplitTunnelGuardToggles() {
        val stringsEn = E2eContractAdapters.readRepoFile("shared/src/main/res/values/strings.xml")
        val stringsRu = E2eContractAdapters.readRepoFile("shared/src/main/res/values-ru/strings.xml")
        val tvScreens = E2eContractAdapters.readRepoFile("tv/src/main/kotlin/com/mydrop/vpn/tv/TvScreens.kt")

        val forbiddenPatterns = listOf(
            "split_tunnel_guard",
            "split_tunnel_leak",
            "disable_split_tunnel",
            "утечка split",
            "защита от утеч",
        )
        for (pattern in forbiddenPatterns) {
            assertFalse("strings.xml (en) must not contain guard toggle '$pattern' (F12)", stringsEn.contains(pattern, ignoreCase = true))
            assertFalse("strings.xml (ru) must not contain guard toggle '$pattern' (F12)", stringsRu.contains(pattern, ignoreCase = true))
            assertFalse("TvScreens.kt must not contain guard toggle '$pattern' (F12)", tvScreens.contains(pattern, ignoreCase = true))
        }
    }
}
