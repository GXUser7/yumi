package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import com.mydrop.vpn.core.model.DnsProfile
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ProfileState(
    val nodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedNodeId: String? = null,
    val latencies: Map<String, LatencyResult> = emptyMap(),
    val seeded: Boolean = false,
    /**
     * Resolvers the user has added. Kept beside the servers because they arrive the same way —
     * pasted, scanned, or found inside a subscription — and are chosen the same way.
     */
    val dnsProfiles: List<DnsProfile> = emptyList(),
    /** Null means the resolver from settings; any other value names one of [dnsProfiles]. */
    val selectedDnsId: String? = null,
)

class ProfileRepository(filesDir: File, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(filesDir, "profiles.json"),
        serializer = ProfileState.serializer(),
        defaultValue = ProfileState(),
        scope = scope,
    )

    val state: StateFlow<ProfileState> = store.state

    val nodes: List<ProxyNode> get() = store.value.nodes

    init {
        // Repair on load, not just on write. Duplicate ids reached the disk before ids were
        // scoped by subscription, and a profile written by that build still crashes the servers
        // list on the first scroll — the write-path guard alone would only heal it at the next
        // subscription refresh, which the user cannot reach without scrolling there first.
        val stored = store.value.nodes
        if (stored.size != stored.distinctBy { it.id }.size) {
            store.update { current ->
                val repaired = current.nodes.distinctById()
                current.copy(
                    nodes = repaired,
                    selectedNodeId = current.selectedNodeId
                        ?.takeIf { id -> repaired.any { it.id == id } }
                        ?: repaired.firstOrNull()?.id,
                    latencies = current.latencies.filterKeys { id -> repaired.any { it.id == id } },
                )
            }
        }
    }

    fun selectNode(nodeId: String) = store.update { it.copy(selectedNodeId = nodeId) }

    fun selectedNode(): ProxyNode? = store.value.let { s ->
        s.nodes.firstOrNull { it.id == s.selectedNodeId }
    }

    fun addNodes(newNodes: List<ProxyNode>) = store.update { current ->
        // Re-adding a known server refreshes its metadata rather than duplicating the row.
        val merged = current.nodes.toMutableList()
        newNodes.forEach { node ->
            val index = merged.indexOfFirst { it.id == node.id }
            if (index >= 0) merged[index] = node else merged += node
        }
        current.copy(
            nodes = merged.distinctById(),
            selectedNodeId = current.selectedNodeId ?: newNodes.firstOrNull()?.id,
            seeded = true,
        )
    }

    fun removeNode(nodeId: String) = store.update { current ->
        val remaining = current.nodes.filterNot { it.id == nodeId }
        current.copy(
            nodes = remaining,
            selectedNodeId = if (current.selectedNodeId == nodeId) {
                remaining.firstOrNull()?.id
            } else {
                current.selectedNodeId
            },
            latencies = current.latencies - nodeId,
        )
    }

    fun addSubscription(subscription: Subscription) = store.update { current ->
        current.copy(subscriptions = current.subscriptions + subscription, seeded = true)
    }

    fun removeSubscription(subscriptionId: String) = store.update { current ->
        val remaining = current.nodes.filterNot { it.subscriptionId == subscriptionId }
        current.copy(
            subscriptions = current.subscriptions.filterNot { it.id == subscriptionId },
            nodes = remaining,
            selectedNodeId = current.selectedNodeId?.takeIf { id -> remaining.any { it.id == id } }
                ?: remaining.firstOrNull()?.id,
        )
    }

    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) = store.update { current ->
        current.copy(
            subscriptions = current.subscriptions.map {
                if (it.id == subscriptionId) it.copy(enabled = enabled) else it
            },
        )
    }

    /**
     * Replaces a subscription's servers with a freshly fetched set. Nodes that survive keep
     * their identity — and therefore the user's selection and measured latency — because ids
     * are derived from the endpoint rather than from list position.
     */
    fun applySubscriptionUpdate(
        subscriptionId: String,
        fetchedNodes: List<ProxyNode>,
        userInfo: SubscriptionUserInfo?,
        remoteTitle: String?,
        webPageUrl: String?,
    ): Pair<Int, Int> {
        var added = 0
        var removed = 0
        store.update { current ->
            // Providers routinely list the same endpoint twice — a duplicate row, or a second
            // label for the same server. Those collapse into one node here rather than in the
            // list, because a repeated id would also make the selection and the latency map
            // ambiguous, not just break LazyColumn's keys.
            val fetched = fetchedNodes.distinctById()

            val previous = current.nodes.filter { it.subscriptionId == subscriptionId }
            val previousIds = previous.map { it.id }.toSet()
            val fetchedIds = fetched.map { it.id }.toSet()
            added = (fetchedIds - previousIds).size
            removed = (previousIds - fetchedIds).size

            val others = current.nodes.filterNot { it.subscriptionId == subscriptionId }
            val nodes = (others + fetched).distinctById()

            current.copy(
                nodes = nodes,
                subscriptions = current.subscriptions.map { sub ->
                    if (sub.id != subscriptionId) {
                        sub
                    } else {
                        sub.copy(
                            nodeIds = fetched.map { it.id },
                            userInfo = userInfo ?: sub.userInfo,
                            remoteTitle = remoteTitle ?: sub.remoteTitle,
                            webPageUrl = webPageUrl ?: sub.webPageUrl,
                            lastUpdatedEpochMillis = System.currentTimeMillis(),
                            lastError = null,
                        )
                    }
                },
                selectedNodeId = current.selectedNodeId?.takeIf { id -> nodes.any { it.id == id } }
                    ?: nodes.firstOrNull()?.id,
                latencies = current.latencies.filterKeys { id -> nodes.any { it.id == id } },
                seeded = true,
            )
        }
        return added to removed
    }

    fun recordSubscriptionError(subscriptionId: String, message: String) = store.update { current ->
        current.copy(
            subscriptions = current.subscriptions.map {
                if (it.id == subscriptionId) it.copy(lastError = message) else it
            },
        )
    }

    fun recordLatency(result: LatencyResult) = store.update { current ->
        current.copy(latencies = current.latencies + (result.nodeId to result))
    }

    fun recordLatencies(results: List<LatencyResult>) = store.update { current ->
        current.copy(latencies = current.latencies + results.associateBy { it.nodeId })
    }

    fun clearLatencies() = store.update { it.copy(latencies = emptyMap()) }

    fun markSeeded() = store.update { it.copy(seeded = true) }

    // ------------------------------------------------------------------ DNS

    fun addDnsProfiles(profiles: List<DnsProfile>) = store.update { current ->
        val merged = current.dnsProfiles.toMutableList()
        profiles.forEach { profile ->
            val index = merged.indexOfFirst { it.id == profile.id }
            if (index >= 0) merged[index] = profile else merged += profile
        }
        // The first resolver added becomes the one in use: adding one and then having to hunt for
        // where to switch it on is a step nobody asked for.
        current.copy(
            dnsProfiles = merged,
            selectedDnsId = current.selectedDnsId ?: profiles.firstOrNull()?.id,
        )
    }

    fun removeDnsProfile(id: String) = store.update { current ->
        current.copy(
            dnsProfiles = current.dnsProfiles.filterNot { it.id == id },
            selectedDnsId = current.selectedDnsId?.takeIf { it != id },
        )
    }

    /** Null switches back to the resolver configured in settings. */
    fun selectDnsProfile(id: String?) = store.update { it.copy(selectedDnsId = id) }

    fun selectedDnsProfile(): DnsProfile? = store.value.let { state ->
        state.dnsProfiles.firstOrNull { it.id == state.selectedDnsId }
    }
}

/**
 * The node list must never hold two servers with the same id — every write path goes through
 * this. The id keys the list, the selection and the latency map at once, so a duplicate is not a
 * cosmetic problem: `LazyColumn` throws outright the moment a repeated key gets composed.
 */
private fun List<ProxyNode>.distinctById(): List<ProxyNode> = distinctBy { it.id }
