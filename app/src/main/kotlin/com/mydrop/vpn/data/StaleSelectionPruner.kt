package com.mydrop.vpn.data

import com.mydrop.vpn.R
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

    fun start() {
        scope.launch {
            profiles.state
                .map { state -> state.nodes.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { alive ->
                    // Computed inside the update rather than from a snapshot taken before it.
                    // Read first and written after, a list the user was editing in the settings
                    // screen at that moment would be overwritten by the state from before their
                    // edit. The lambda sees whatever is current when it runs, and reruns if that
                    // changed underneath.
                    var applied: StaleSelection.Pruned? = null
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
                            current.copy(
                                failoverNodeIds = pruned.failover,
                                mobileNodeIds = pruned.mobile,
                            )
                        }
                    }
                    val pruned = applied ?: return@collect

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
                        if (pruned.failoverEmptied) alerts.listEmptied(mobile = false)
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
                        if (pruned.mobileEmptied) alerts.listEmptied(mobile = true)
                    }
                }
        }
    }
}
