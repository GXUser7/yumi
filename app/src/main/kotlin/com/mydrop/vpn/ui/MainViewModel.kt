package com.mydrop.vpn.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrop.vpn.core.format.pluralServers
import com.mydrop.vpn.core.model.AddKind
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.DnsProfile
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SpeedPhase
import com.mydrop.vpn.core.model.SpeedTestState
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.parse.DnsUriParser
import com.mydrop.vpn.core.parse.ProxyUriParser
import com.mydrop.vpn.core.parse.DeepLinkParser
import com.mydrop.vpn.core.parse.DeepLinkPayload
import com.mydrop.vpn.data.AppContainer
import com.mydrop.vpn.data.ConnectOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class MainUiState(
    val nodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedNode: ProxyNode? = null,
    val latencies: Map<String, LatencyResult> = emptyMap(),
    val vpnState: VpnState = VpnState.Disconnected,
    val traffic: TrafficStats = TrafficStats.Zero,
    val settings: AppSettings = AppSettings(),
    val dnsProfiles: List<DnsProfile> = emptyList(),
    /** Null means the resolver typed into settings is the one in use. */
    val selectedDnsId: String? = null,
    val pingingNodeIds: Set<String> = emptySet(),
    val refreshingSubscriptionIds: Set<String> = emptySet(),
    val tunnelIsSimulated: Boolean = true,
) {
    val isBusy: Boolean get() = pingingNodeIds.isNotEmpty() || refreshingSubscriptionIds.isNotEmpty()

    /** Servers not attached to any subscription, shown as a synthetic "added manually" group. */
    fun manualNodes(): List<ProxyNode> = nodes.filter { node ->
        node.subscriptionId == null || subscriptions.none { it.id == node.subscriptionId }
    }
}

private data class TransientState(
    val pingingNodeIds: Set<String> = emptySet(),
    val refreshingSubscriptionIds: Set<String> = emptySet(),
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _permissionRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val permissionRequests = _permissionRequests.asSharedFlow()

    private var pendingConnectNodeId: String? = null

    val logs: StateFlow<List<LogEntry>> = container.logs.entries

    val uiState: StateFlow<MainUiState> = combine(
        container.profiles.state,
        container.settings.settings,
        container.tunnel.state,
        container.tunnel.traffic,
        transient,
    ) { profiles, settings, vpnState, traffic, transient ->
        MainUiState(
            nodes = profiles.nodes,
            subscriptions = profiles.subscriptions,
            selectedNode = profiles.nodes.firstOrNull { it.id == profiles.selectedNodeId },
            latencies = profiles.latencies,
            vpnState = vpnState,
            traffic = traffic,
            settings = settings,
            dnsProfiles = profiles.dnsProfiles,
            selectedDnsId = profiles.selectedDnsId,
            pingingNodeIds = transient.pingingNodeIds,
            refreshingSubscriptionIds = transient.refreshingSubscriptionIds,
            tunnelIsSimulated = !container.tunnel.isReal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    // --------------------------------------------------------- Speed test

    private val _speedTest = MutableStateFlow(SpeedTestState())
    val speedTest: StateFlow<SpeedTestState> = _speedTest.asStateFlow()

    private var speedTestJob: Job? = null

    /**
     * Measures through the core when a tunnel is up, and straight out when it is not.
     *
     * The probe endpoint is only meaningful while the core it was generated for is running, so a
     * tunnel that is merely connecting — or one whose configuration could not claim a loopback
     * port — measures the phone's own connection and the screen says so.
     */
    fun startSpeedTest() {
        if (speedTestJob?.isActive == true) return
        val connected = uiState.value.vpnState is VpnState.Connected
        val probe = if (connected) container.tunnelConfigs.probe.value else null
        val serverName = uiState.value.selectedNode?.name

        speedTestJob = viewModelScope.launch {
            // The traces are kept here rather than in the tester: the tester reports one sample at
            // a time, and how much history a chart wants is a question about the screen.
            val current = mutableListOf<Long>()
            var downloadTrace = emptyList<Long>()
            var uploadTrace = emptyList<Long>()
            var tracedPhase: SpeedPhase? = null

            container.speedTester.measure(probe, serverName).collect { measured ->
                if (measured.phase != tracedPhase) {
                    // The phase that just ended keeps its trace; the results panel draws it after
                    // the run, so it has to outlive the phase that produced it.
                    when (tracedPhase) {
                        SpeedPhase.Download -> downloadTrace = current.toList()
                        SpeedPhase.Upload -> uploadTrace = current.toList()
                        else -> Unit
                    }
                    current.clear()
                    tracedPhase = measured.phase
                }
                if (measured.running) {
                    current += measured.liveBytesPerSecond
                    while (current.size > TRACE_SAMPLES) current.removeAt(0)
                }

                _speedTest.value = measured.copy(
                    series = current.toList(),
                    downloadSeries = if (measured.phase == SpeedPhase.Download) {
                        current.toList()
                    } else {
                        downloadTrace
                    },
                    uploadSeries = if (measured.phase == SpeedPhase.Upload) {
                        current.toList()
                    } else {
                        uploadTrace
                    },
                )
            }
        }
    }

    fun stopSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
        // Whatever the run managed to measure stays on screen; only the phase goes back to rest.
        _speedTest.update {
            it.copy(phase = SpeedPhase.Idle, liveBytesPerSecond = 0, phaseProgress = 0f)
        }
    }

    // ------------------------------------------------------------- Tunnel

    fun toggleConnection() {
        if (uiState.value.vpnState.isActive) {
            container.tunnelLauncher.disconnect()
            return
        }
        viewModelScope.launch {
            when (val outcome = container.tunnelLauncher.connect()) {
                is ConnectOutcome.Started -> Unit

                // VPN consent has to be granted through an Activity, so the intent is surfaced to
                // the UI and the connection is retried once the system dialog comes back.
                is ConnectOutcome.NeedsConsent -> {
                    pendingConnectNodeId = container.profiles.selectedNode()?.id
                    _permissionRequests.tryEmit(outcome.intent)
                }

                is ConnectOutcome.Rejected -> emit(outcome.reason)
            }
        }
    }

    /** Called by the Activity once the system VPN consent dialog has been answered. */
    fun onVpnPermissionResult(granted: Boolean) {
        val nodeId = pendingConnectNodeId
        pendingConnectNodeId = null
        if (!granted) {
            emit("Без разрешения VPN туннель не поднять")
            return
        }
        val node = container.profiles.nodes.firstOrNull { it.id == nodeId } ?: return
        container.tunnelLauncher.connectTo(node)
    }

    fun selectNode(nodeId: String) {
        container.profiles.selectNode(nodeId)
        // Switching servers while up should land the user on the new one, not silently keep
        // routing through the old tunnel. Re-dialling the server that is already carrying the
        // traffic is the one case worth skipping: it would drop every open connection to arrive
        // exactly where it started.
        val state = uiState.value.vpnState
        if (!state.isActive || state.activeNodeId == nodeId) return
        container.profiles.nodes
            .firstOrNull { it.id == nodeId }
            ?.let(container.tunnelLauncher::connectTo)
    }

    /**
     * Routing mode is baked into the generated configuration, so on a live tunnel it means nothing
     * until the core is handed a new one. The chips sit on the statistics panel of a running
     * tunnel, where a control that quietly does nothing until the next reconnect reads as broken —
     * and now that switching servers reloads the core in place, applying it costs the same.
     */
    fun setRoutingMode(mode: RoutingMode) {
        if (uiState.value.settings.routingMode == mode) return
        container.settings.update { it.copy(routingMode = mode) }

        if (!uiState.value.vpnState.isActive) return
        val node = container.profiles.selectedNode() ?: return
        container.tunnelLauncher.connectTo(node)
        emit("Режим «${mode.label}» применён")
    }

    /**
     * Flips certificate checking for one server, and rebuilds the tunnel when it is the one
     * carrying traffic — the core was handed the old flag at startup and will not reread it.
     */
    fun setTlsInsecure(nodeId: String, insecure: Boolean) {
        val node = container.profiles.nodes.firstOrNull { it.id == nodeId } ?: return
        val tls = node.tls ?: return
        if (tls.insecure == insecure) return

        container.profiles.setTlsInsecure(nodeId, insecure)
        emit(
            if (insecure) {
                "«${node.name}»: сертификат не проверяется"
            } else {
                "«${node.name}»: проверка сертификата включена"
            },
        )

        if (!uiState.value.vpnState.isActive) return
        if (container.profiles.selectedNode()?.id != nodeId) return
        container.tunnelLauncher.connectTo(node.copy(tls = tls.copy(insecure = insecure)))
    }

    /**
     * Applied to a running tunnel, not merely stored.
     *
     * Both halves of this switch — the resolver the game is pointed at and the rule sending its
     * traffic around the proxy — live in the document the core was handed at startup. Storing the
     * flag and leaving the core alone would leave a switch reading "on" while nothing about the
     * game had changed, which is the same quiet lie the resolver picker used to tell.
     */
    fun setBrawlStarsMode(enabled: Boolean) {
        if (uiState.value.settings.brawlStarsMode == enabled) return
        container.settings.update { it.copy(brawlStarsMode = enabled) }

        val active = uiState.value.vpnState.isActive
        emit(
            when {
                !enabled -> "Brawl Stars: разблокировка выключена"
                // The switch does nothing on its own — it only shapes a tunnel that is running.
                !active -> "Brawl Stars: заработает при подключении"
                else -> "Brawl Stars: разблокировка включена"
            },
        )

        if (!active) return
        val node = container.profiles.selectedNode() ?: return
        container.tunnelLauncher.connectTo(node)
    }

    // ------------------------------------------------------------ Latency

    fun pingNode(nodeId: String) {
        val node = container.profiles.nodes.firstOrNull { it.id == nodeId } ?: return
        viewModelScope.launch {
            transient.update { it.copy(pingingNodeIds = it.pingingNodeIds + nodeId) }
            val result = container.latencyTester.measure(node, container.settings.value.pingMode)
            container.profiles.recordLatency(result)
            transient.update { it.copy(pingingNodeIds = it.pingingNodeIds - nodeId) }
        }
    }

    fun pingAll() {
        val nodes = container.profiles.nodes
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            transient.update { it.copy(pingingNodeIds = nodes.map { n -> n.id }.toSet()) }
            container.latencyTester.measureAll(nodes, container.settings.value.pingMode) { result ->
                container.profiles.recordLatency(result)
                transient.update { it.copy(pingingNodeIds = it.pingingNodeIds - result.nodeId) }
            }
            transient.update { it.copy(pingingNodeIds = emptySet()) }
            emit("Проверка задержек завершена")
        }
    }

    fun selectFastest() {
        val latencies = uiState.value.latencies
        val fastest = uiState.value.nodes
            .mapNotNull { node -> latencies[node.id]?.takeIf { !it.failed }?.let { node to it.millis } }
            .minByOrNull { it.second }
            ?.first
        if (fastest == null) {
            emit("Сначала измерьте задержки")
            return
        }
        selectNode(fastest.id)
        emit("Выбран ${fastest.name}")
    }

    // ------------------------------------------------------- Subscriptions

    fun refreshSubscription(subscriptionId: String) {
        val subscription = uiState.value.subscriptions.firstOrNull { it.id == subscriptionId }
            ?: return
        viewModelScope.launch {
            transient.update {
                it.copy(refreshingSubscriptionIds = it.refreshingSubscriptionIds + subscriptionId)
            }
            emit(container.subscriptionRefresher.refresh(subscription))

            transient.update {
                it.copy(refreshingSubscriptionIds = it.refreshingSubscriptionIds - subscriptionId)
            }
        }
    }

    fun refreshAllSubscriptions() {
        uiState.value.subscriptions.filter { it.enabled }.forEach { refreshSubscription(it.id) }
    }

    fun addSubscription(url: String, name: String?) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        if (uiState.value.subscriptions.any { it.url == trimmed }) {
            emit("Такая подписка уже добавлена")
            return
        }
        val subscription = Subscription(
            id = UUID.randomUUID().toString(),
            name = name?.takeIf(String::isNotBlank) ?: guessName(trimmed),
            url = trimmed,
        )
        container.profiles.addSubscription(subscription)
        refreshSubscription(subscription.id)
    }

    fun removeSubscription(subscriptionId: String) {
        container.profiles.removeSubscription(subscriptionId)
        emit("Подписка удалена")
    }

    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) =
        container.profiles.setSubscriptionEnabled(subscriptionId, enabled)

    // ------------------------------------------------------------ Import

    /**
     * The add sheet's entry point, where the user may have said what they are pasting.
     *
     * [AddKind.Auto] falls through to the same detection QR scans and shared text use; the other
     * kinds skip it. Naming a kind and getting silence back would be worse than the guessing it
     * replaced, so each branch says plainly when the text is not what it was called.
     */
    fun addFromText(raw: String, name: String?, kind: AddKind) {
        val text = raw.trim()
        if (text.isEmpty()) return

        when (kind) {
            AddKind.Auto -> importText(text)

            AddKind.Subscription -> {
                if (!text.startsWith("http", ignoreCase = true)) {
                    emit("Подписка — это ссылка http:// или https://")
                    return
                }
                addSubscription(text, name)
            }

            AddKind.Server -> {
                val nodes = ProxyUriParser.parseAll(text)
                if (nodes.isEmpty()) {
                    emit("Это не похоже на сервер — ссылка не разобралась")
                    return
                }
                container.profiles.addNodes(nodes)
                container.logs.info("Импортировано серверов: ${nodes.size}")
                emit("Добавлено серверов: ${nodes.size}")
            }

            AddKind.Dns -> {
                // Named explicitly, so the path convention that keeps a subscription from being
                // read as a resolver is not applied here — the user already answered that.
                val profiles = DnsUriParser.parseAll(text).ifEmpty {
                    listOfNotNull(DnsUriParser.parse(text) ?: dnsFromPlainUrl(text, name))
                }
                if (profiles.isEmpty()) {
                    emit("Это не похоже на адрес DNS")
                    return
                }
                container.profiles.addDnsProfiles(profiles)
                container.logs.info("Импортировано DNS: ${profiles.size}")
                emit("DNS добавлен: ${profiles.first().name}")
            }
        }
    }

    /** An https resolver whose path is not the well-known one, taken at the user's word. */
    private fun dnsFromPlainUrl(text: String, name: String?): DnsProfile? {
        if (!text.startsWith("https://", ignoreCase = true) &&
            !text.startsWith("h3://", ignoreCase = true)
        ) {
            return null
        }
        return DnsProfile(
            id = DnsProfile.stableId(text),
            name = name ?: text.substringAfter("://").substringBefore('/'),
            url = text,
        )
    }

    /** Single entry point for QR scans, pasted text, shared text and `happ://` deep links. */
    fun importText(raw: String) {
        when (val payload = DeepLinkParser.parse(raw)) {
            is DeepLinkPayload.AddSubscription -> addSubscription(payload.url, payload.name)

            is DeepLinkPayload.AddNodes -> {
                container.profiles.addNodes(payload.nodes)
                container.logs.info("Импортировано серверов: ${payload.nodes.size}")
                emit("Добавлено серверов: ${payload.nodes.size}")
            }

            is DeepLinkPayload.AddDns -> {
                container.profiles.addDnsProfiles(payload.profiles)
                container.logs.info("Импортировано DNS: ${payload.profiles.size}")
                emit(
                    if (payload.profiles.size == 1) {
                        "DNS добавлен: ${payload.profiles.single().name}"
                    } else {
                        "Добавлено DNS: ${payload.profiles.size}"
                    },
                )
            }

            is DeepLinkPayload.Unsupported -> emit(payload.reason)
        }
    }

    fun removeNode(nodeId: String) = container.profiles.removeNode(nodeId)

    // ---------------------------------------------------------- Settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) =
        container.settings.update(transform)

    /**
     * Null puts the resolver from settings back in charge.
     *
     * Applied to a running tunnel, not just stored. The resolver is baked into the configuration
     * the core is holding, so picking another one and leaving the core alone would show a chosen
     * resolver that nothing resolves through — the same quiet lie the routing chips used to tell
     * before they reloaded. Reloading in place costs about a second and no consent dialog.
     */
    fun selectDns(id: String?) {
        if (uiState.value.selectedDnsId == id) return
        container.profiles.selectDnsProfile(id)
        applyDnsToRunningTunnel(container.profiles.selectedDnsProfile()?.name ?: "из настроек")
    }

    fun removeDns(id: String) {
        // Removing the resolver in use falls back to the settings field, which is a change to the
        // running configuration exactly like selecting another one.
        val wasSelected = uiState.value.selectedDnsId == id
        container.profiles.removeDnsProfile(id)
        if (wasSelected) applyDnsToRunningTunnel("из настроек")
    }

    private fun applyDnsToRunningTunnel(name: String) {
        if (!uiState.value.vpnState.isActive) return
        val node = container.profiles.selectedNode() ?: return
        container.tunnelLauncher.connectTo(node)
        emit("DNS применён: $name")
    }

    fun clearLogs() = container.logs.clear()

    // ----------------------------------------------------------- Helpers

    private fun guessName(url: String): String =
        runCatching { java.net.URL(url).host }.getOrNull()?.removePrefix("www.") ?: "Подписка"

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private companion object {
        /** One phase's worth of 150 ms samples, which is exactly the window the trace shows. */
        const val TRACE_SAMPLES = 60
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(container) as T
    }
}
