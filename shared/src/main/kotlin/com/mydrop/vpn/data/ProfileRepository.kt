package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.DnsProfile
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.NodeIdMigration
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUserInfo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ProfileState(
    val nodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedNodeId: String? = null,
    val latencies: Map<String, LatencyResult> = emptyMap(),
    /**
     * Resolvers the user has added. Kept beside the servers because they arrive the same way —
     * pasted, scanned, or found inside a subscription — and are chosen the same way.
     */
    val dnsProfiles: List<DnsProfile> = emptyList(),
    /** Null means the resolver from settings; any other value names one of [dnsProfiles]. */
    val selectedDnsId: String? = null,
) {
    /**
     * Servers a switched-off subscription brought in are still stored, and still hidden.
     *
     * The switch used to mean only "do not refresh this one", which is not what a switch beside a
     * subscription looks like it means: its hundred servers stayed in the list, stayed pickable,
     * and the failover watchdog would still move the tunnel onto one of them. Now the flag hides
     * them everywhere at once — and stored rather than deleted, so switching back is instant and
     * costs no fetch.
     *
     * Servers added by hand carry no [ProxyNode.subscriptionId] and belong to no switch, so they
     * are never hidden.
     */
    val visibleNodes: List<ProxyNode>
        get() {
            val off = subscriptions.asSequence()
                .filterNot { it.enabled }
                .map { it.id }
                .toSet()
            return if (off.isEmpty()) nodes else nodes.filterNot { it.subscriptionId in off }
        }
}

class ProfileRepository(
    filesDir: File,
    scope: CoroutineScope,
    onWriteFailure: (Throwable) -> Unit = {},
) {

    private val store = JsonStore(
        file = File(filesDir, "profiles.json"),
        serializer = ProfileState.serializer(),
        defaultValue = ProfileState(),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    val state: StateFlow<ProfileState> = store.state

    /**
     * What the app works with: everything except the servers of a switched-off subscription.
     *
     * Filtered here rather than at each of the twenty-odd places that ask for the list, because a
     * filter that has to be remembered is a filter somebody will forget — and the one that gets
     * forgotten is the watchdog, which would then move the tunnel onto a server the user has
     * switched off.
     */
    val nodes: List<ProxyNode> get() = store.value.visibleNodes

    /** Everything, switched off included: for storing, merging and migrating ids. */
    val allNodes: List<ProxyNode> get() = store.value.nodes

    /**
     * Old id to new id for this launch, empty once the move has been made.
     *
     * Published because the two lists the user curates live in the settings store, which this class
     * has no business reaching into — see [AppContainer], which applies it there.
     */
    var nodeIdMigration: Map<String, String> = emptyMap()
        private set

    init {
        dropDemoContent()
        migrateNodeIds()

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

    /**
     * Moves every stored server onto the id its contents now imply; see [NodeIdMigration].
     *
     * Runs before anything reads the profile, and writes only when something actually moves.
     */
    private fun migrateNodeIds() {
        val mapping = NodeIdMigration.remap(store.value.nodes)
        if (mapping.isEmpty()) return
        nodeIdMigration = mapping

        store.update { current ->
            val moved = current.nodes.map { node ->
                mapping[node.id]?.let { node.copy(id = it) } ?: node
            }
            current.copy(
                nodes = moved,
                selectedNodeId = current.selectedNodeId?.let { NodeIdMigration.follow(it, mapping) },
                // Rekeyed rather than dropped: these are measurements of servers that have not
                // changed, only of ids that have, and throwing them away would leave the failover
                // choosing blind until a fresh sweep.
                latencies = current.latencies
                    .mapKeys { (id, _) -> NodeIdMigration.follow(id, mapping) }
                    .mapValues { (id, result) -> result.copy(nodeId = id) },
                subscriptions = current.subscriptions.map { subscription ->
                    subscription.copy(nodeIds = NodeIdMigration.follow(subscription.nodeIds, mapping))
                },
            )
        }
    }

    /**
     * Removes the sample set earlier versions seeded on first run.
     *
     * Dropping the seeding code only helps a fresh install. Everyone who already opened one of
     * those builds still carries eight servers under `example.com` that cannot be dialled, plus a
     * subscription pointing at `https://example.com/subscription/demo` — and that subscription is
     * enabled, so the scheduler keeps asking for it every ten minutes for the life of the process
     * and writes a failure to the journal each time. It is matched by id rather than by name,
     * because the name was the user's to change.
     */
    private fun dropDemoContent() {
        if (store.value.subscriptions.none { it.id == DEMO_SUBSCRIPTION_ID }) return
        store.update { current ->
            val remaining = current.nodes.filterNot { it.subscriptionId == DEMO_SUBSCRIPTION_ID }
            current.copy(
                nodes = remaining,
                subscriptions = current.subscriptions.filterNot { it.id == DEMO_SUBSCRIPTION_ID },
                selectedNodeId = current.selectedNodeId
                    ?.takeIf { id -> remaining.any { it.id == id } }
                    ?: remaining.firstOrNull()?.id,
                latencies = current.latencies.filterKeys { id -> remaining.any { it.id == id } },
            )
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
        )
    }

    /**
     * Turns certificate checking off, or back on, for one server.
     *
     * The flag already travelled from share-links into the generated configuration; what it never
     * had was a way in for a server whose link omitted it. Self-signed and expired certificates
     * are ordinary on hand-rolled Hysteria2 and Trojan endpoints, and without this the only fix
     * was to edit the link by hand and re-import.
     *
     * A node with no TLS is left alone: there is no certificate to skip, and writing the flag
     * would put a field in the configuration the core ignores while the list claimed otherwise.
     */
    fun setTlsInsecure(nodeId: String, insecure: Boolean) = store.update { current ->
        current.copy(
            nodes = current.nodes.map { node ->
                val tls = node.tls
                if (node.id != nodeId || tls == null) node
                else node.copy(tls = tls.copy(insecure = insecure))
            },
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
        current.copy(subscriptions = current.subscriptions + subscription)
    }

    /**
     * Adds a subscription received from another installation, or updates its source credentials
     * without duplicating the URL. Runtime metadata remains local to this device and is refreshed
     * immediately by the caller.
     */
    fun upsertSubscriptionSource(
        name: String,
        url: String,
        userAgentOverride: String?,
        headers: Map<String, String>,
    ): Subscription {
        val existing = store.value.subscriptions.firstOrNull { it.url == url }
        val imported = existing?.copy(
            name = name,
            userAgentOverride = userAgentOverride,
            headers = headers,
        ) ?: Subscription(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            userAgentOverride = userAgentOverride,
            headers = headers,
        )
        store.update { current ->
            current.copy(
                subscriptions = if (existing == null) {
                    current.subscriptions + imported
                } else {
                    current.subscriptions.map { if (it.id == existing.id) imported else it }
                },
            )
        }
        return imported
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
                selectedNodeId = survivingSelection(current, nodes),
                latencies = current.latencies.filterKeys { id -> nodes.any { it.id == id } },
            )
        }
        return added to removed
    }

    /**
     * What the selection should be after a refresh.
     *
     * An id is a hash of the endpoint and the credentials, deliberately not the name — providers
     * rename constantly, and a name-sensitive id would orphan the selection every refresh. The
     * cost of that choice shows up here: a provider that rotates an address, a port or a key hands
     * back the *same* server under a *different* id, and the old one looks deleted.
     *
     * The naive answer to that — fall back to the first node in the list — was caught in a field
     * journal doing real harm. A working tunnel on Belgium survived a routine auto-refresh by
     * being moved to France, which was dead; twenty seconds of no internet followed, and neither
     * the switch nor its reason was anything the user had asked for or could see.
     *
     * So the name is used as a continuity key when the id fails, within the same subscription.
     * It is a weaker key than the id and can miss — a provider that renames *and* re-addresses in
     * the same refresh gets the old behaviour — but it cannot pick the wrong server, only fail to
     * find the right one.
     */
    private fun survivingSelection(current: ProfileState, nodes: List<ProxyNode>): String? {
        val selectedId = current.selectedNodeId ?: return nodes.firstOrNull()?.id
        if (nodes.any { it.id == selectedId }) return selectedId

        val was = current.nodes.firstOrNull { it.id == selectedId }
        val sameName = was?.let { previous ->
            nodes.firstOrNull {
                it.subscriptionId == previous.subscriptionId && it.name == previous.name
            }
        }
        return sameName?.id ?: nodes.firstOrNull()?.id
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

    private companion object {
        /** Id the removed sample set was written under; see [dropDemoContent]. */
        const val DEMO_SUBSCRIPTION_ID = "demo-subscription"
    }
}

/**
 * The node list must never hold two servers with the same id — every write path goes through
 * this. The id keys the list, the selection and the latency map at once, so a duplicate is not a
 * cosmetic problem: `LazyColumn` throws outright the moment a repeated key gets composed.
 */
private fun List<ProxyNode>.distinctById(): List<ProxyNode> = distinctBy { it.id }
