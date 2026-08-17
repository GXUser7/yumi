package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Refreshes subscriptions on the cadence chosen in settings.
 *
 * A minute-by-minute check against each subscription's own `lastUpdatedEpochMillis` rather than a
 * timer counting down from app start. The timestamp is persisted, so the schedule survives the
 * process being killed and restarted — a phone that reopens the app four times an hour still gets
 * one refresh per interval, not four.
 *
 * This runs while the app's process does, which for a VPN client is most of the time the tunnel is
 * up. It is deliberately not an `AlarmManager` or a `WorkManager` job: waking a phone to re-read a
 * server list is a poor trade for the battery, and a list that refreshes when the app is next in
 * use is fresh exactly when it is about to be needed.
 */
class SubscriptionScheduler(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val refresher: SubscriptionRefresher,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
) {

    /** Last attempt per subscription, so a failing one is retried on a cooldown, not every tick. */
    private val attempted = mutableMapOf<String, Long>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                runCatching { refreshDue() }
                    .onFailure { logs.warn("Автообновление подписок: ${it.message}") }
            }
        }
    }

    private suspend fun refreshDue() {
        val current = settings.value
        if (!current.subscriptionAutoUpdate) return
        val interval = current.subscriptionUpdateMinutes.takeIf { it > 0 } ?: return

        profiles.state.value.subscriptions
            .filter { it.enabled && it.isDue(interval) }
            .forEach { subscription ->
                attempted[subscription.id] = System.currentTimeMillis()
                logs.info("Автообновление: ${refresher.refresh(subscription)}")
            }
    }

    private fun Subscription.isDue(intervalMinutes: Int): Boolean {
        val now = System.currentTimeMillis()
        // A subscription that has never answered has no timestamp to count from. Treating it as
        // due keeps a first fetch that failed from waiting out the whole interval, and the
        // cooldown below is what stops that turning into a retry every minute.
        val since = lastUpdatedEpochMillis ?: 0L
        val lastAttempt = attempted[id] ?: 0L
        return now - since >= intervalMinutes * 60_000L && now - lastAttempt >= RETRY_COOLDOWN_MILLIS
    }

    private companion object {
        const val TICK_MILLIS = 60_000L
        const val RETRY_COOLDOWN_MILLIS = 10 * 60_000L
    }
}
