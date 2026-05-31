package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.VpnSession

internal data class RuntimeControlPlaneOwnershipState(
    val activeSession: VpnSession? = null,
    val activeLocalGuardMode: LocalGuardMode? = null,
    val validationActive: Boolean = false,
    val networkCallbackRegistered: Boolean = false,
    val vpnNetworkCallbackRegistered: Boolean = false,
    val defaultNetworkCallbackRegistered: Boolean = false,
    val activeVpnNetworkHandle: Long? = null,
)

internal enum class RuntimeNetworkCallbackKind {
    UPSTREAM,
    VPN,
    DEFAULT,
}
