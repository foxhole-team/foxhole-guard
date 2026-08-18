@file:Suppress("MatchingDeclarationName")

package com.foxhole.core.runtime

import com.foxhole.core.model.Settings

/**
 * The guard runtimes that can run without a VPN profile. There is no journal mode: network-activity
 * logging is a facet of the firewall guard (a toggle on it), never a runtime of its own.
 */
enum class LocalGuardMode {
    FIREWALL,
    DNS,
}

/** A fake-IP answer is safe only when the guard captures the connection that follows the query. */
fun localGuardUsesFullCapture(
    mode: LocalGuardMode,
    dnsGuardFullCapture: Boolean,
): Boolean = mode == LocalGuardMode.FIREWALL || dnsGuardFullCapture

fun LocalGuardMode.runtimeProfileName(): String =
    when (this) {
        LocalGuardMode.FIREWALL -> "Local firewall"
        LocalGuardMode.DNS -> "System DNS replacement"
    }

/** What a START_LOCAL_GUARD command must do by the time it actually runs. */
enum class LocalGuardStartResolution {
    START_REQUESTED,
    START_DESIRED,
    STOP,
}

/**
 * Reconciles a queued guard start with what the settings want NOW.
 *
 * Guard commands are queued and run one at a time, so the settings can move between "send" and
 * "run" — every guard-relevant toggle posts one, and a firewall/DNS-guard switch posts two. Only
 * [STOP] may tear the runtime down, and only because nothing wants a guard any more.
 *
 * A command for the OTHER mode is the case that used to be wrong: it took the same teardown branch
 * as "guard disabled" and stopped a guard the settings still asked for — with `suppressLocalGuard`
 * set, so nothing raised it back and the firewall stayed down until the user toggled something. On
 * the bench that read as "the guard starts and stops itself a second later". The stale command
 * knows less than the settings do, so it defers to them and starts the desired mode instead; the
 * mode change is a real restart anyway (the two guards have different tun parameters).
 */
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
        // I2P always needs the Android VPN routing surface. With no profile up it raises the
        // transparent firewall guard and uses that same TUN only for the `.i2p` diversion.
        i2pRaisesLocalGuard() -> LocalGuardMode.FIREWALL
        dns.replaceSystemDns -> LocalGuardMode.DNS
        else -> null
    }
}

/**
 * Carrier wanted after a clean profile STOP.
 *
 * I2P may raise its own transparent guard only when the user opted into carrier recovery. Turning
 * that preference off does not revoke or pause I2P; it only prevents I2P from being the reason a
 * replacement Android VPN is created. A separately enabled firewall or system-DNS guard remains
 * authoritative and is still restored.
 */
fun Settings.localGuardModeAfterCleanProfileDisconnect(): LocalGuardMode? =
    runtimeSettingsAfterCleanProfileDisconnect().localGuardModeOrNull()

/** Runtime-only view; never persisted, and therefore never revokes the user's I2P engagement. */
fun Settings.runtimeSettingsAfterCleanProfileDisconnect(): Settings =
    if (i2pRuntimeActive() && !i2p.autoReconnectAfterVpnDisconnect) {
        copy(i2p = i2p.copy(engaged = false))
    } else {
        this
    }

/**
 * Whether the I2P router should actually run: permitted (`enabled`) AND not paused (`engaged`).
 * Every runtime gate reads this instead of `enabled` so the dashboard pause stops the router while
 * the settings permission (and its quick-access pill) stays.
 */
fun Settings.i2pRuntimeActive(): Boolean = i2p.enabled && i2p.engaged

/**
 * Would switching I2P on right now raise the transparent guard?
 *
 * Asked before the switch, so it cannot use [i2pRaisesLocalGuard], which answers about a module
 * that is already on. The transparent guard is a tunnel the user did not ask for that deliberately
 * applies no rules — no blocking, no DNS filtering — so the answer decides whether they are told.
 */
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
