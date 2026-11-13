package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(
    filesDir: File,
    scope: CoroutineScope,
    onWriteFailure: (Throwable) -> Unit = {},
) {

    private val store = JsonStore(
        file = File(filesDir, "settings.json"),
        serializer = AppSettings.serializer(),
        defaultValue = AppSettings(),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    val settings: StateFlow<AppSettings> = store.state

    val value: AppSettings get() = store.value

    fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    fun onPackageRemoved(packageName: String, replacing: Boolean) {
        if (replacing || packageName !in value.splitTunnelPackages) return
        update { it.copy(splitTunnelPackages = it.splitTunnelPackages - packageName) }
    }

    fun pruneSplitTunnelPackages(installed: (String) -> Boolean) {
        val missing = value.splitTunnelPackages.filterNot(installed).toSet()
        if (missing.isEmpty()) return
        update { it.copy(splitTunnelPackages = it.splitTunnelPackages - missing) }
    }
}
