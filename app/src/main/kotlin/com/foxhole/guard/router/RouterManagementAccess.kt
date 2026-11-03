package com.foxhole.guard.router

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class RouterManagementAccess {
    LOCAL, REMOTE, VPN_ACTIVE, UNAVAILABLE;
    val canDiscover: Boolean get() = this == LOCAL || this == REMOTE
}

internal fun routerManagementAccess(wifi: Boolean, homeCandidate: Boolean, vpn: Boolean,
    pairedRemoteVpn: Boolean): RouterManagementAccess = when {
    wifi && homeCandidate -> if (vpn) RouterManagementAccess.VPN_ACTIVE else RouterManagementAccess.LOCAL
    pairedRemoteVpn -> RouterManagementAccess.REMOTE
    else -> RouterManagementAccess.UNAVAILABLE
}

// This private hint never substitutes for the pinned router certificate or pairing credential.
internal fun routerWifiIdentityHash(credential: ByteArray, bssid: String?, ssid: String?): String? {
    require(credential.size == 32)
    val address = bssid?.uppercase(java.util.Locale.ROOT)?.takeIf {
        it.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) &&
            it !in setOf("02:00:00:00:00:00", "00:00:00:00:00:00")
    }
    val name = ssid?.removeSurrounding("\"")?.takeIf {
        it.isNotBlank() && it != "<unknown ssid>" && it.toByteArray().size <= 32 && it.none(Char::isISOControl)
    }
    val hint = address?.let { "bssid:$it" } ?: name?.let { "ssid:$it" } ?: return null
    return Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(credential, "HmacSHA256"))
        doFinal(("foxhole-router-wifi-v1\u0000" + hint).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
