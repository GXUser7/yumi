package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.SelectionRematch
import com.mydrop.vpn.core.model.StaleSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drops servers that no longer exist out of the lists that name servers by id.
 *
 * Node identity is derived from the endpoint, so a provider that rotates addresses hands back a
 * different set of ids on every refresh. [ProfileRepository.applySubscriptionUpdate] cleans up
 * everything it owns — the selection, the measured latencies — but the failover list lives in
 * settings, a separate store it cannot reach, so ids of servers that were replaced stayed there
 * for good.
 *
 * That is not merely untidy. The list, when it is not empty, *is* the pool the watchdog switches
 * between: `candidates()` keeps only nodes whose id is in it. Filled with ids that match nothing,
 * it leaves the watchdog with nowhere to go — the tunnel sits on a dead server reporting that
 * there is nothing to replace it with, while the subscription is full of working ones — and the
 * settings screen counts the ghosts as a number the user chose.
 *
 * Watching the profile rather than hooking each call site: servers also disappear when a node is
 * deleted by hand, when a subscription is removed, and when one is switched off.
 */
class StaleSelectionPruner(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val logs: LogRepository,
    private val alerts: AlertNotifier,
    private val scope: CoroutineScope,
) {

    /**
     * What every id was last time the profile changed, so a server that moved can be recognised
     * after its id has gone.
     *
     * Held here rather than written to storage because the refresh that loses the ids happens
     * while the app is running, and this collector has already seen the profile as it was — the
     * first value a StateFlow hands over is the one loaded from disk, so even a refresh during
     * startup is preceded by a snapshot. If the process died in between there is nothing to
     * remember and the behaviour falls back to what it was before: the entries are dropped.
     */
    private var nodesBefore: Map<String, ProxyNode> = emptyMap()

    fun start() {
        scope.launch {
            profiles.state
                .map { state -> state.nodes }
                .distinctUntilChanged { old, new -> old.map { it.id } == new.map { it.id } }
                .collect { nodes ->
                    val alive = nodes.map { it.id }.toSet()
                    val previousNodes = nodesBefore
                    nodesBefore = nodes.associateBy { it.id }
                    // Computed inside the update rather than from a snapshot taken before it.
                    // Read first and written after, a list the user was editing in the settings
                    // screen at that moment would be overwritten by the state from before their
                    // edit. The lambda sees whatever is current when it runs, and reruns if that
                    // changed underneath.
                    var applied: StaleSelection.Pruned? = null
                    var followed = 0
                    // One write for both, so a refresh that empties them together never leaves a
                    // moment where one list has gone and the other has not.
                    settings.update { current ->
                        val pruned = StaleSelection.prune(
                            alive = alive,
                            failover = current.failoverNodeIds,
                            mobile = current.mobileNodeIds,
                        )
                        applied = pruned
                        if (pruned == null) {
                            current
                        } else {
                            // Tried before the loss is accepted: an id that vanished because its
                            // server moved should follow the server, and only an id whose server
                            // is really gone should be dropped. Both lists are rematched against
                            // one pool of unclaimed candidates, so the same server cannot be
                            // handed to an ordinary spare and a mobile one at once.
                            val claimed = pruned.failover + pruned.mobile
                            val moves = SelectionRematch.rematch(
                                lost = lostNodes(
                                    current.failoverNodeIds + current.mobileNodeIds,
                                    alive,
                                    previousNodes,
                                ),
                                candidates = nodes,
                                taken = claimed,
                            )
                            followed = moves.size
                            current.copy(
                                failoverNodeIds = pruned.failover + follow(
                                    current.failoverNodeIds, alive, moves,
                                ),
                                mobileNodeIds = pruned.mobile + follow(
                                    current.mobileNodeIds, alive, moves,
                                ),
                            )
                        }
                    }
                    val pruned = applied ?: return@collect
                    if (followed > 0) logs.info(R.string.log_selection_followed, followed)

                    // Emptiness is decided on what was written, not on what the prune found.
                    //
                    // `pruned.failoverEmptied` is computed from the intersection with the surviving
                    // ids — before the rematch runs. When a provider rotates addresses the ids all
                    // change, the rematch follows every server to its new one, the list is saved
                    // intact, and the flag still says it is empty. The user was told their list had
                    // been emptied while the app was busy proving it had not: the line above logs
                    // the rescue two statements earlier.
                    val failoverLeft = settings.value.failoverNodeIds.isNotEmpty()
                    val mobileLeft = settings.value.mobileNodeIds.isNotEmpty()

                    if (pruned.lostFailover > 0) {
                        logs.info(
                            if (pruned.failoverEmptied) {
                                R.string.log_failover_pruned_all
                            } else {
                                R.string.log_failover_pruned
                            },
                            pruned.lostFailover,
                        )
                        // Only when it is gone entirely. Losing two of twenty is housekeeping;
                        // losing the last one changes what the app will do next time.
                        if (pruned.failoverEmptied && !failoverLeft) alerts.listEmptied(mobile = false)
                    }
                    if (pruned.lostMobile > 0) {
                        // Louder than the failover list emptying: that one falling back to the
                        // subscription is a defensible default, while this one falling back means
                        // a feature the user switched on has switched itself off.
                        logs.info(
                            if (pruned.mobileEmptied) {
                                R.string.log_mobile_pruned_all
                            } else {
                                R.string.log_mobile_pruned
                            },
                            pruned.lostMobile,
                        )
                        if (pruned.mobileEmptied && !mobileLeft) alerts.listEmptied(mobile = true)
                    }
                }
        }
    }

    /** The node each vanished id used to be, for the ones still remembered. */
    private fun lostNodes(
        chosen: Set<String>,
        alive: Set<String>,
        previousNodes: Map<String, ProxyNode>,
    ): Map<String, ProxyNode> = chosen
        .asSequence()
        .filterNot { it in alive }
        .mapNotNull { id -> previousNodes[id]?.let { id to it } }
        .toMap()

    /** The replacements found for the vanished ids of one list. */
    private fun follow(
        chosen: Set<String>,
        alive: Set<String>,
        moves: Map<String, String>,
    ): Set<String> = chosen.asSequence()
        .filterNot { it in alive }
        .mapNotNull(moves::get)
        .toSet()
}
