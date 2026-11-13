package com.mydrop.vpn.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One process-wide observer, also alive while only the VPN service is running. */
class InstalledApps(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.data?.schemeSpecificPart ?: return
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                // An update sends REMOVED followed by ADDED with EXTRA_REPLACING. During that
                // gap PackageManager may not resolve the package; keep its selection and rules.
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                settings.onPackageRemoved(name, replacing)
                if (replacing) return
            }
            revision.update { it + 1 }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Broadcasts are not delivered to a dead process. Repair selections saved before an
        // uninstall on the next start too, without filtering out system or non-launcher apps.
        settings.pruneSplitTunnelPackages { uid(it) != null }
    }

    fun rules(settings: AppSettings): SplitTunnelRules = SplitTunnelRules.resolve(settings, ::uid)

    private fun uid(name: String): Int? = try {
        context.packageManager.getApplicationInfo(name, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
