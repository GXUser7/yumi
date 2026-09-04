package com.mydrop.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Asks GitHub whether there is a new version, twice a day.
 *
 * Built the same way as [SubscriptionScheduler] and for the same reasons: a minute-by-minute
 * comparison against a persisted timestamp rather than a countdown from app start, so a phone that
 * opens the app six times a day still performs one check, and no alarm is set to wake a sleeping
 * device for something this unurgent.
 *
 * The first check of a process is deliberately not immediate. Startup is when the tunnel is coming
 * up, subscriptions are refreshing and the user is waiting to press connect; a request for release
 * metadata belongs after all of that, not in the middle of it.
 */
class UpdateScheduler(
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            delay(FIRST_CHECK_DELAY_MILLIS)
            while (isActive) {
                if (isDue()) updates.check(manual = false)
                delay(TICK_MILLIS)
            }
        }
    }

    private fun isDue(): Boolean {
        if (!settings.value.updateAutoCheck) return false
        val last = settings.value.lastUpdateCheckEpochMillis
        val since = System.currentTimeMillis() - last
        // A negative elapsed time means the clock moved backwards — a timezone correction, an NTP
        // step, a user setting the date. Treating that as "not due yet" would suspend checking
        // until real time caught up, which for a date set forward and back is indefinitely.
        return last == 0L || since < 0L || since >= INTERVAL_MILLIS
    }

    private companion object {
        const val INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
        const val FIRST_CHECK_DELAY_MILLIS = 20_000L
        const val TICK_MILLIS = 60_000L
    }
}
