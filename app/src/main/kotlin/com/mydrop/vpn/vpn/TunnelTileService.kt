package com.mydrop.vpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mydrop.vpn.MainActivity
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.data.ConnectOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The tunnel on the quick settings panel.
 *
 * Worth having because of where it can be reached from: the shade is available over other apps
 * and from the lock screen, which is exactly where "turn the VPN on" tends to be wanted, and the
 * app takes several seconds to cold-start for a single tap.
 */
class TunnelTileService : TileService() {

    private var scope: CoroutineScope? = null

    /**
     * The panel only reports state while it is open, so the flow is collected for exactly that
     * window. Collecting for longer would keep the process alive to update something nobody is
     * looking at.
     */
    override fun onStartListening() {
        super.onStartListening()
        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = collector
        collector.launch {
            MyDropVpnService.state.collectLatest(::render)
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        val container = (application as MyDropApplication).container

        if (MyDropVpnService.state.value.isActive) {
            container.tunnelLauncher.disconnect()
            return
        }

        // A tap from the lock screen has to wait for the user to unlock: connecting reads the
        // profile, which sits in credential-encrypted storage.
        unlockAndRun {
            container.applicationScope.launch {
                when (val outcome = container.tunnelLauncher.connect()) {
                    is ConnectOutcome.Started -> Unit
                    // Both remaining cases need a screen: one to grant consent, one to add or
                    // choose a server. Neither is something a tile can do.
                    is ConnectOutcome.NeedsConsent -> openApp()
                    is ConnectOutcome.Rejected -> openApp()
                }
            }
        }
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        tile.state = when {
            state.isActive -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                is VpnState.Connected -> currentNodeName()
                is VpnState.Connecting -> strings.get(R.string.tile_connecting)
                is VpnState.Disconnecting -> strings.get(R.string.tile_disconnecting)
                is VpnState.Failed -> strings.get(R.string.tile_error)
                VpnState.Disconnected -> strings.get(R.string.tile_off)
            }
        }
        tile.updateTile()
    }

    private val strings get() = (application as MyDropApplication).container.strings

    private fun currentNodeName(): String =
        (application as MyDropApplication).container.profiles.selectedNode()?.name
            ?: strings.get(R.string.tile_connected)

    // The deprecated overload is only ever reached below API 34, where it is the only one that
    // exists; lint flags the call site rather than the branch it sits in.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
