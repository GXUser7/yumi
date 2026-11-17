package com.mydrop.vpn.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.data.ConnectOutcome
import com.mydrop.vpn.shared.R
import kotlinx.coroutines.launch

/**
 * 1x1 home screen widget that shows tunnel status and toggles the VPN on tap.
 */
class TunnelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds, MyDropVpnService.state.value)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        val container = (context.applicationContext as? MyDropApplication)?.container ?: return
        val current = MyDropVpnService.state.value
        when (toggleActionFor(current)) {
            ToggleAction.Ignore -> return
            ToggleAction.Disconnect -> {
                container.tunnelLauncher.disconnect()
                updateAll(context, MyDropVpnService.state.value)
                return
            }
            ToggleAction.Connect -> Unit
        }

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                when (container.tunnelLauncher.connect()) {
                    is ConnectOutcome.Started -> Unit
                    is ConnectOutcome.NeedsConsent,
                    is ConnectOutcome.Rejected,
                    -> {
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                            ?.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                            ?.let { launchIntent -> runCatching { context.startActivity(launchIntent) } }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.mydrop.vpn.action.WIDGET_TOGGLE"

        fun updateAll(context: Context, state: VpnState = MyDropVpnService.state.value) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val component = ComponentName(context, TunnelWidgetProvider::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return
            updateWidgets(context, manager, ids, state)
        }

        fun createRemoteViews(context: Context, state: VpnState): RemoteViews {
            val spec = widgetSpecFor(state)
            val description = resolveWidgetContentDescription(context, spec.stateLabelRes)

            val toggleIntent = Intent(context, TunnelWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return RemoteViews(context.packageName, R.layout.widget_tunnel).also { views ->
                bindWidget(spec, description, pendingIntent, RemoteViewsTarget(views))
            }
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            state: VpnState,
        ) {
            if (appWidgetIds.isEmpty()) return
            val views = createRemoteViews(context, state)
            manager.updateAppWidget(appWidgetIds, views)
        }

        private fun resolveWidgetContentDescription(
            context: Context,
            @StringRes stateLabelRes: Int,
        ): String {
            val strings = (context.applicationContext as? MyDropApplication)?.container?.strings
            return if (strings != null) {
                strings.get(
                    R.string.widget_content_description,
                    strings.get(R.string.app_name),
                    strings.get(stateLabelRes),
                )
            } else {
                formatWidgetContentDescription(
                    template = context.getString(R.string.widget_content_description),
                    appName = context.getString(R.string.app_name),
                    stateLabel = context.getString(stateLabelRes),
                )
            }
        }
    }
}

internal enum class ToggleAction {
    Connect,
    Disconnect,
    Ignore,
}

internal data class WidgetVisualSpec(
    @DrawableRes val backgroundRes: Int,
    @DrawableRes val markRes: Int,
    @DrawableRes val cueRes: Int,
    val showConnectingBorder: Boolean,
    @StringRes val stateLabelRes: Int,
)

internal interface WidgetRemoteTarget {
    fun setBackgroundResource(viewId: Int, @DrawableRes resId: Int)
    fun setImageResource(viewId: Int, @DrawableRes resId: Int)
    fun setViewVisibility(viewId: Int, visibility: Int)
    fun setContentDescription(viewId: Int, description: CharSequence)
    fun setOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent?)
}

private class RemoteViewsTarget(
    private val views: RemoteViews,
) : WidgetRemoteTarget {
    override fun setBackgroundResource(viewId: Int, resId: Int) {
        views.setInt(viewId, "setBackgroundResource", resId)
    }

    override fun setImageResource(viewId: Int, resId: Int) {
        views.setImageViewResource(viewId, resId)
    }

    override fun setViewVisibility(viewId: Int, visibility: Int) {
        views.setViewVisibility(viewId, visibility)
    }

    override fun setContentDescription(viewId: Int, description: CharSequence) {
        views.setContentDescription(viewId, description)
    }

    override fun setOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent?) {
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }
}

internal fun toggleActionFor(state: VpnState): ToggleAction = when (state) {
    VpnState.Disconnecting -> ToggleAction.Ignore
    is VpnState.Connected,
    is VpnState.Connecting,
    -> ToggleAction.Disconnect
    VpnState.Disconnected,
    is VpnState.Failed,
    -> ToggleAction.Connect
}

internal fun widgetSpecFor(state: VpnState): WidgetVisualSpec = when (state) {
    VpnState.Disconnected -> WidgetVisualSpec(
        backgroundRes = R.drawable.widget_bg_off,
        markRes = R.drawable.ic_widget_yumi_off,
        cueRes = R.drawable.ic_widget_cue_off,
        showConnectingBorder = false,
        stateLabelRes = R.string.tile_off,
    )
    is VpnState.Connecting -> WidgetVisualSpec(
        backgroundRes = R.drawable.widget_bg_connecting,
        markRes = R.drawable.ic_widget_yumi_connecting,
        cueRes = R.drawable.ic_widget_cue_connecting,
        showConnectingBorder = true,
        stateLabelRes = R.string.tile_connecting,
    )
    VpnState.Disconnecting -> WidgetVisualSpec(
        backgroundRes = R.drawable.widget_bg_connecting,
        markRes = R.drawable.ic_widget_yumi_connecting,
        cueRes = R.drawable.ic_widget_cue_connecting,
        showConnectingBorder = true,
        stateLabelRes = R.string.tile_disconnecting,
    )
    is VpnState.Connected -> WidgetVisualSpec(
        backgroundRes = R.drawable.widget_bg_on,
        markRes = R.drawable.ic_widget_yumi_on,
        cueRes = R.drawable.ic_widget_cue_on,
        showConnectingBorder = false,
        stateLabelRes = R.string.tile_connected,
    )
    is VpnState.Failed -> WidgetVisualSpec(
        backgroundRes = R.drawable.widget_bg_failed,
        markRes = R.drawable.ic_widget_yumi_failed,
        cueRes = R.drawable.ic_widget_cue_failed,
        showConnectingBorder = false,
        stateLabelRes = R.string.tile_error,
    )
}

internal fun formatWidgetContentDescription(
    template: String,
    appName: String,
    stateLabel: String,
): String = String.format(template, appName, stateLabel)

internal fun bindWidget(
    spec: WidgetVisualSpec,
    contentDescription: CharSequence,
    pendingIntent: PendingIntent?,
    target: WidgetRemoteTarget,
) {
    target.setBackgroundResource(android.R.id.background, spec.backgroundRes)
    target.setViewVisibility(
        R.id.widget_border_overlay,
        if (spec.showConnectingBorder) View.VISIBLE else View.GONE,
    )
    target.setImageResource(R.id.widget_mark, spec.markRes)
    target.setImageResource(R.id.widget_status_cue, spec.cueRes)
    target.setContentDescription(android.R.id.background, contentDescription)
    target.setOnClickPendingIntent(android.R.id.background, pendingIntent)
}
