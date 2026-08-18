package com.foxhole.guard.ui

import com.foxhole.core.model.RoutingModePreset

enum class LiveModeSwitchKind {
    ATTACH_TOR,

    DETACH_TOR,

    TOR_STOPS_VPN,
}

const val LIVE_MODE_SWITCH_COUNTDOWN_SECONDS: Int = 15

internal fun liveModeSwitchKind(
    current: RoutingModePreset,
    target: RoutingModePreset,
): LiveModeSwitchKind? {
    if (current == target) return null
    val currentTor = current == RoutingModePreset.VPN_TOR || current == RoutingModePreset.TOR
    val targetTor = target == RoutingModePreset.VPN_TOR || target == RoutingModePreset.TOR
    return when {
        target == RoutingModePreset.TOR -> LiveModeSwitchKind.TOR_STOPS_VPN
        !currentTor && target == RoutingModePreset.VPN_TOR -> LiveModeSwitchKind.ATTACH_TOR
        current == RoutingModePreset.VPN_TOR && !targetTor -> LiveModeSwitchKind.DETACH_TOR
        else -> null
    }
}
