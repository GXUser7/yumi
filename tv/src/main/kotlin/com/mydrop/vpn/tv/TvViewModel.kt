package com.mydrop.vpn.tv

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.UpdateState
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.data.AppContainer
import com.mydrop.vpn.data.ConnectOutcome
import com.mydrop.vpn.pairing.PairingReceiver
import com.mydrop.vpn.pairing.PairingReceiverState
import com.mydrop.vpn.pairing.PairingResult
import com.mydrop.vpn.remote.RemoteInvite
import com.mydrop.vpn.remote.RemotePeer
import java.net.URI
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TvUiState(
    val nodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedNode: ProxyNode? = null,
    val latencies: Map<String, LatencyResult> = emptyMap(),
    val vpnState: VpnState = VpnState.Disconnected,
    val traffic: TrafficStats = TrafficStats.Zero,
    val settings: AppSettings = AppSettings(),
)

class TvViewModel(
    private val container: AppContainer,
    private val applicationContext: android.content.Context,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _permissionRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val permissionRequests = _permissionRequests.asSharedFlow()
    private var pendingConnectNodeId: String? = null

    val uiState: StateFlow<TvUiState> = combine(
        container.profiles.state,
        container.settings.settings,
        container.tunnel.state,
        container.tunnel.traffic,
    ) { profiles, settings, vpnState, traffic ->
        TvUiState(
            nodes = profiles.visibleNodes,
            subscriptions = profiles.subscriptions,
            selectedNode = profiles.visibleNodes.firstOrNull { it.id == profiles.selectedNodeId },
            latencies = profiles.latencies,
            vpnState = vpnState,
            traffic = traffic,
            settings = settings,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        TvUiState(
            nodes = container.profiles.nodes,
            subscriptions = container.profiles.state.value.subscriptions,
            selectedNode = container.profiles.selectedNode(),
            latencies = container.profiles.state.value.latencies,
            vpnState = container.tunnel.state.value,
            traffic = container.tunnel.traffic.value,
            settings = container.settings.value,
        ),
    )

    val updates: StateFlow<UpdateState> = container.updates.state

    /**
     * Which subscriptions are being fetched, by id.
     *
     * Straight off the refresher rather than tracked here, so the spinner covers the automatic
     * refreshes too — the one on opening the app is the whole reason there is a spinner at all.
     */
    val refreshing: StateFlow<Set<String>> = container.subscriptionRefresher.running

    private val receiver = PairingReceiver(applicationContext, viewModelScope) { transfer ->
        val subscription = container.profiles.upsertSubscriptionSource(
            name = transfer.name,
            url = transfer.url,
            userAgentOverride = transfer.userAgentOverride,
            headers = transfer.headers,
        )
        container.subscriptionRefresher.refresh(subscription)
        val refreshed = container.profiles.state.value.subscriptions
            .firstOrNull { it.id == subscription.id }
        PairingResult(
            accepted = true,
            status = if (refreshed?.lastError == null) "accepted" else "saved_refresh_failed",
            subscriptionName = refreshed?.remoteTitle ?: subscription.name,
        )
    }
    val pairing: StateFlow<PairingReceiverState> = receiver.state

    /* ── The remote control ──────────────────────────────────────────────────────────────── */

    /**
     * The set's end of the remote lives in the container, not here, so that it outlives this
     * screen — see [com.mydrop.vpn.data.RemoteHost]. What is left for the view model is the part
     * that is genuinely about the screen: the code being shown, and the system dialog.
     */
    private val host = container.remoteHost

    val remoteInvite: StateFlow<RemoteInvite?> =
        host?.invite ?: MutableStateFlow(null)
    val remotePeers: StateFlow<List<RemotePeer>> =
        host?.peers ?: MutableStateFlow(emptyList())

    init {
        if (container.profiles.state.value.subscriptions.isEmpty()) startPairing()
        // A phone pressing "connect" on a set that has never carried a tunnel lands on Android's
        // consent dialog, and only the activity can raise it. Forwarded into the same channel the
        // set's own button uses, so the two paths end in exactly one dialog.
        viewModelScope.launch {
            host?.consentRequests?.collect { _permissionRequests.emit(it) }
        }
    }

    fun startRemotePairing() {
        if (host?.startPairing() == null) {
            _messages.tryEmit(applicationContext.getString(R.string.tv_remote_no_network))
        }
    }

    fun stopRemotePairing() = host?.stopPairing() ?: Unit

    fun forgetRemote(peerId: String) = host?.forget(peerId) ?: Unit

    fun startPairing() = receiver.start("Yumi TV · ${Build.MODEL}")
    fun stopPairing() = receiver.stop()

    fun addManualSubscription(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            _messages.tryEmit(applicationContext.getString(R.string.tv_invalid_url))
            return
        }
        val name = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "TV subscription" }
        val subscription = container.profiles.upsertSubscriptionSource(name, url, null, emptyMap())
        viewModelScope.launch {
            container.subscriptionRefresher.refresh(subscription)
            _messages.emit(applicationContext.getString(R.string.tv_added))
        }
    }

    fun refreshSubscription(subscription: Subscription) {
        viewModelScope.launch { container.subscriptionRefresher.refresh(subscription) }
    }

    fun removeSubscription(subscription: Subscription) = container.profiles.removeSubscription(subscription.id)

    fun setSubscriptionEnabled(subscription: Subscription, enabled: Boolean) =
        container.profiles.setSubscriptionEnabled(subscription.id, enabled)

    fun selectNode(node: ProxyNode) {
        container.profiles.selectNode(node.id)
        if (uiState.value.vpnState.isActive && uiState.value.vpnState.activeNodeId != node.id) {
            container.tunnelLauncher.connectTo(node, "TV server selected")
        }
    }

    fun pingAll() {
        val nodes = container.profiles.nodes
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            container.latencyTester.measureAll(nodes, container.settings.value.pingMode) {
                container.profiles.recordLatency(it)
            }
        }
    }

    fun toggleConnection() {
        if (uiState.value.vpnState.isActive) {
            container.tunnelLauncher.disconnect()
            return
        }
        viewModelScope.launch {
            when (val outcome = container.tunnelLauncher.connect()) {
                is ConnectOutcome.Started -> Unit
                is ConnectOutcome.NeedsConsent -> {
                    pendingConnectNodeId = container.profiles.selectedNode()?.id
                    _permissionRequests.emit(outcome.intent)
                }
                is ConnectOutcome.Rejected -> _messages.emit(outcome.reason)
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        host?.consentResolved(granted)
        // The remote's connect goes through the same dialog and leaves no pending id behind it,
        // so the selected server stands in — which is the one the phone was asking for anyway.
        val node = (pendingConnectNodeId?.let { id -> container.profiles.nodes.firstOrNull { it.id == id } })
            ?: container.profiles.selectedNode()
        pendingConnectNodeId = null
        if (!granted || node == null) {
            _messages.tryEmit(
                applicationContext.getString(
                    if (granted) R.string.tv_no_selected_server else R.string.tv_vpn_permission_denied,
                ),
            )
            return
        }
        container.tunnelLauncher.connectTo(node, "TV VPN consent granted")
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)
    fun checkForUpdate() = container.updates.check(manual = true)
    fun downloadUpdate() = container.updates.download()
    fun installUpdate(context: android.content.Context) = container.updates.install(context)

    /** Puts an offered release away. The scheduler will offer it again on its own clock. */
    fun dismissUpdate() = container.updates.clear()

    override fun onCleared() {
        receiver.stop()
        // The remote host itself keeps running: it belongs to the process, not to this screen.
        host?.stopPairing()
    }

    class Factory(
        private val container: AppContainer,
        private val applicationContext: android.content.Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TvViewModel(container, applicationContext) as T
    }
}
