package com.mydrop.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.data.ConnectOutcome
import kotlinx.coroutines.launch

/**
 * Raises the tunnel after a reboot, when the user has asked for it.
 *
 * `BOOT_COMPLETED` is one of the broadcasts still allowed to start a foreground service from the
 * background, which is what makes this possible at all — and it is why the work happens here
 * rather than on the app's next launch.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        val container = (context.applicationContext as? MyDropApplication)?.container ?: return
        if (!container.settings.value.autoConnectOnBoot) return

        // Reading the profile and, when the fastest server is wanted, probing servers both take
        // longer than a receiver may hold the main thread for.
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                when (val outcome = container.tunnelLauncher.connect()) {
                    is ConnectOutcome.Started ->
                        container.logs.info(R.string.log_boot_connected, outcome.node.name)

                    // Nothing here can show the system consent dialog, and asking for it out of a
                    // boot broadcast would be a dialog over whatever the user is actually doing.
                    is ConnectOutcome.NeedsConsent ->
                        container.logs.warn(R.string.log_boot_no_permission)

                    is ConnectOutcome.Rejected ->
                        container.logs.warn(R.string.log_boot_rejected, outcome.reason)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Some OEM images fast-boot out of a saved state and never send the standard one.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
