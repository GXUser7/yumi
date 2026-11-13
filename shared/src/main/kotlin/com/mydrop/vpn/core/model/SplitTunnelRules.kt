package com.mydrop.vpn.core.model

/** Android resolves package names to UIDs when a VPN interface is established. */
data class SplitTunnelRules(
    val mode: SplitTunnelMode,
    val packageUids: Map<String, Int>,
) {
    companion object {
        fun resolve(settings: AppSettings, uid: (String) -> Int?): SplitTunnelRules =
            SplitTunnelRules(
                mode = settings.splitTunnelMode,
                packageUids = if (settings.splitTunnelMode == SplitTunnelMode.Off) {
                    emptyMap()
                } else {
                    settings.splitTunnelPackages.mapNotNull { name ->
                        uid(name)?.let { name to it }
                    }.toMap()
                },
            )
    }
}
