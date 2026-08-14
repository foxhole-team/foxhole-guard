package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.tunnelKeptOutPackages
import com.foxhole.guard.R

/**
 * The two axes the status block is written on.
 *
 * MODE is what the user picked, and there are exactly three: VPN, TOR, both. The firewall is
 * deliberately not among them — it is a module, reported on its own row among the modules, and a
 * device running the filter alone is running no mode at all.
 *
 * SCENARIO is how that mode is applied, and VPN and TOR each carry their own, independently: a
 * whole-device Tor route next to a per-app VPN tunnel is an ordinary configuration.
 *
 * Both are derived from RUNTIME state only. A per-app mode stored in settings while nothing is
 * running is a preference, not a scenario, and printing it as one is the exact lie this block
 * exists to avoid.
 */
internal enum class CliStatusMode { NONE, VPN, TOR, VPN_TOR }

internal enum class CliStatusScenario {
    WHOLE_DEVICE,
    PROXY_SELECTED,
    PROXY_EXCEPT_SELECTED,
    PROXY_SERVER,
    PROXY_SERVER_LAN,
}

/**
 * Compact route name used by the always-visible status fact. Unlike the header (which deliberately
 * lists only the live networks: VPN, Tor, I2P), this value also tells whether each leg applies to
 * the whole device or acts as a per-app proxy, and whether Tor sits beside or inside the VPN.
 */
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

/** Pure ALL/SELECTED × VPN/Tor × beside/inside matrix, shared by UI and regression tests. */
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

/**
 * Which of the three modes is running, from the live routes and nothing else.
 *
 * The firewall is not consulted here at all: it is a module and it never carries a packet, so with
 * the filter alone up this answers NONE and the filter reports itself on its own module row.
 */
internal fun cliStatusMode(runtimes: CliActiveRuntimes): CliStatusMode =
    when {
        runtimes.vpn && runtimes.tor -> CliStatusMode.VPN_TOR
        runtimes.vpn -> CliStatusMode.VPN
        runtimes.tor -> CliStatusMode.TOR
        else -> CliStatusMode.NONE
    }

/**
 * The VPN scenario, ordered by what beats what: a server published to the Wi-Fi first (another
 * device can reach this one through it), then the device-local proxy surface, then the two per-app
 * shapes, then the plain whole-device tunnel.
 *
 * Null while nothing carries traffic — see [cliStatusMode].
 *
 * Exclude mode with an empty exception set is a whole-device tunnel and says so: "everything
 * except" nothing is everything.
 */
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

/** The Tor scenario. Its own scope switch, never the VPN's per-app mode. */
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

/**
 * The firewall as a live component rather than as a stored switch: the filter is armed AND some
 * runtime is hosting it — either a profile tunnel or the local guard's own service.
 */
internal fun cliFirewallLive(
    settings: Settings,
    connection: ConnectionSnapshot,
    runtimes: CliActiveRuntimes,
): Boolean =
    settings.expert.firewallEnabled && (runtimes.any || connection.isLocalGuardLive())

/**
 * Are the per-app and per-site rules in force at all? Only a live route applies them; with nothing
 * running they are a configuration, and the status block does not print configurations.
 */
internal fun cliRouteRulesInForce(runtimes: CliActiveRuntimes): Boolean = runtimes.vpn || runtimes.tor

/**
 * The header's state word, as a decision rather than a string: which route the app is connected TO.
 * A bare "connected" was the same word for a whole-device VPN, a Tor circuit and both at once —
 * the one question the line exists to answer.
 *
 * Order is by strength of claim. A transition outranks everything (nothing is carrying yet), then a
 * live route, then the I2P overlay — whose own network must be up, not merely engaged — and last
 * the firewall, which carries nothing and is therefore only the answer when nothing else runs.
 */
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
