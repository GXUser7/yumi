package com.mydrop.vpn.data

import com.mydrop.vpn.R
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

    /**
     * Last attempt per subscription and how many have failed in a row, so a failing one backs off
     * instead of being retried on the same fixed cooldown for the life of the process.
     */
    private data class Attempt(val atMillis: Long, val failures: Int)

    private val attempts = mutableMapOf<String, Attempt>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                runCatching { refreshDue() }
                    .onFailure { logs.warn(R.string.log_auto_update_failed, it.message.orEmpty()) }
            }
        }
    }

    private suspend fun refreshDue() {
        val current = settings.value
        if (!current.subscriptionAutoUpdate) return
        val interval = current.subscriptionUpdateMinutes.takeIf { it > 0 } ?: return

        val subscriptions = profiles.state.value.subscriptions
        // Otherwise the map keeps a row for every subscription the user ever deleted.
        attempts.keys.retainAll(subscriptions.map { it.id }.toSet())

        subscriptions
            .filter { it.enabled && it.isDue(interval) }
            .forEach { subscription ->
                val before = subscription.lastUpdatedEpochMillis
                logs.info(R.string.log_auto_update, refresher.refresh(subscription))

                // The refresher writes a fresh timestamp on success and only an error on failure,
                // so the timestamp is what says which happened.
                val succeeded = profiles.state.value.subscriptions
                    .firstOrNull { it.id == subscription.id }
                    ?.lastUpdatedEpochMillis != before
                val previousFailures = attempts[subscription.id]?.failures ?: 0
                attempts[subscription.id] = Attempt(
                    atMillis = System.currentTimeMillis(),
                    failures = if (succeeded) 0 else previousFailures + 1,
                )
            }
    }

    private fun Subscription.isDue(intervalMinutes: Int): Boolean {
        val now = System.currentTimeMillis()
        // A subscription that has never answered has no timestamp to count from. Treating it as
        // due keeps a first fetch that failed from waiting out the whole interval, and the
        // backoff below is what stops that turning into a retry every ten minutes forever.
        val since = lastUpdatedEpochMillis ?: 0L
        val attempt = attempts[id]
        val cooldown = backoffFor(attempt?.failures ?: 0)
        val quietFor = now - (attempt?.atMillis ?: 0L)
        return now - since >= intervalMinutes * 60_000L && quietFor >= cooldown
    }

    /**
     * Ten minutes, then twenty, forty, and so on to an hour.
     *
     * A provider that is down stays down for longer than one cooldown, and the flat ten-minute
     * retry meant a dead subscription woke the radio six times an hour indefinitely — the removed
     * demo entry did exactly that against `example.com`, and a real subscription whose provider
     * has gone away behaves identically.
     */
    private fun backoffFor(failures: Int): Long =
        if (failures <= 0) {
            RETRY_COOLDOWN_MILLIS
        } else {
            (RETRY_COOLDOWN_MILLIS shl minOf(failures, MAX_BACKOFF_DOUBLINGS))
                .coerceAtMost(MAX_RETRY_COOLDOWN_MILLIS)
        }

    private companion object {
        const val TICK_MILLIS = 60_000L
        const val RETRY_COOLDOWN_MILLIS = 10 * 60_000L
        const val MAX_RETRY_COOLDOWN_MILLIS = 60 * 60_000L
        const val MAX_BACKOFF_DOUBLINGS = 3
    }
}
