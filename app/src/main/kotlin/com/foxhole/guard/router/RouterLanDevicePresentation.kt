package com.foxhole.guard.router

internal enum class RouterLanDeviceKind { PHONE, TABLET, COMPUTER, UNKNOWN }

internal fun visibleRouterLanDevices(devices: List<RouterLanDevice>, hideOffline: Boolean): List<RouterLanDevice> =
    if (hideOffline) devices.filter { it.online == true } else devices

// Local hostname hints are presentation only; ambiguous names retain the generic icon.
internal fun routerLanDeviceKind(name: String): RouterLanDeviceKind {
    val text = name.lowercase(java.util.Locale.ROOT)
    fun contains(pattern: String) = Regex("(?:^|[^a-z])(?:" + pattern + ")(?:$|[^a-z])").containsMatchIn(text)
    return when {
        contains("ipad[0-9]*|tablet|galaxy-tab[0-9]*") -> RouterLanDeviceKind.TABLET
        contains("iphone[0-9]*|pixel[0-9]+|smartphone|phone") -> RouterLanDeviceKind.PHONE
        contains("macbook|imac|thinkpad|laptop|desktop|workstation|computer|pc") -> RouterLanDeviceKind.COMPUTER
        else -> RouterLanDeviceKind.UNKNOWN
    }
}

// Unconfigured devices inherit VPN policy; runtime connectivity is a separate fact.
internal fun routerLanDeviceUsesVpn(route: String?): Boolean = when (route) {
    null, "vpn" -> true
    "direct" -> false
    else -> throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
}
