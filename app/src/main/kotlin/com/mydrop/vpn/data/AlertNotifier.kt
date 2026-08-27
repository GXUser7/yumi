package com.mydrop.vpn.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mydrop.vpn.MainActivity
import com.mydrop.vpn.R

/**
 * Says out loud that something the app usually fixes silently has happened.
 *
 * Everything here is already in the journal, and the journal is the wrong place for it: the whole
 * value of automatic failover is that the user is not watching when it fires, which is exactly why
 * they never learn that it did. A tunnel that quietly moved to another country, or a resolver that
 * was swapped out from under them, are things worth knowing the moment they happen — otherwise the
 * only symptom is a connect screen naming a server nobody chose, hours later.
 *
 * [enabled] is read on every post rather than captured once, so the switch in settings takes
 * effect on the next event instead of the next launch.
 *
 * Its own channel, at [NotificationManager.IMPORTANCE_DEFAULT]: the tunnel's own notification is
 * ongoing and silent by design, and a fault has to be able to make a sound without making the
 * permanent one noisy. So there are two ways to silence this — the switch in the app, and the
 * channel in the system settings — and neither of them touches the other.
 */
class AlertNotifier(
    private val context: Context,
    private val strings: Strings,
    private val enabled: () -> Boolean,
) {

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    private var channelReady = false

    /** The tunnel moved off a server that stopped answering. */
    fun serverLeft(dead: String, replacement: String) = post(
        id = ID_SERVER,
        title = strings.get(R.string.alert_server_dead_title),
        text = strings.get(R.string.alert_server_dead_text, dead, replacement),
    )

    /** The server is gone and there was nowhere to go: the tunnel is still on it, still broken. */
    fun serverStranded(dead: String) = post(
        id = ID_SERVER,
        title = strings.get(R.string.alert_server_stranded_title),
        text = strings.get(R.string.alert_server_stranded_text, dead),
    )

    /** Names stopped resolving and the resolver was replaced. */
    fun dnsReplaced(dead: String, replacement: String) = post(
        id = ID_DNS,
        title = strings.get(R.string.alert_dns_dead_title),
        text = strings.get(R.string.alert_dns_replaced_text, dead, replacement),
    )

    /** Names stopped resolving and the user has asked for the resolver to be left alone. */
    fun dnsDead(dead: String) = post(
        id = ID_DNS,
        title = strings.get(R.string.alert_dns_dead_title),
        text = strings.get(R.string.alert_dns_dead_text, dead),
    )

    /** A newer release exists. Not a fault, but it arrives the same way and at the same moments. */
    fun updateAvailable(version: String) = post(
        id = ID_UPDATE,
        title = strings.get(R.string.alert_update_title, version),
        text = strings.get(R.string.alert_update_text),
    )

    private fun post(id: Int, title: String, text: String) {
        if (!enabled()) return
        val notifications = manager ?: return
        ensureChannel(notifications)

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // Without this the text is truncated to one line, and the interesting half of every
            // message here — which server, which resolver — sits at the end of it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        // Silently ignored when the user has refused POST_NOTIFICATIONS, which is the correct
        // outcome: this is an aside, and nothing downstream depends on it being seen.
        runCatching { notifications.notify(id, notification) }
    }

    private fun ensureChannel(notifications: NotificationManager) {
        if (channelReady) return
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                strings.get(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = strings.get(R.string.alert_channel_description) },
        )
        channelReady = true
    }

    private companion object {
        const val CHANNEL_ID = "mydrop_alerts"

        // Stable per kind, so a second server failure replaces the first rather than stacking a
        // column of near-identical lines in the shade.
        const val ID_SERVER = 4001
        const val ID_DNS = 4002
        const val ID_UPDATE = 4003
    }
}
