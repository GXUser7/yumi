package com.mydrop.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drops servers that no longer exist out of the failover list.
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
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            profiles.state
                .map { state -> state.nodes.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { alive ->
                    // An empty profile is a profile that has not loaded yet as often as it is one
                    // with nothing in it, and pruning against it would wipe a choice the user made.
                    if (alive.isEmpty()) return@collect

                    val chosen = settings.value.failoverNodeIds
                    val kept = chosen intersect alive
                    if (kept.size == chosen.size) return@collect

                    val lost = chosen.size - kept.size
                    settings.update { it.copy(failoverNodeIds = kept) }
                    logs.info(
                        if (kept.isEmpty()) {
                            "Запасные серверы исчезли из подписки ($lost) — выбор снова автоматический"
                        } else {
                            "Из списка запасных убрано серверов, которых больше нет: $lost"
                        },
                    )
                }
        }
    }
}
