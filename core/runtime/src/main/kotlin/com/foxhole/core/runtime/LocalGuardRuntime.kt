@file:Suppress("MatchingDeclarationName")

package com.foxhole.core.runtime

import com.foxhole.core.model.Settings

enum class LocalGuardMode {
    FIREWALL,
    DNS,
}

fun localGuardUsesFullCapture(
    mode: LocalGuardMode,
    dnsGuardFullCapture: Boolean,
): Boolean = mode == LocalGuardMode.FIREWALL || dnsGuardFullCapture

fun LocalGuardMode.runtimeProfileName(): String =
    when (this) {
        LocalGuardMode.FIREWALL -> "Local firewall"
        LocalGuardMode.DNS -> "System DNS replacement"
    }

enum class LocalGuardStartResolution {
    START_REQUESTED,
    START_DESIRED,
    STOP,
}

fun resolveLocalGuardStart(
    desiredMode: LocalGuardMode?,
    requestedMode: LocalGuardMode,
): LocalGuardStartResolution =
    when (desiredMode) {
        null -> LocalGuardStartResolution.STOP
        requestedMode -> LocalGuardStartResolution.START_REQUESTED
        else -> LocalGuardStartResolution.START_DESIRED
    }

fun Settings.localGuardModeOrNull(): LocalGuardMode? {
    return when {
        localFirewallGuardRequired() -> LocalGuardMode.FIREWALL

        i2pRaisesLocalGuard() -> LocalGuardMode.FIREWALL
        dns.replaceSystemDns -> LocalGuardMode.DNS
        else -> null
    }
}

fun Settings.localGuardModeAfterCleanProfileDisconnect(): LocalGuardMode? =
    runtimeSettingsAfterCleanProfileDisconnect().localGuardModeOrNull()

/** Runtime-only view; never persisted, and therefore never revokes the user's I2P engagement. */
fun Settings.runtimeSettingsAfterCleanProfileDisconnect(): Settings =
    if (i2pRuntimeActive() && !i2p.autoReconnectAfterVpnDisconnect) {
        copy(i2p = i2p.copy(engaged = false))
    } else {
        this
    }

fun Settings.i2pRuntimeActive(): Boolean = i2p.enabled && i2p.engaged

fun Settings.i2pWouldRaiseTransparentGuard(): Boolean =
    i2p.allowOutsideTunnel && !expert.firewallEnabled

fun Settings.i2pRaisesLocalGuard(): Boolean =
    i2pRuntimeActive() && i2p.allowOutsideTunnel && !expert.firewallEnabled

private fun Settings.localFirewallGuardRequired(): Boolean {
    if (!expert.firewallEnabled) {
        return false
    }
    return true
}
