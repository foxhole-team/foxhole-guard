package com.foxhole.guard.ui

import com.foxhole.core.model.RoutingModePreset

/**
 * A mode change requested while a VPN tunnel is already live. The user must confirm it (with a
 * countdown that reverts to the running mode on timeout), so the button never silently reshapes a
 * live connection.
 */
enum class LiveModeSwitchKind {
    /** VPN / split → VPN+Tor: attach Tor to the running tunnel (seamless hot reload). */
    ATTACH_TOR,

    /** VPN+Tor → VPN / split: detach Tor from the running tunnel (seamless hot reload). */
    DETACH_TOR,

    /** VPN / VPN+Tor → pure Tor: this drops the VPN and runs Tor on its own. */
    TOR_STOPS_VPN,
}

/** Seconds the live mode-switch modal counts down before it auto-cancels (reverts). */
const val LIVE_MODE_SWITCH_COUNTDOWN_SECONDS: Int = 15

/**
 * Classifies a requested mode change against the currently-applied mode for a LIVE tunnel. Returns
 * null when the change needs no confirmation modal — it either does not touch Tor (split↔split, a
 * no-op) and can hot-reload straight through, or there is nothing to confirm.
 */
internal fun liveModeSwitchKind(
    current: RoutingModePreset,
    target: RoutingModePreset,
): LiveModeSwitchKind? {
    if (current == target) return null
    val currentTor = current == RoutingModePreset.VPN_TOR || current == RoutingModePreset.TOR
    val targetTor = target == RoutingModePreset.VPN_TOR || target == RoutingModePreset.TOR
    return when {
        // Pure Tor as the target always drops the VPN, whatever the source mode.
        target == RoutingModePreset.TOR -> LiveModeSwitchKind.TOR_STOPS_VPN
        // Turning Tor on beside/over a running VPN tunnel.
        !currentTor && target == RoutingModePreset.VPN_TOR -> LiveModeSwitchKind.ATTACH_TOR
        // Turning Tor off, keeping the VPN tunnel (VPN+Tor → VPN or a split mode).
        current == RoutingModePreset.VPN_TOR && !targetTor -> LiveModeSwitchKind.DETACH_TOR
        // Split↔split or any other Tor-neutral reshape: hot-reload straight through, no modal.
        else -> null
    }
}
