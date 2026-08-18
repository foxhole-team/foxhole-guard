package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.tunnelKeptOutPackages
import com.foxhole.guard.R

internal enum class CliStatusMode { NONE, VPN, TOR, VPN_TOR }

internal enum class CliStatusScenario {
    WHOLE_DEVICE,
    PROXY_SELECTED,
    PROXY_EXCEPT_SELECTED,
    PROXY_SERVER,
    PROXY_SERVER_LAN,
}

internal enum class CliCompactRouteStatus(
    val shape: CliCompactRouteShape,
    val labelRes: Int,
) {
    VPN(shape(vpn = true), R.string.cli_st_vpn),
    VPN_PROXY(shape(vpn = true, vpnProxy = true), R.string.cli_st_split_vpn),
    TOR(shape(tor = true), R.string.cli_st_tor),
    TOR_PROXY(shape(tor = true, torProxy = true), R.string.cli_st_split_tor),
    VPN_TOR(shape(vpn = true, tor = true, beside = true), R.string.cli_st_vpn_tor),
    VPN_TOR_PROXY(
        shape(vpn = true, tor = true, beside = true, torProxy = true),
        R.string.cli_st_vpn_tor_proxy,
    ),
    VPN_PROXY_TOR(
        shape(vpn = true, tor = true, beside = true, vpnProxy = true),
        R.string.cli_st_vpn_proxy_tor,
    ),
    VPN_PROXY_TOR_PROXY(
        shape(vpn = true, tor = true, beside = true, vpnProxy = true, torProxy = true),
        R.string.cli_st_vpn_proxy_tor_proxy,
    ),
    TOR_IN_VPN(shape(vpn = true, tor = true), R.string.cli_st_tor_in_vpn),
    TOR_PROXY_IN_VPN(
        shape(vpn = true, tor = true, torProxy = true),
        R.string.cli_st_tor_proxy_in_vpn,
    ),
    TOR_IN_VPN_PROXY(
        shape(vpn = true, tor = true, vpnProxy = true),
        R.string.cli_st_tor_in_vpn_proxy,
    ),
    TOR_PROXY_IN_VPN_PROXY(
        shape(vpn = true, tor = true, vpnProxy = true, torProxy = true),
        R.string.cli_st_tor_proxy_in_vpn_proxy,
    ),
}

internal data class CliCompactRouteShape(
    val vpn: Boolean,
    val tor: Boolean,
    val beside: Boolean,
    val vpnProxy: Boolean,
    val torProxy: Boolean,
)

private fun shape(
    vpn: Boolean = false,
    tor: Boolean = false,
    beside: Boolean = false,
    vpnProxy: Boolean = false,
    torProxy: Boolean = false,
): CliCompactRouteShape =
    CliCompactRouteShape(vpn, tor, beside, vpnProxy, torProxy)

internal fun cliCompactRouteStatus(
    settings: Settings,
    runtimes: CliActiveRuntimes,
): CliCompactRouteStatus? {
    val vpnScenario = if (runtimes.vpn) {
        cliVpnScenario(
            settings = settings,
            vpnLive = true,
            lanProxyServing = false,
            localSurfaceServing = false,
        )
    } else {
        null
    }
    val vpnProxy = runtimes.proxy || vpnScenario.isPerAppProxy()
    val torProxy = runtimes.tor && settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS
    val current = shape(
        vpn = runtimes.vpn,
        tor = runtimes.tor,
        beside = runtimes.vpn && runtimes.tor && runtimes.torBesideVpn,
        vpnProxy = vpnProxy,
        torProxy = torProxy,
    )
    return CliCompactRouteStatus.entries.firstOrNull { status -> status.shape == current }
}

private fun CliStatusScenario?.isPerAppProxy(): Boolean =
    this == CliStatusScenario.PROXY_SELECTED || this == CliStatusScenario.PROXY_EXCEPT_SELECTED

internal fun cliStatusMode(runtimes: CliActiveRuntimes): CliStatusMode =
    when {
        runtimes.vpn && runtimes.tor -> CliStatusMode.VPN_TOR
        runtimes.vpn -> CliStatusMode.VPN
        runtimes.tor -> CliStatusMode.TOR
        else -> CliStatusMode.NONE
    }

internal fun cliVpnScenario(
    settings: Settings,
    vpnLive: Boolean,
    lanProxyServing: Boolean,
    localSurfaceServing: Boolean,
): CliStatusScenario? {
    if (!vpnLive) return null
    return when {
        lanProxyServing -> CliStatusScenario.PROXY_SERVER_LAN
        localSurfaceServing -> CliStatusScenario.PROXY_SERVER
        else -> when (settings.expert.perAppRoutingMode) {
            PerAppRoutingMode.INCLUDE_SELECTED_APPS -> CliStatusScenario.PROXY_SELECTED
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
                if (settings.expert.tunnelKeptOutPackages().isEmpty()) {
                    CliStatusScenario.WHOLE_DEVICE
                } else {
                    CliStatusScenario.PROXY_EXCEPT_SELECTED
                }
            PerAppRoutingMode.FULL_TUNNEL -> CliStatusScenario.WHOLE_DEVICE
        }
    }
}

internal fun cliTorScenario(
    settings: Settings,
    torLive: Boolean,
): CliStatusScenario? {
    if (!torLive) return null
    return when (settings.privacyRoute.scope) {
        PrivacyRouteScope.ALL_APPS -> CliStatusScenario.WHOLE_DEVICE
        PrivacyRouteScope.SELECTED_APPS -> CliStatusScenario.PROXY_SELECTED
    }
}

internal fun cliFirewallLive(
    settings: Settings,
    connection: ConnectionSnapshot,
    runtimes: CliActiveRuntimes,
): Boolean =
    settings.expert.firewallEnabled && (runtimes.any || connection.isLocalGuardLive())

internal fun cliRouteRulesInForce(runtimes: CliActiveRuntimes): Boolean = runtimes.vpn || runtimes.tor

internal enum class CliStatusWord {
    CONNECTING,
    RECONNECTING,
    DISCONNECTING,
    ERROR,
    VPN,
    TOR,
    VPN_TOR,
    I2P,
    FIREWALL,
    NONE,
}

internal fun cliStatusWordFor(
    state: ConnectionState,
    runtimes: CliActiveRuntimes,
    i2pConnected: Boolean,
    firewallLive: Boolean,
): CliStatusWord =
    when {
        state == ConnectionState.CONNECTING -> CliStatusWord.CONNECTING
        state == ConnectionState.RECONNECTING -> CliStatusWord.RECONNECTING
        state == ConnectionState.DISCONNECTING -> CliStatusWord.DISCONNECTING
        state == ConnectionState.ERROR -> CliStatusWord.ERROR
        runtimes.vpn && runtimes.tor -> CliStatusWord.VPN_TOR
        runtimes.vpn -> CliStatusWord.VPN
        runtimes.tor -> CliStatusWord.TOR
        i2pConnected -> CliStatusWord.I2P
        firewallLive -> CliStatusWord.FIREWALL
        else -> CliStatusWord.NONE
    }

internal fun cliStatusWordTone(word: CliStatusWord): CliLineTone = when (word) {
    CliStatusWord.CONNECTING,
    CliStatusWord.RECONNECTING,
    CliStatusWord.DISCONNECTING,
    -> CliLineTone.WARN
    CliStatusWord.ERROR -> CliLineTone.ERR
    CliStatusWord.VPN -> CliLineTone.VPN
    CliStatusWord.TOR, CliStatusWord.VPN_TOR -> CliLineTone.TOR
    CliStatusWord.I2P -> CliLineTone.I2P
    CliStatusWord.FIREWALL -> CliLineTone.FIREWALL
    CliStatusWord.NONE -> CliLineTone.DIM
}
