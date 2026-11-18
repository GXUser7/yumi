package com.mydrop.vpn.e2e

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.SplitTunnelRules
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.data.TunnelHealthCheck
import java.io.Closeable
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail

/**
 * Opaque-box contract adapters for M1/M2 symbols that may not yet be landed while E2E tests are
 * compiled against `:shared`.
 *
 * Uses pure `java.lang.reflect` (no `kotlin-reflect` dependency required) and NEVER silently passes
 * when an M2 contract symbol is missing — every missing symbol fails explicitly via `fail(...)`.
 */
internal object E2eContractAdapters {

    const val SPLIT_TUNNEL_POLICY_CLASS = "com.mydrop.vpn.vpn.SplitTunnelUidPolicy"
    const val MYDROP_VPN_SERVICE_CLASS = "com.mydrop.vpn.vpn.MyDropVpnService"
    const val XRAY_CORE_CLASS = "com.mydrop.vpn.vpn.XrayCore"

    val ALWAYS_OUTSIDE_PACKAGES: Set<String> = setOf(
        "com.android.phone",
        "com.shannon.imsservice",
    )

    fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("Unable to locate repository root containing settings.gradle.kts from ${File(".").canonicalPath}")
        error("unreachable")
    }

    fun readRepoFile(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        if (!file.isFile) {
            fail("Expected repository file to exist: $relativePath (${file.absolutePath})")
        }
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Strips Kotlin `//` line comments and nested `/* ... */` block comments while preserving
     * string/char literals (so URLs like `"https://..."` inside strings are untouched).
     */
    fun stripKotlinComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                // Raw triple-quoted string """..."""
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
                // Normal string "..."
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
                // Char literal '...'
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
                // Line comment //
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < n && source[i] != '\n') i++
                }
                // Block comment /* ... */ (supports Kotlin nesting)
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

    /**
     * Handle over M2's `com.mydrop.vpn.vpn.SplitTunnelUidPolicy` contract.
     */
    class SplitTunnelPolicyHandle(
        private val instance: Any,
        private val allowMethod: Method,
    ) {
        fun allowConnection(
            proto: Int,
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
        ): Boolean {
            return try {
                val result = allowMethod.invoke(instance, proto, srcIp, srcPort, dstIp, dstPort)
                result as? Boolean
                    ?: error("SplitTunnelUidPolicy.allowConnection returned non-Boolean: $result")
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }

    /**
     * Instantiates M2's `com.mydrop.vpn.vpn.SplitTunnelUidPolicy` with injectable OS seams.
     */
    fun createSplitTunnelPolicy(
        mode: SplitTunnelMode,
        splitTunnelPackages: Set<String>,
        packageUidResolver: (String) -> Int?,
        sdkInt: Int,
        myUid: Int,
        ownPackageName: String = "com.mydrop.vpn",
        ownerUidResolver: (proto: Int, src: InetSocketAddress, dst: InetSocketAddress) -> Int,
    ): SplitTunnelPolicyHandle {
        val clazz = try {
            Class.forName(SPLIT_TUNNEL_POLICY_CLASS)
        } catch (_: ClassNotFoundException) {
            fail(
                "Expected M2 contract $SPLIT_TUNNEL_POLICY_CLASS to exist " +
                    "(PROJECT.md § Interface Contracts #2, F7/F8/F9)",
            )
            error("unreachable")
        }

        val settings = AppSettings(
            splitTunnelMode = mode,
            splitTunnelPackages = splitTunnelPackages,
        )
        val resolvedPackageUids: Map<String, Int> = splitTunnelPackages
            .mapNotNull { pkg -> packageUidResolver(pkg)?.let { pkg to it } }
            .toMap()
        val rules = SplitTunnelRules(mode = mode, packageUids = resolvedPackageUids)

        val factoryNames = setOf("create", "from", "fromSettings", "resolve", "build")
        val companionInstance = runCatching {
            clazz.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        }.getOrNull()
        val factoryMethods = if (companionInstance != null) {
            companionInstance::class.java.declaredMethods
                .filter {
                    !it.isSynthetic &&
                        it.name.substringBefore('$') in factoryNames
                }
                .sortedByDescending { it.parameterCount }
                .map { companionInstance to it }
        } else {
            emptyList()
        }
        val staticMethods = clazz.declaredMethods
            .filter {
                !it.isSynthetic &&
                    Modifier.isStatic(it.modifiers) &&
                    it.name.substringBefore('$') in factoryNames
            }
            .sortedByDescending { it.parameterCount }
            .map { null to it }

        val constructors = clazz.declaredConstructors
            .filter { c ->
                !c.isSynthetic &&
                    c.parameterTypes.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
            }
            .sortedByDescending { it.parameterCount }
            .map { null to it }

        val candidates: List<Pair<Any?, Executable>> = (factoryMethods + staticMethods + constructors)

        var instance: Any? = null
        for ((receiver, exec) in candidates) {
            val args = bindArguments(
                exec = exec,
                mode = mode,
                settings = settings,
                splitTunnelPackages = splitTunnelPackages,
                rules = rules,
                packageUids = resolvedPackageUids,
                sdkInt = sdkInt,
                myUid = myUid,
                ownPackageName = ownPackageName,
                packageUidResolver = packageUidResolver,
                ownerUidResolver = ownerUidResolver,
            ) ?: continue

            runCatching { exec.isAccessible = true }
            instance = try {
                when (exec) {
                    is Constructor<*> -> exec.newInstance(*args)
                    is Method -> exec.invoke(receiver, *args)
                    else -> null
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
            if (instance != null) break
        }

        if (instance == null) {
            fail(
                "Could not instantiate $SPLIT_TUNNEL_POLICY_CLASS via raw-settings/mode factory or constructor " +
                    "(adapter never pre-filters ownPackageName/ALWAYS_OUTSIDE_THE_TUNNEL). " +
                    "Candidates: ${candidates.joinToString { (_, e) -> e.toGenericString() }}",
            )
        }

        val allowMethod: Method = (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
            .firstOrNull { m ->
                m.name.equals("allowConnection", ignoreCase = true) &&
                    m.parameterCount == 5 &&
                    m.returnType == Boolean::class.javaPrimitiveType
            }?.apply { runCatching { isAccessible = true } }
            ?: failNothing(
                "$SPLIT_TUNNEL_POLICY_CLASS must expose allowConnection(" +
                    "proto: Int, srcIP: ByteArray, srcPort: Int, dstIP: ByteArray, dstPort: Int): Boolean",
            )

        return SplitTunnelPolicyHandle(instance!!, allowMethod)
    }

    private fun failNothing(message: String): Nothing {
        fail(message)
        error(message)
    }

    /**
     * Binds arguments strictly by type and `PROJECT.md § Interface Contracts #2` ordering
     * (`sdkInt`, `myUid`) without relying on `-java-parameters` (`Parameter.isNamePresent == false`
     * in `:shared`) and WITHOUT accepting pre-filtered `Set<Int>` UID sets.
     */
    private fun bindArguments(
        exec: Executable,
        mode: SplitTunnelMode,
        settings: AppSettings,
        splitTunnelPackages: Set<String>,
        rules: SplitTunnelRules,
        packageUids: Map<String, Int>,
        sdkInt: Int,
        myUid: Int,
        ownPackageName: String,
        packageUidResolver: (String) -> Int?,
        ownerUidResolver: (Int, InetSocketAddress, InetSocketAddress) -> Int,
    ): Array<Any?>? {
        val params = exec.parameters
        val values = arrayOfNulls<Any?>(params.size)
        var intOrdinal = 0
        var stringOrdinal = 0
        var setStringOrdinal = 0
        var hasPolicySource = false
        var hasOwnerUidResolver = false

        for (i in params.indices) {
            val p = params[i]
            val pType = p.type

            values[i] = when {
                pType == AppSettings::class.java -> {
                    hasPolicySource = true
                    settings
                }
                pType == SplitTunnelRules::class.java -> {
                    hasPolicySource = true
                    rules
                }
                pType == SplitTunnelMode::class.java -> {
                    hasPolicySource = true
                    mode
                }
                Map::class.java.isAssignableFrom(pType) -> packageUids
                Set::class.java.isAssignableFrom(pType) -> {
                    // Only allow raw splitTunnelPackages: Set<String>; reject pre-filtered Set<Int>
                    val elementType = (p.parameterizedType as? ParameterizedType)
                        ?.actualTypeArguments
                        ?.firstOrNull()
                    if (elementType == String::class.java && setStringOrdinal == 0) {
                        setStringOrdinal++
                        splitTunnelPackages
                    } else {
                        return null
                    }
                }
                pType == Int::class.javaPrimitiveType || pType == Int::class.javaObjectType -> {
                    when (intOrdinal++) {
                        0 -> sdkInt
                        1 -> myUid
                        else -> return null
                    }
                }
                pType == String::class.java -> {
                    if (stringOrdinal++ > 0) return null
                    ownPackageName
                }
                pType == Function1::class.java -> packageUidResolver
                pType == Function3::class.java -> {
                    hasOwnerUidResolver = true
                    ownerUidResolver
                }
                pType.isInterface -> {
                    val samMethod = pType.methods.firstOrNull { Modifier.isAbstract(it.modifiers) }
                    when (samMethod?.parameterCount) {
                        3 -> hasOwnerUidResolver = true
                        1 -> Unit
                        else -> return null
                    }
                    Proxy.newProxyInstance(
                        pType.classLoader,
                        arrayOf(pType),
                    ) { _, _, args ->
                        val actual = args ?: emptyArray()
                        when (actual.size) {
                            3 -> ownerUidResolver(
                                actual[0] as Int,
                                actual[1] as InetSocketAddress,
                                actual[2] as InetSocketAddress,
                            )
                            1 -> packageUidResolver(actual[0] as String)
                            else -> error("Unsupported SAM interface ${pType.name} with ${actual.size} args")
                        }
                    }
                }
                else -> return null
            }
        }
        if (!hasPolicySource || !hasOwnerUidResolver) return null
        return values
    }

    /**
     * Checks `XrayCore` for public M1/M2 `TrafficGuard` and in-process `core.Dial` bridge contracts.
     */
    fun requireXrayCoreTrafficGuardAndDialBridge() {
        val coreClass = Class.forName(XRAY_CORE_CLASS)
        val publicMethods = coreClass.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }

        val hasTrafficGuardMethod = publicMethods.any { m ->
            (m.name == "start" || m.name.equals("startWithGuard", ignoreCase = true)) &&
                m.parameterCount >= 5
        } || publicMethods.any { m ->
            m.name.contains("guard", ignoreCase = true)
        }
        if (!hasTrafficGuardMethod) {
            fail(
                "Expected M1/M2 public contract in $XRAY_CORE_CLASS: start(...) accepting TrafficGuard " +
                    "(PROJECT.md § Interface Contracts #1, F1/F7)",
            )
        }

        val exactBridgeNames = setOf(
            "openHttp",
            "probeHttp",
            "dialTunnel",
            "dialTunnelTls",
            "fetchUrl",
            "fetch",
            "dial",
            "httpGet",
            "request",
        )
        val hasInProcessDialBridge = publicMethods.any { m ->
            exactBridgeNames.any { m.name.equals(it, ignoreCase = true) } ||
                m.name.contains("http", ignoreCase = true) ||
                m.name.contains("probe", ignoreCase = true) ||
                m.name.contains("dial", ignoreCase = true) ||
                m.name.contains("fetch", ignoreCase = true)
        }
        if (!hasInProcessDialBridge) {
            fail(
                "Expected M1/M2 public in-process core.Dial helper on $XRAY_CORE_CLASS " +
                    "(PROJECT.md F5/F10 — OpenHTTP/ProbeHTTP/DialTunnel replacing probe-in loopback SOCKS)",
            )
        }
    }

    /**
     * Invokes `TunnelHealthCheck.passes` / `resolves` reflectively so tests compile cleanly both
     * pre-M2 (`passes(ProbeEndpoint?)`) and post-M2 (if `ProbeEndpoint?` parameter is removed).
     */
    suspend fun invokeHealthCheckPasses(
        healthCheck: TunnelHealthCheck,
        trapProbe: ProbeEndpoint?,
    ): Boolean? = invokeHealthCheckMethod(healthCheck, "passes", trapProbe)

    suspend fun invokeHealthCheckResolves(
        healthCheck: TunnelHealthCheck,
        trapProbe: ProbeEndpoint?,
    ): Boolean? = invokeHealthCheckMethod(healthCheck, "resolves", trapProbe)

    private fun invokeHealthCheckMethod(
        healthCheck: TunnelHealthCheck,
        methodName: String,
        trapProbe: ProbeEndpoint?,
    ): Boolean? {
        val method: Method = TunnelHealthCheck::class.java.methods.firstOrNull { m ->
            m.name == methodName &&
                m.parameterCount in 1..2 &&
                m.parameterTypes.lastOrNull() == Continuation::class.java
        } ?: failNothing("Expected suspend method TunnelHealthCheck.$methodName to exist")

        return try {
            runBlocking {
                val result = suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                    if (method.parameterCount == 1) {
                        method.invoke(healthCheck, cont)
                    } else {
                        method.invoke(healthCheck, trapProbe, cont)
                    }
                }
                result as? Boolean
            }
        } catch (e: InvocationTargetException) {
            val target = e.targetException
            if (isAndroidLogNotMocked(target)) null else throw (target ?: e)
        } catch (e: Throwable) {
            if (isAndroidLogNotMocked(e)) null else throw e
        }
    }

    private fun isAndroidLogNotMocked(t: Throwable?): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message.orEmpty()
            val isLogStub = cur is RuntimeException &&
                (msg.contains("not mocked") || msg.contains("Stub!")) &&
                cur.stackTrace.any { it.className == "android.util.Log" }
            if (isLogStub) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * Invokes `XrayConfigFactory.build` with a `ProbeEndpoint` if that parameter still exists, or
     * without it if M2 removed `ProbeEndpoint` from `XrayConfigFactory.build` altogether.
     */
    fun buildXrayConfigWithOptionalProbe(
        nodes: List<ProxyNode>,
        selected: ProxyNode,
        settings: AppSettings,
        probe: ProbeEndpoint?,
        geoAvailable: Boolean = false,
    ): XrayConfigFactory.Document {
        val buildMethod: Method = XrayConfigFactory::class.java.declaredMethods
            .filter { !it.isSynthetic && it.name == "build" }
            .maxByOrNull { it.parameterCount }
            ?: failNothing("Expected XrayConfigFactory.build to exist")

        val hasProbeParam = buildMethod.parameterTypes.any { it == ProbeEndpoint::class.java }
        if (!hasProbeParam) {
            return XrayConfigFactory.build(
                nodes = nodes,
                selected = selected,
                settings = settings,
                geoAvailable = geoAvailable,
            )
        }

        val args = arrayOfNulls<Any?>(buildMethod.parameterCount)
        for (i in buildMethod.parameterTypes.indices) {
            args[i] = when (buildMethod.parameterTypes[i]) {
                List::class.java -> nodes
                ProxyNode::class.java -> selected
                AppSettings::class.java -> settings
                ProbeEndpoint::class.java -> probe
                String::class.java -> null
                Boolean::class.javaPrimitiveType -> if (i == buildMethod.parameterCount - 1) false else geoAvailable
                Function1::class.java -> { _: String -> true }
                else -> null
            }
        }
        return try {
            buildMethod.invoke(XrayConfigFactory, *args) as XrayConfigFactory.Document
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    /**
     * Real loopback TCP trap listener that records whether any component attempted to dial `127.0.0.1`
     * SOCKS/HTTP `probe-in`.
     */
    class LoopbackTrapServer : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val acceptCount = AtomicInteger(0)
        val receivedHeads = CopyOnWriteArrayList<String>()
        private val running = AtomicBoolean(true)

        private val worker = Thread {
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    acceptCount.incrementAndGet()
                    runCatching {
                        client.soTimeout = 300
                        val buf = ByteArray(256)
                        val n = client.getInputStream().read(buf)
                        if (n > 0) {
                            receivedHeads.add(String(buf, 0, n, Charsets.ISO_8859_1))
                        }
                        client.close()
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                    // Ignore transient accept error during teardown
                }
            }
        }.apply {
            isDaemon = true
            name = "e2e-loopback-trap"
            start()
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            runCatching { worker.join(500) }
        }
    }
}
