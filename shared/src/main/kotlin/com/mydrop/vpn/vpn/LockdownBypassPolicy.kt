package com.mydrop.vpn.vpn

/**
 * Coordinates Android's `VpnService.Builder.allowBypass()` flag with system Always-on Lockdown
 * ("Block connections without VPN").
 *
 * When Lockdown is OFF, `allowBypass()` keeps long-lived push channels (such as FCM) able to bind
 * directly to the underlying network instead of being reaped inside the proxy tunnel.
 *
 * When Lockdown is ON, AOSP Android 10–13 `Vpn.setAlwaysOnPackageInternal` installs
 * `RULE_PRIORITY_PROHIBIT_NON_VPN` (12500) without recreating an already-running `NetworkAgent`;
 * a tunnel built with `allowBypass()` stays at `RULE_PRIORITY_BYPASSABLE_VPN` (20000), so all
 * app sockets hit `PROHIBIT` (`EACCES` / `net::ERR_NETWORK_ACCESS_DENIED`). Omitting `allowBypass()`
 * when Lockdown is enabled and rebuilding the TUN on transitions causes AOSP's
 * `updateLinkPropertiesInPlaceIfPossible` (`oldConfig.allowBypass != mConfig.allowBypass`) to
 * register a non-bypassable agent at `RULE_PRIORITY_SECURE_VPN` (12000) — and restores bypassable
 * push support when Lockdown is turned back off.
 */
internal object LockdownBypassPolicy {
    fun shouldAllowBypass(lockdownEnabled: Boolean): Boolean = !lockdownEnabled

    fun needsTunRebuild(tunLockdownEnabled: Boolean?, currentLockdownEnabled: Boolean): Boolean =
        tunLockdownEnabled != null && tunLockdownEnabled != currentLockdownEnabled
}
