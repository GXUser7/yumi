package com.mydrop.vpn.e2e

import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.data.LatencyTester
import com.mydrop.vpn.data.ProfileState
import com.mydrop.vpn.ui.MainViewModel
import com.mydrop.vpn.ui.ServersUiState
import com.mydrop.vpn.ui.screens.servers.ServerSort
import com.mydrop.vpn.ui.screens.servers.buildGroups
import java.io.Closeable
import java.io.File
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 1–4 Opaque-Box E2E Tests for F17 (Filtered Server Ping in `:app` & `:tv`) and
 * F12 (`:app` UI zero split-tunnel guard toggle invariant).
 *
 * Covers:
 * - Area A10: `T1-A10-01..05`, `T2-A10-01..05`, `T4-05`
 * - Area A9: `T2-A9-05` (`:app` `SplitTunnelScreen` & `SettingsScreen` zero guard toggle)
 */
class FilteredServerPingE2eTest {

    private class LoopbackProbeCounter : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val acceptCount = AtomicInteger(0)
        private val running = AtomicBoolean(true)

        private val thread = Thread {
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    acceptCount.incrementAndGet()
                    runCatching { client.close() }
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                    // Ignore transient teardown errors
                }
            }
        }.apply {
            isDaemon = true
            name = "e2e-ping-counter"
            start()
        }

        fun awaitAcceptAtLeast(min: Int, timeoutMs: Long = 1000L): Int {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (acceptCount.get() < min && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            return acceptCount.get()
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            runCatching { thread.join(500) }
        }
    }

    private fun stripKotlinComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n) {
                        if (source[i] == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                            out.append("\"\"\"")
                            i += 3
                            break
                        }
                        out.append(source[i++])
                    }
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < n) {
                        val sc = source[i++]
                        out.append(sc)
                        if (sc == '\\' && i < n) {
                            out.append(source[i++])
                        } else if (sc == '"') {
                            break
                        }
                    }
                }
                c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < n) {
                        val cc = source[i++]
                        out.append(cc)
                        if (cc == '\\' && i < n) {
                            out.append(source[i++])
                        } else if (cc == '\'') {
                            break
                        }
                    }
                }
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (source[i] == '/' && i + 1 < n && source[i + 1] == '*') {
                            depth++
                            i += 2
                        } else if (source[i] == '*' && i + 1 < n && source[i + 1] == '/') {
                            depth--
                            i += 2
                        } else {
                            if (source[i] == '\n') out.append('\n')
                            i++
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun extractBalancedDelimiter(
        code: String,
        openIdx: Int,
        openChar: Char,
        closeChar: Char,
    ): Int {
        var depth = 1
        var idx = openIdx + 1
        while (idx < code.length && depth > 0) {
            when (code[idx]) {
                '"' -> {
                    if (idx + 2 < code.length && code[idx + 1] == '"' && code[idx + 2] == '"') {
                        idx += 3
                        while (idx < code.length) {
                            if (code[idx] == '"' && idx + 2 < code.length && code[idx + 1] == '"' && code[idx + 2] == '"') {
                                idx += 3
                                break
                            }
                            idx++
                        }
                    } else {
                        idx++
                        while (idx < code.length) {
                            val sc = code[idx++]
                            if (sc == '\\' && idx < code.length) {
                                idx++
                            } else if (sc == '"') {
                                break
                            }
                        }
                    }
                }
                '\'' -> {
                    idx++
                    while (idx < code.length) {
                        val cc = code[idx++]
                        if (cc == '\\' && idx < code.length) {
                            idx++
                        } else if (cc == '\'') {
                            break
                        }
                    }
                }
                openChar -> {
                    depth++
                    idx++
                }
                closeChar -> {
                    depth--
                    if (depth == 0) return idx
                    idx++
                }
                else -> idx++
            }
        }
        return code.length
    }

    private fun extractFunctionBodies(code: String, namePattern: Regex): List<Triple<String, String, String>> {
        val headerRegex = Regex("""fun\s+([A-Za-z_]\w*)\s*\(""")
        val results = mutableListOf<Triple<String, String, String>>()
        for (match in headerRegex.findAll(code)) {
            val fnName = match.groupValues[1]
            if (!namePattern.matches(fnName)) continue
            val paramsOpenIdx = match.range.last
            val paramsCloseIdx = extractBalancedDelimiter(code, paramsOpenIdx, '(', ')')
            if (paramsCloseIdx >= code.length) continue
            val params = code.substring(paramsOpenIdx + 1, paramsCloseIdx).trim()

            // Scan past optional return type `: Type` until `{` or `=` (not `==`).
            var cursor = paramsCloseIdx + 1
            var bodyKind = '\u0000'
            var parenDepth = 0
            var angleDepth = 0
            while (cursor < code.length) {
                val ch = code[cursor]
                when {
                    ch == '(' -> parenDepth++
                    ch == ')' -> if (parenDepth > 0) parenDepth--
                    ch == '<' -> angleDepth++
                    ch == '>' -> if (angleDepth > 0) angleDepth--
                    parenDepth == 0 && angleDepth == 0 && ch == '{' -> {
                        bodyKind = '{'
                        break
                    }
                    parenDepth == 0 && angleDepth == 0 && ch == '=' &&
                        (cursor + 1 >= code.length || code[cursor + 1] != '=') -> {
                        bodyKind = '='
                        break
                    }
                    parenDepth == 0 && angleDepth == 0 && ch == '\n' -> {
                        val restOfHeader = code.substring(paramsCloseIdx + 1, cursor).trim()
                        if (restOfHeader.isEmpty() || (!restOfHeader.endsWith(":") && !restOfHeader.endsWith("->"))) {
                            // Interface/abstract declaration without body
                            break
                        }
                    }
                }
                cursor++
            }

            val body = when (bodyKind) {
                '{' -> {
                    val closeBraceIdx = extractBalancedDelimiter(code, cursor, '{', '}')
                    code.substring(cursor + 1, closeBraceIdx.coerceAtLeast(cursor + 1))
                }
                '=' -> {
                    var idx = cursor + 1
                    var depth = 0
                    while (idx < code.length) {
                        val ch = code[idx]
                        when (ch) {
                            '(', '{', '[' -> depth++
                            ')', '}', ']' -> if (depth > 0) depth--
                            '\n' -> if (depth == 0) {
                                val lineBefore = code.substring(cursor + 1, idx).trimEnd()
                                val afterNl = code.substring(idx + 1).trimStart(' ', '\t')
                                val continues = lineBefore.isEmpty() ||
                                    lineBefore.endsWith("=") ||
                                    lineBefore.endsWith(".") ||
                                    lineBefore.endsWith(",") ||
                                    lineBefore.endsWith("->") ||
                                    afterNl.startsWith(".") ||
                                    afterNl.startsWith("?.")
                                if (!continues) break
                            }
                        }
                        idx++
                    }
                    code.substring(cursor + 1, idx).trim()
                }
                else -> continue
            }
            results.add(Triple(fnName, params, body))
        }
        return results
    }

    private fun collectReachableBodies(
        allMethods: List<Triple<String, String, String>>,
        startName: String,
        startBody: String,
        maxDepth: Int = 3,
    ): List<String> {
        val collected = mutableListOf(startBody)
        val visited = mutableSetOf(startName)
        var frontier = listOf(startBody)
        repeat(maxDepth) {
            if (frontier.isEmpty()) return@repeat
            val nextFrontier = mutableListOf<String>()
            for (currentBody in frontier) {
                for ((candidateName, _, candidateBody) in allMethods) {
                    if (candidateName in visited) continue
                    if (Regex("""\b${Regex.escape(candidateName)}\s*\(""").containsMatchIn(currentBody)) {
                        visited.add(candidateName)
                        collected.add(candidateBody)
                        nextFrontier.add(candidateBody)
                    }
                }
            }
            frontier = nextFrontier
        }
        return collected
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("Unable to locate repo root from ${File(".").canonicalPath}")
        error("unreachable")
    }

    private fun readRepoFile(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("Expected file to exist: $relativePath", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    private fun node(
        id: String,
        name: String,
        port: Int,
        subscriptionId: String? = "sub-1",
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        group: NodeGroup? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = "127.0.0.1",
        port = port,
        settings = settings,
        subscriptionId = subscriptionId,
        group = group,
    )

    private fun sub(id: String, name: String = "Sub $id", enabled: Boolean = true) = Subscription(
        id = id,
        name = name,
        url = "https://example.com/$id",
        enabled = enabled,
    )

    private fun filteredNodes(
        nodes: List<ProxyNode>,
        subscriptions: List<Subscription>,
        latencies: Map<String, LatencyResult> = emptyMap(),
        query: String = "",
        sort: ServerSort = ServerSort.Default,
        selectedProtocols: Set<Protocol> = emptySet(),
        selectedCountries: Set<String> = emptySet(),
    ): List<ProxyNode> = buildGroups(
        nodes = nodes,
        subscriptions = subscriptions,
        latencies = latencies,
        query = query,
        sort = sort,
        selectedProtocols = selectedProtocols,
        selectedCountries = selectedCountries,
        manualGroupTitle = "Manual",
    ).flatMap { it.nodes }

    // ------------------------------------------------------------------ Tier 1: Area A10

    @Test
    fun t1_a10_01_searchQueryRestrictsFilteredNodesAndLoopbackLatencyProbes() = runBlocking {
        val counters = List(5) { LoopbackProbeCounter() }
        try {
            val nodes = listOf(
                node("us", "🇺🇸 US-East", counters[0].port),
                node("de", "🇩🇪 DE-Berlin", counters[1].port),
                node("jp1", "🇯🇵 JP-Tokyo", counters[2].port),
                node("jp2", "🇯🇵 JP-Osaka", counters[3].port),
                node("nl", "🇳🇱 NL-AMS", counters[4].port),
            )
            val matched = filteredNodes(nodes, listOf(sub("sub-1")), query = "jp")
            assertEquals(listOf("jp1", "jp2"), matched.map { it.id })

            val results = LatencyTester().measureAll(matched, PingMode.Tcp)
            assertEquals(listOf("jp1", "jp2"), results.map { it.nodeId })
            assertTrue(counters[2].awaitAcceptAtLeast(1) >= 1)
            assertTrue(counters[3].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals(0, counters[0].acceptCount.get())
            assertEquals(0, counters[1].acceptCount.get())
            assertEquals(0, counters[4].acceptCount.get())
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t1_a10_02_protocolFilterIncludesMatchingNodesAndNodeGroupsOnly() = runBlocking {
        val counters = List(4) { LoopbackProbeCounter() }
        try {
            val vless = node("v1", "🇩🇪 VLESS-1", counters[0].port, settings = ProxySettings.Vless(uuid = "u1"))
            val trojan = node("t1", "🇩🇪 Trojan-1", counters[1].port, settings = ProxySettings.Trojan(password = "p"))
            val grouped = node(
                "g1",
                "🇩🇪 Auto-Group",
                counters[2].port,
                settings = ProxySettings.Hysteria2(password = "p"),
                group = NodeGroup(
                    members = listOf(
                        node("gm1", "M1", counters[3].port, settings = ProxySettings.Vless(uuid = "u2")),
                    ),
                ),
            )
            val matched = filteredNodes(
                listOf(vless, trojan, grouped),
                listOf(sub("sub-1")),
                selectedProtocols = setOf(Protocol.VLESS),
            )
            assertEquals(listOf("v1", "g1"), matched.map { it.id })

            LatencyTester().measureAll(matched, PingMode.Tcp)
            assertTrue(counters[0].awaitAcceptAtLeast(1) >= 1)
            assertTrue("Group member VLESS must be probed", counters[3].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals("Standalone Trojan must receive 0 probes", 0, counters[1].acceptCount.get())
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t1_a10_03_countryFilterIncludesMatchingSubscriptionNodesOnly() = runBlocking {
        val counters = List(4) { LoopbackProbeCounter() }
        try {
            val nodes = listOf(
                node("de", "🇩🇪 DE-1", counters[0].port, subscriptionId = "sub-1"),
                node("nl", "🇳🇱 NL-1", counters[1].port, subscriptionId = "sub-1"),
                node("jp", "🇯🇵 JP-1", counters[2].port, subscriptionId = "sub-1"),
                node("jp-man", "🇯🇵 JP-Manual", counters[3].port, subscriptionId = null),
            )
            val matched = filteredNodes(
                nodes,
                listOf(sub("sub-1")),
                selectedCountries = setOf("DE"),
            )
            assertEquals(listOf("de"), matched.map { it.id })

            LatencyTester().measureAll(matched, PingMode.Tcp)
            assertTrue(counters[0].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals(0, counters[1].acceptCount.get())
            assertEquals(0, counters[2].acceptCount.get())
            assertEquals(0, counters[3].acceptCount.get())
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t1_a10_04_mainViewModelAndServersScreenWireFilteredPingToFab() {
        // Verify M2 F17 contract semantically without over-constraining exact private names AND
        // without allowing an unused stub method on MainViewModel to pass vacuously.
        val mainVmCode = stripKotlinComments(
            readRepoFile("app/src/main/kotlin/com/mydrop/vpn/ui/MainViewModel.kt"),
        )
        val myDropAppCode = stripKotlinComments(
            readRepoFile("app/src/main/kotlin/com/mydrop/vpn/ui/MyDropApp.kt"),
        )
        val serversScreenCode = stripKotlinComments(
            readRepoFile("app/src/main/kotlin/com/mydrop/vpn/ui/screens/servers/ServersScreen.kt"),
        )

        val serversScreenHasLocalFilter = Regex(
            """var\s+(?:query|selectedProtocols|selectedCountries)\s+by\s+remember""",
        ).containsMatchIn(serversScreenCode)

        val fabParamMatch = Regex("""floatingActionButton\s*=\s*\{""").find(myDropAppCode)
        val fabBlock = fabParamMatch?.let {
            val openIdx = it.range.last
            val closeIdx = extractBalancedDelimiter(myDropAppCode, openIdx, '{', '}')
            myDropAppCode.substring(openIdx + 1, closeIdx.coerceAtLeast(openIdx + 1))
        }.orEmpty()
        val serversFabMatch = Regex("""Routes\.SERVERS\s*->""").find(fabBlock)
        val fabWindow = serversFabMatch?.let { m ->
            val tail = fabBlock.substring(m.range.first)
            val nextArm = Regex("""\n\s*(?:Routes\.\w+|else)\s*->""").find(tail, startIndex = m.value.length)
            if (nextArm != null) tail.substring(0, nextArm.range.first) else tail
        }.orEmpty()
        val myDropAppCallsUnfilteredZeroArgPingAll = Regex(
            """viewModel::pingAll\b|viewModel\.pingAll\s*\(\s*\)""",
        ).containsMatchIn(fabWindow)

        val serversComposableMatch = Regex("""composable\s*\(\s*Routes\.SERVERS\s*\)\s*\{""").find(myDropAppCode)
        val serversComposableBlock = serversComposableMatch?.let {
            val openIdx = it.range.last
            val closeIdx = extractBalancedDelimiter(myDropAppCode, openIdx, '{', '}')
            myDropAppCode.substring(openIdx + 1, closeIdx.coerceAtLeast(openIdx + 1))
        }.orEmpty()

        val serversScreenHasFab =
            Regex("""\b(?:Extended)?FloatingActionButton\s*\(""").containsMatchIn(serversScreenCode) ||
                Regex("""(?<!fun\s)\bPingAllButtonContent\s*\(""").containsMatchIn(serversScreenCode)

        val hasParameterizedScreenSweepCall = Regex("""\b(onPing\w*)\s*\(""").findAll(serversScreenCode).any { m ->
            val fnName = m.groupValues[1]
            val openIdx = m.range.last
            val closeIdx = extractBalancedDelimiter(serversScreenCode, openIdx, '(', ')')
            val args = serversScreenCode.substring(openIdx + 1, closeIdx.coerceAtLeast(openIdx + 1)).trim()
            if (args.isBlank()) return@any false
            !(fnName == "onPing" && args == "node.id")
        }

        val hasZeroArgScreenSweepRefOrCall = Regex(
            """\bonPing(?!Node\b)\w+\s*\(\s*\)|onClick\s*=\s*onPing(?!Node\b)\w+\b""",
        ).containsMatchIn(serversScreenCode)

        val serversScreenOwnsFilteredPing = serversScreenHasFab && (
            hasParameterizedScreenSweepCall ||
                (!serversScreenHasLocalFilter && hasZeroArgScreenSweepRefOrCall)
            )

        val allVmMethods = extractFunctionBodies(mainVmCode, Regex("""[A-Za-z_]\w*"""))
        val pingSweepMethods = allVmMethods.filter { (name, _, _) ->
            Regex("""ping(?!Node\b)\w*""").matches(name)
        }
        val publicVmPingMethods = MainViewModel::class.java.declaredMethods.filter { m ->
            Modifier.isPublic(m.modifiers) &&
                !m.isSynthetic &&
                m.name != "pingNode" &&
                m.name.startsWith("ping", ignoreCase = true)
        }

        val uiStateHasFilterOrScope = ServersUiState::class.java.declaredFields.any { f ->
            val n = f.name.lowercase()
            "query" in n || "search" in n || "protocol" in n || "countr" in n || "filter" in n || "sort" in n
        }

        fun isGenuinelyFilteredPingMethod(fnName: String, params: String, body: String): Boolean {
            if (publicVmPingMethods.none { it.name == fnName }) return false
            val reachableBodies = collectReachableBodies(allVmMethods, fnName, body)
            val callsMeasureAll = reachableBodies.any { it.contains("measureAll(") }
            if (!callsMeasureAll) return false
            val ignoresParamWithAllNodes = reachableBodies.any { b ->
                Regex("""val\s+nodes\s*=\s*container\.profiles\.nodes\s*$""", RegexOption.MULTILINE)
                    .containsMatchIn(b)
            }
            val usesScopeParam = params.isNotBlank() && !ignoresParamWithAllNodes
            val filtersInternally = reachableBodies.any { b ->
                b.contains("buildGroups") ||
                    b.contains(".filter") ||
                    b.contains("filtered", ignoreCase = true) ||
                    b.contains("filter", ignoreCase = true)
            }
            return usesScopeParam || filtersInternally
        }

        val filteredSweepMethods = pingSweepMethods.filter { (name, params, body) ->
            isGenuinelyFilteredPingMethod(name, params, body)
        }

        val isWiredEndToEnd = if (myDropAppCallsUnfilteredZeroArgPingAll) {
            // If MyDropApp still triggers 0-arg viewModel::pingAll on Routes.SERVERS, filter state MUST be
            // hoisted out of ServersScreen AND MainViewModel.pingAll() itself MUST filter before measureAll.
            val pingAllZeroArgFiltered = filteredSweepMethods.any { (name, params, _) ->
                name == "pingAll" && params.isBlank()
            }
            !serversScreenHasLocalFilter && uiStateHasFilterOrScope && pingAllZeroArgFiltered
        } else {
            // Otherwise either MyDropApp's Routes.SERVERS FAB or ServersScreen's own FAB delegates to a
            // real filtered sweep method on MainViewModel (never satisfied by an unused stub or pingNode).
            val activeFabRegion = if (serversScreenOwnsFilteredPing) {
                "$fabWindow\n$serversComposableBlock"
            } else {
                fabWindow
            }
            val calledSweepMethodNames = Regex("""viewModel(?:::|\.)(ping(?!Node\b)\w*)""")
                .findAll(activeFabRegion)
                .map { it.groupValues[1] }
                .toSet()
            val activeFabCallsFilteredSweep = calledSweepMethodNames.any { called ->
                filteredSweepMethods.any { (fnName, params, _) ->
                    fnName == called && (!serversScreenHasLocalFilter || params.isNotBlank())
                }
            }
            val hasFabOwner = fabWindow.isNotBlank() || serversScreenOwnsFilteredPing
            hasFabOwner &&
                activeFabCallsFilteredSweep &&
                (!serversScreenHasLocalFilter || serversScreenOwnsFilteredPing)
        }

        if (!isWiredEndToEnd) {
            fail(
                "Expected M2 F17 contract: MainViewModel / MyDropApp / ServersScreen must wire active " +
                    "search/protocol/country filters to the Ping sweep (either hoisted into MainViewModel/" +
                    "ServersUiState or passed from ServersScreen/FAB) so Ping measures ONLY filtered servers",
            )
        }
    }

    @Test
    fun t1_a10_05_tvViewModelPingsOnlyVisibleEnabledSubscriptionNodes() = runBlocking {
        val counters = List(3) { LoopbackProbeCounter() }
        try {
            val sub1 = sub("sub-1", enabled = true)
            val sub2 = sub("sub-2", enabled = false)
            val nA = node("a", "🇩🇪 DE-A", counters[0].port, subscriptionId = sub1.id)
            val nB = node("b", "🇳🇱 NL-B", counters[1].port, subscriptionId = sub1.id)
            val nC = node("c", "🇯🇵 JP-Disabled", counters[2].port, subscriptionId = sub2.id)

            val profileState = ProfileState(
                nodes = listOf(nA, nB, nC),
                subscriptions = listOf(sub1, sub2),
            )
            assertEquals(listOf("a", "b"), profileState.visibleNodes.map { it.id })

            LatencyTester().measureAll(profileState.visibleNodes, PingMode.Tcp)
            assertTrue(counters[0].awaitAcceptAtLeast(1) >= 1)
            assertTrue(counters[1].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals("Disabled subscription node must receive 0 probes", 0, counters[2].acceptCount.get())

            // Verify TvViewModel.pingAll reads container.profiles.nodes (visibleNodes) and does not use allNodes
            val tvVmSrc = stripKotlinComments(
                readRepoFile("tv/src/main/kotlin/com/mydrop/vpn/tv/TvViewModel.kt"),
            )
            assertFalse(tvVmSrc.contains("container.profiles.allNodes"))
        } finally {
            counters.forEach { it.close() }
        }
    }

    // ------------------------------------------------------------------ Tier 2: Area A10

    @Test
    fun t2_a10_01_zeroMatchFilterMakesPingNoOpWithZeroNetworkDials() = runBlocking {
        val counters = List(3) { LoopbackProbeCounter() }
        try {
            val nodes = listOf(
                node("n1", "🇩🇪 DE-1", counters[0].port),
                node("n2", "🇳🇱 NL-1", counters[1].port),
                node("n3", "🇺🇸 US-1", counters[2].port),
            )
            val matched = filteredNodes(nodes, listOf(sub("sub-1")), query = "nonexistent-mars-99")
            assertTrue(matched.isEmpty())

            val results = LatencyTester().measureAll(matched, PingMode.Tcp)
            assertTrue(results.isEmpty())
            Thread.sleep(50)
            counters.forEach { assertEquals(0, it.acceptCount.get()) }
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t2_a10_02_combinedQueryProtocolCountryAndLatencySortFiltersExactSubset() = runBlocking {
        val counters = List(4) { LoopbackProbeCounter() }
        try {
            val nodes = listOf(
                node("de-trojan-fra", "🇩🇪 Frankfurt Trojan", counters[0].port, settings = ProxySettings.Trojan("p")),
                node("de-vless-fra", "🇩🇪 Frankfurt VLESS", counters[1].port, settings = ProxySettings.Vless("u")),
                node("de-trojan-ber", "🇩🇪 Berlin Trojan", counters[2].port, settings = ProxySettings.Trojan("p")),
                node("nl-trojan-ams", "🇳🇱 Amsterdam Trojan", counters[3].port, settings = ProxySettings.Trojan("p")),
            )
            val matched = filteredNodes(
                nodes = nodes,
                subscriptions = listOf(sub("sub-1")),
                query = "Frankfurt",
                sort = ServerSort.Latency,
                selectedProtocols = setOf(Protocol.TROJAN),
                selectedCountries = setOf("DE"),
            )
            assertEquals(listOf("de-trojan-fra"), matched.map { it.id })

            LatencyTester().measureAll(matched, PingMode.Tcp)
            assertTrue(counters[0].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals(0, counters[1].acceptCount.get())
            assertEquals(0, counters[2].acceptCount.get())
            assertEquals(0, counters[3].acceptCount.get())
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t2_a10_03_disabledSubscriptionNodesNeverProbedEvenWhenMatchingFilter() = runBlocking {
        val counters = List(2) { LoopbackProbeCounter() }
        try {
            val s1 = sub("s1", enabled = true)
            val s2 = sub("s2", enabled = false)
            val n1 = node("de1", "🇩🇪 DE-1", counters[0].port, subscriptionId = s1.id)
            val n2 = node("de2", "🇩🇪 DE-2", counters[1].port, subscriptionId = s2.id)
            val state = ProfileState(nodes = listOf(n1, n2), subscriptions = listOf(s1, s2))

            val matched = filteredNodes(
                nodes = state.visibleNodes,
                subscriptions = state.subscriptions,
                query = "DE",
                selectedCountries = setOf("DE"),
            )
            assertEquals(listOf("de1"), matched.map { it.id })
            LatencyTester().measureAll(matched, PingMode.Tcp)
            assertTrue(counters[0].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals(0, counters[1].acceptCount.get())
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t2_a10_04_clearingFiltersRestoresFullVisibleNodesPing() = runBlocking {
        val counters = List(3) { LoopbackProbeCounter() }
        try {
            val nodes = listOf(
                node("jp", "🇯🇵 Tokyo", counters[0].port),
                node("de", "🇩🇪 Berlin", counters[1].port),
                node("nl", "🇳🇱 Amsterdam", counters[2].port),
            )
            val subs = listOf(sub("sub-1"))

            // Step 1: filtered to JP
            val step1 = filteredNodes(nodes, subs, query = "Tokyo")
            LatencyTester().measureAll(step1, PingMode.Tcp)
            assertTrue(counters[0].awaitAcceptAtLeast(1) >= 1)
            Thread.sleep(50)
            assertEquals(0, counters[1].acceptCount.get())
            assertEquals(0, counters[2].acceptCount.get())

            // Step 2: cleared filters -> all 3 pinged
            val step2 = filteredNodes(nodes, subs, query = "")
            assertEquals(3, step2.size)
            LatencyTester().measureAll(step2, PingMode.Tcp)
            assertTrue(counters[1].awaitAcceptAtLeast(1) >= 1)
            assertTrue(counters[2].awaitAcceptAtLeast(1) >= 1)
        } finally {
            counters.forEach { it.close() }
        }
    }

    @Test
    fun t2_a10_05_doubleTapGuardPreservesSingleSweepBehaviorInMainViewModel() {
        val mainVmCode = stripKotlinComments(
            readRepoFile("app/src/main/kotlin/com/mydrop/vpn/ui/MainViewModel.kt"),
        )
        val allVmMethods = extractFunctionBodies(mainVmCode, Regex("""[A-Za-z_]\w*"""))
        val sweepMethods = allVmMethods.filter { (name, _, _) ->
            Regex("""ping(?!Node\b)\w*""").matches(name)
        }
        assertTrue("MainViewModel must define a ping sweep method", sweepMethods.isNotEmpty())

        val hasConcurrencyGuard = sweepMethods.any { (fnName, _, body) ->
            collectReachableBodies(allVmMethods, fnName, body).any { reachableBody ->
                val hasJobGuard = Regex("""\b\w*Job\s*\?\.\s*isActive\s*==\s*true""").containsMatchIn(reachableBody)
                val hasAtomicGuard = reachableBody.contains("compareAndSet(") ||
                    Regex("""if\s*\([^)]*(?:isPinging|pinging|inProgress|running)[^)]*\)\s*return""")
                        .containsMatchIn(reachableBody)
                val hasBusyReturnGuard = Regex("""if\s*\([^)]*(?:isActive|isNotEmpty\(\)|isBusy)[^)]*\)\s*return""")
                    .containsMatchIn(reachableBody)
                hasJobGuard || hasAtomicGuard || hasBusyReturnGuard
            }
        }
        assertTrue(
            "MainViewModel ping sweep must preserve an active Job / AtomicBoolean / busy double-tap guard",
            hasConcurrencyGuard,
        )
        // Ensure F17 filtered ping is also wired
        t1_a10_04_mainViewModelAndServersScreenWireFilteredPingToFab()
    }

    // ------------------------------------------------------------------ Tier 4: Scenario T4-05

    @Test
    fun t4_05_realWorld150ServerFilteredPingWithLoopbackProbeCounters() = runBlocking {
        val targetCounters = List(3) { LoopbackProbeCounter() }
        val nonMatchingCounters = List(5) { LoopbackProbeCounter() }
        try {
            val sEnabled = sub("sub-enabled", enabled = true)
            val sDisabled = sub("sub-disabled", enabled = false)
            val allNodes = buildList {
                // 3 distant JP Tokyo VLESS servers
                for (i in 0 until 3) {
                    add(
                        node(
                            id = "tokyo-$i",
                            name = "🇯🇵 Tokyo #$i",
                            port = targetCounters[i].port,
                            subscriptionId = sEnabled.id,
                            settings = ProxySettings.Vless(uuid = "u-$i"),
                        ),
                    )
                }
                // 5 real-socket non-matching European nodes + 142 additional non-matching servers
                for (i in 0 until 5) {
                    add(
                        node(
                            id = "eu-$i",
                            name = "🇩🇪 Frankfurt #$i",
                            port = nonMatchingCounters[i].port,
                            subscriptionId = sEnabled.id,
                            settings = ProxySettings.Trojan(password = "p-$i"),
                        ),
                    )
                }
                for (i in 0 until 140) {
                    add(
                        node(
                            id = "other-$i",
                            name = "🇺🇸 New York #$i",
                            port = nonMatchingCounters[0].port,
                            subscriptionId = sEnabled.id,
                            settings = ProxySettings.Shadowsocks("aes-256-gcm", "p"),
                        ),
                    )
                }
                // 2 disabled-sub JP Tokyo nodes that must never be visible or pinged
                for (i in 0 until 2) {
                    add(
                        node(
                            id = "disabled-tokyo-$i",
                            name = "🇯🇵 Tokyo Disabled #$i",
                            port = nonMatchingCounters[1].port,
                            subscriptionId = sDisabled.id,
                            settings = ProxySettings.Vless(uuid = "ud-$i"),
                        ),
                    )
                }
            }

            val profileState = ProfileState(nodes = allNodes, subscriptions = listOf(sEnabled, sDisabled))
            val matched = filteredNodes(
                nodes = profileState.visibleNodes,
                subscriptions = profileState.subscriptions,
                query = "Tokyo",
                selectedProtocols = setOf(Protocol.VLESS),
                selectedCountries = setOf("JP"),
            )
            assertEquals(3, matched.size)
            assertEquals(setOf("tokyo-0", "tokyo-1", "tokyo-2"), matched.map { it.id }.toSet())

            val results = LatencyTester().measureAll(matched, PingMode.Tcp)
            assertEquals(3, results.size)
            targetCounters.forEach { assertTrue(it.awaitAcceptAtLeast(1) >= 1) }
            Thread.sleep(50)
            nonMatchingCounters.forEach {
                assertEquals("Non-matching & disabled servers must receive ZERO dials", 0, it.acceptCount.get())
            }

            // Also assert the MainViewModel/MyDropApp/ServersScreen F17 wiring contract
            t1_a10_04_mainViewModelAndServersScreenWireFilteredPingToFab()
        } finally {
            targetCounters.forEach { it.close() }
            nonMatchingCounters.forEach { it.close() }
        }
    }

    // ------------------------------------------------------------------ Area A9 (F12 :app UI Check)

    @Test
    fun t2_a9_05_appSplitTunnelAndSettingsScreensHaveZeroGuardToggle() {
        val splitTunnelScreen = stripKotlinComments(
            readRepoFile("app/src/main/kotlin/com/mydrop/vpn/ui/screens/apps/SplitTunnelScreen.kt"),
        )
        val settingsScreen = stripKotlinComments(
            readRepoFile("app/src/main/kotlin/com/mydrop/vpn/ui/screens/settings/SettingsScreen.kt"),
        )

        assertFalse(
            "SplitTunnelScreen must contain zero Switch/SwitchRow guard toggles (F12)",
            splitTunnelScreen.contains("Switch(") || splitTunnelScreen.contains("SwitchRow("),
        )
        val forbiddenKeywords = listOf(
            "leak",
            "утеч",
            "splitTunnelGuard",
            "split_tunnel_guard",
            "split_tunnel_leak",
            "splitTunnelProtection",
            "disableSplitTunnel",
            "trafficGuardToggle",
        )
        for (kw in forbiddenKeywords) {
            assertFalse("SplitTunnelScreen must not contain '$kw' toggle (F12)", splitTunnelScreen.contains(kw, ignoreCase = true))
            assertFalse("SettingsScreen must not contain '$kw' toggle (F12)", settingsScreen.contains(kw, ignoreCase = true))
        }
    }
}
