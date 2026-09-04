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
import java.net.URI
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    init {
        if (container.profiles.state.value.subscriptions.isEmpty()) startPairing()
    }

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
        val node = pendingConnectNodeId?.let { id -> container.profiles.nodes.firstOrNull { it.id == id } }
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

    override fun onCleared() {
        receiver.stop()
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
