package com.foxhole.guard.ui.cli.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.enabledDnsFilterCategories
import com.foxhole.core.model.networkUp
import com.foxhole.core.model.serving
import com.foxhole.core.model.tunnelKeptOutPackages
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import java.util.Locale

// Icons, not names: at 13sp a launcher icon is the width of two letters, so a lane fits six of
// them where it fitted three truncated labels. Past that the tail collapses into "+N".
private const val MAX_LANE_ICONS = 6

/**
 * The short `status` answer, printed line by line into the same log as everything else.
 *
 * Strictly current state, in the order the user reads it: which MODE is running and in which
 * SCENARIO, then the routing rules that are actually in force, then the modules — firewall, I2P,
 * DNS filtering. Encryption and anomalies belong to the long-press block and are deliberately
 * absent here.
 *
 * Everything is read from runtime state. A per-app mode saved in settings with nothing running is
 * a preference, not a route, and printing it as one is the defect this block keeps failing at.
 */
@Composable
internal fun cliStatusRows(
    home: HomeRouteUiState,
    torOnlyLive: Boolean,
): List<CliTerminalRow> {
    val runtimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    return cliStatusModeRows(home = home, runtimes = runtimes) +
        cliStatusRouteIdentityRows(home = home, runtimes = runtimes) +
        statusRouteRows(home = home, runtimes = runtimes) +
        cliStatusModuleRows(home = home, runtimes = runtimes) +
        cliStatusInfoRows(home = home, runtimes = runtimes)
}

/**
 * The routes that actually carry traffic. The local guard is deliberately absent: it is a filter,
 * not a route, so a firewall-only device reports every flag here as false — «no connection».
 */
internal data class CliActiveRuntimes(
    val vpn: Boolean,
    val proxy: Boolean,
    val tor: Boolean,
    val torBesideVpn: Boolean,
    val i2p: Boolean,
) {
    val any: Boolean
        get() = vpn || tor || i2p
}

internal fun activeRuntimes(
    home: HomeRouteUiState,
    torOnlyLive: Boolean,
): CliActiveRuntimes {
    val connection = home.connection
    val settings = home.settings
    // A profile runtime — the sentinel ids are the firewall and the standalone TOR session, and
    // neither is a VPN/proxy egress.
    val profileLive =
        connection.state == ConnectionState.CONNECTED &&
            connection.profileId?.let { it > 0L } == true
    val torOnlySentinelLive =
        connection.state == ConnectionState.CONNECTED && connection.profileId == TOR_ONLY_PROFILE_ID
    val tor =
        (connection.state == ConnectionState.CONNECTED && connection.torActive) ||
            torOnlySentinelLive ||
            torOnlyLive
    return CliActiveRuntimes(
        vpn = profileLive,
        proxy = profileLive && connection.trafficMode == TrafficMode.PROXY,
        tor = tor,
        // bypassVpnTunnel: TOR dials out NEXT to the tunnel instead of through it.
        torBesideVpn = tor && profileLive && settings.privacyRoute.bypassVpnTunnel,
        i2p = settings.i2p.enabled && settings.i2p.engaged,
    )
}

/**
 * Operating mode first, then the scenario under it — the hierarchy the readme describes.
 *
 * One scenario row per live leg, because VPN and TOR answer for themselves: with both up the rows
 * name which leg they are about, and with one there is nothing to disambiguate.
 */
@Composable
internal fun cliStatusModeRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    val settings = home.settings
    val mode = cliStatusMode(runtimes = runtimes)
    val modeRow = CliTerminalRow(
        key = stringResource(R.string.cli_home_status_mode),
        value = cliStatusModeLabel(mode),
        tone = cliStatusModeTone(mode),
    )
    val vpnScenario = cliVpnScenario(
        settings = settings,
        vpnLive = runtimes.vpn,
        lanProxyServing = home.lanProxy.phase.serving,
        localSurfaceServing = cliLocalProxySurfaceServing(home.connection),
    )
    val torScenario = cliTorScenario(settings = settings, torLive = runtimes.tor)
    val bothLegs = vpnScenario != null && torScenario != null
    val scenarioKey = stringResource(R.string.cli_home_status_scenario)
    val vpnRow = vpnScenario?.let { scenario ->
        CliTerminalRow(
            key = if (bothLegs) "$scenarioKey · ${stringResource(R.string.cli_st_vpn)}" else scenarioKey,
            value = cliScenarioLabel(scenario),
            tone = CliLineTone.INFO,
        )
    }
    // The Tor leg keeps its own canon label as the qualifier, because it also says HOW it is
    // attached — inside the tunnel, beside it, or alone — and the scenario cannot express that.
    val torRow = torScenario?.let { scenario ->
        CliTerminalRow(
            key = if (bothLegs) {
                "$scenarioKey · ${stringResource(R.string.cli_st_tor)}"
            } else {
                scenarioKey
            },
            value = cliScenarioLabel(scenario),
            tone = CliLineTone.TOR,
        )
    }
    return listOfNotNull(modeRow, vpnRow, torRow)
}

@Composable
private fun cliStatusModeLabel(mode: CliStatusMode): String =
    when (mode) {
        CliStatusMode.VPN -> stringResource(R.string.cli_st_vpn)
        CliStatusMode.TOR -> stringResource(R.string.cli_st_tor)
        CliStatusMode.VPN_TOR -> stringResource(R.string.cli_home_status_mode_vpn_tor)
        CliStatusMode.NONE -> stringResource(R.string.cli_home_status_mode_none)
    }

private fun cliStatusModeTone(mode: CliStatusMode): CliLineTone =
    when (mode) {
        CliStatusMode.VPN, CliStatusMode.TOR, CliStatusMode.VPN_TOR -> CliLineTone.INFO
        CliStatusMode.NONE -> CliLineTone.DIM
    }

@Composable
private fun cliScenarioLabel(scenario: CliStatusScenario): String =
    when (scenario) {
        CliStatusScenario.WHOLE_DEVICE -> stringResource(R.string.cli_home_status_lane_all)
        CliStatusScenario.PROXY_SELECTED -> stringResource(R.string.cli_home_status_scenario_proxy_selected)
        CliStatusScenario.PROXY_EXCEPT_SELECTED -> stringResource(R.string.cli_home_status_scenario_proxy_except)
        CliStatusScenario.PROXY_SERVER -> stringResource(R.string.cli_home_status_scenario_proxy_server)
        CliStatusScenario.PROXY_SERVER_LAN -> stringResource(R.string.cli_home_status_scenario_proxy_server_lan)
    }

/**
 * Is the device-local proxy surface — the loopback SOCKS/HTTP listener with its own credentials —
 * genuinely accepting connections?
 *
 * This reads the traffic mode and nothing else, on purpose. `RuntimeConfigAssembler.assembleProxy`
 * is the ONLY path that emits the user's own local inbound (`includeLocalProxy = true`);
 * `assembleTunnel` passes `false`, so in a tunnel session the single loopback inbound is the app's
 * internal `foxhole-runtime` HTTP proxy, not the user's surface. And `assembleProxy` runs only for
 * [TrafficMode.PROXY], which `Settings.normalized()` migrates to TUNNEL unconditionally — schema 19
 * retired the proxy-only service. So this is false in every reachable configuration today, and that
 * is the correct answer: nothing is listening.
 *
 * Deliberately NOT `activeProxySurface()` / `localSurfaces.*.enabled`. Those are stored preferences,
 * and lighting the label from them would render a toggle as state — the same defect the LAN proxy
 * carried until it grew a real `nativeStartLanProxy` status.
 *
 * THE SEAM: when the core gains named loopback inbounds, publish their state the way the LAN leg
 * does (the runtime bridge's `lanProxyStatus` fed from `native.lanProxyStatus`) and pass that
 * snapshot's `serving` in here instead of the traffic mode.
 */
internal fun cliLocalProxySurfaceServing(connection: ConnectionSnapshot): Boolean =
    connection.state == ConnectionState.CONNECTED &&
        connection.trafficMode == TrafficMode.PROXY &&
        connection.profileId != null &&
        connection.profileId != LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != TOR_ONLY_PROFILE_ID

/**
 * TOR alone / beside the tunnel / inside it — the canon labels of the status line.
 *
 * The inside-the-tunnel case is scope-aware on purpose. After the split→proxy rename, "PROXY"
 * names the per-app mode, so one shared string called a whole-device tunnel a proxy — the exact
 * opposite of what it is. Whole-device and per-app must never share a label here.
 */
/**
 * What the VPN lane row is allowed to claim.
 *
 * [PER_APP] — the include set is what the tunnel carries, so list it. [WHOLE_DEVICE] — the tunnel
 * carries everything it is given: FULL_TUNNEL, and exclude mode, where the pins are exceptions
 * rather than members. [INERT] — the same with nothing running: no route, so no claim at all.
 *
 * Exclude mode used to report PER_APP and print its pins under "in VPN". Those are the apps the
 * runtime hands the tun builder as *excluded* (`RuntimeTunInbound`): the row named the one lane
 * they are not in. Whatever is really kept out is listed by the excluded row, from the same
 * function the runtime uses.
 */
internal enum class CliVpnLaneScope { PER_APP, WHOLE_DEVICE, INERT }

internal fun vpnLaneScope(
    settings: Settings,
    vpnLive: Boolean,
): CliVpnLaneScope =
    when (settings.expert.perAppRoutingMode) {
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> CliVpnLaneScope.PER_APP
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
        -> if (vpnLive) CliVpnLaneScope.WHOLE_DEVICE else CliVpnLaneScope.INERT
    }

/** «These apps → TOR, those → VPN, rest → direct»: the per-lane pins plus the fall-through. */
@Composable
internal fun statusRouteRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    // No route, no rules: with nothing running the lanes shape a configuration, not traffic.
    if (!cliRouteRulesInForce(runtimes)) return emptyList()
    val settings = home.settings
    val assignments = settings.expert.appAssignments
    // With the module off the lane shapes nothing: the pins stay saved, no circuit exists to carry
    // them, and printing them here would advertise a rule that is not in force — the same defect
    // the VPN lane had in whole-device mode.
    val torModule = cliTorLaneVisible(settings)
    // Whole-device TOR names the device instead of listing every package on it.
    val wholeDeviceTor = runtimes.tor && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS
    val torRow = when {
        !torModule -> null
        wholeDeviceTor -> CliTerminalRow(
            key = stringResource(R.string.cli_home_status_lane_tor),
            value = stringResource(R.string.cli_home_status_lane_all),
            tone = CliLineTone.TOR,
        )
        else -> statusLaneRow(
            labelRes = R.string.cli_home_status_lane_tor,
            packages = assignments.lane(AppTunnelLane.TOR),
            tone = CliLineTone.TOR,
        )
    }
    // The VPN lane is the ONE lane a whole-device tunnel really ignores: with FULL_TUNNEL the
    // assembler's vpnIncludedPackages() is empty and the exclude set never sees the selection, so
    // the core routes everything the same way whatever is pinned here. Listing pinned apps in that
    // mode claimed a rule that is not in force — say what actually happens, in the same "whole
    // device" shape the Tor lane already uses for its ALL_APPS scope.
    //
    // The other lanes stay: TOR and EXCLUDE shape the config in every mode (tor route rules /
    // tun bypass). Hiding those would be the same lie in the opposite direction.
    val vpnScope = vpnLaneScope(settings = settings, vpnLive = runtimes.vpn)
    val vpnRow = when (vpnScope) {
        CliVpnLaneScope.WHOLE_DEVICE -> CliTerminalRow(
            key = stringResource(R.string.cli_home_status_lane_vpn),
            value = stringResource(R.string.cli_home_status_lane_all),
            tone = CliLineTone.VPN,
        )
        // Nothing is carrying traffic, so the pins are not in force either.
        CliVpnLaneScope.INERT -> null
        CliVpnLaneScope.PER_APP -> statusLaneRow(
            labelRes = R.string.cli_home_status_lane_vpn,
            packages = assignments.lane(AppTunnelLane.VPN),
            tone = CliLineTone.VPN,
        )
    }
    // Not the EXCLUDE lane but everything really kept out: in exclude mode the selection is out
    // too, and that is the half the pins-under-"in VPN" row used to claim the opposite of.
    val excludeRow = statusLaneRow(
        labelRes = R.string.cli_home_status_lane_exclude,
        packages = settings.expert.tunnelKeptOutPackages().toSet(),
        tone = CliLineTone.DIM,
    )
    // "rest" only means something when something was pinned away from the default: a whole-device
    // lane has already said where everything goes.
    val wholeDevice = wholeDeviceTor || vpnScope == CliVpnLaneScope.WHOLE_DEVICE
    val restRow = if (wholeDevice) null else statusRestRow(home = home, runtimes = runtimes)
    val rulesRow = statusRulesRow(preset = home.activePreset)
    return listOfNotNull(torRow, vpnRow, excludeRow, restRow, rulesRow)
}

private fun Map<String, AppTunnelLane>.lane(lane: AppTunnelLane): Set<String> =
    filterValues { it == lane }.keys

/**
 * May the TOR lane be printed at all?
 *
 * Only while its module is on. The pins survive the switch — they are a saved preference — but with
 * no circuit to carry them they shape nothing, and a row about them would advertise a route that
 * does not exist.
 */
internal fun cliTorLaneVisible(settings: Settings): Boolean = settings.privacyRoute.permitted

/** An empty lane prints nothing: silence is honester than four consecutive "—" rows. */
@Composable
private fun statusLaneRow(
    @StringRes labelRes: Int,
    packages: Set<String>,
    tone: CliLineTone,
): CliTerminalRow? {
    if (packages.isEmpty()) return null
    val sorted = packages.sorted()
    val hidden = (sorted.size - MAX_LANE_ICONS).coerceAtLeast(0)
    return CliTerminalRow(
        key = stringResource(labelRes),
        value = if (hidden > 0) stringResource(R.string.cli_home_status_lane_more, hidden) else null,
        tone = tone,
        packages = sorted.take(MAX_LANE_ICONS),
    )
}

/**
 * Where every app the user did NOT pin ends up. Include-mode tunnels the pinned apps only, so the
 * rest stay direct; full/exclude tunnelling hands them to the live VPN or proxy.
 */
@Composable
private fun statusRestRow(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): CliTerminalRow {
    val includeOnly =
        home.settings.expert.perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS
    val tunnelled = runtimes.vpn && !includeOnly
    val valueRes = when {
        tunnelled && runtimes.proxy -> R.string.cli_rt_proxy
        tunnelled -> R.string.cli_st_vpn
        else -> R.string.cli_home_status_rest_direct
    }
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_rest),
        value = stringResource(valueRes),
        tone = if (tunnelled) CliLineTone.VPN else CliLineTone.DIM,
    )
}

/** The live domain rules of the active preset, folded into one `vpn N · direct N · block N`. */
@Composable
private fun statusRulesRow(preset: RoutingPreset?): CliTerminalRow? {
    val rules = preset?.rules.orEmpty().filter { it.enabled }
    if (rules.isEmpty()) return null
    val viaVpn = rules.count { it.action == RoutingRuleAction.PROXY }
    val direct = rules.count { it.action == RoutingRuleAction.DIRECT }
    val blocked = rules.count { it.action == RoutingRuleAction.BLOCK }
    val summary = listOfNotNull(
        stringResource(R.string.cli_home_status_rules_vpn, viaVpn).takeIf { viaVpn > 0 },
        stringResource(R.string.cli_home_status_rules_direct, direct).takeIf { direct > 0 },
        stringResource(R.string.cli_home_status_rules_block, blocked).takeIf { blocked > 0 },
    ).joinToString(" · ")
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_rules),
        value = summary,
        tone = CliLineTone.INFO,
    )
}

/**
 * The components, briefly: the firewall (with whatever it is blocking), I2P and DNS filtering.
 * These three are reported whenever they are switched on, with or without a route — they are not
 * routes themselves and a device can run nothing but them.
 */
@Composable
internal fun cliStatusModuleRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    val settings = home.settings
    val firewallRows = if (settings.expert.firewallEnabled) {
        val live = cliFirewallLive(settings = settings, connection = home.connection, runtimes = runtimes)
        listOfNotNull(
            CliTerminalRow(
                key = stringResource(R.string.cli_home_status_filter),
                value = if (live) {
                    stringResource(R.string.cli_home_status_filter_active)
                } else {
                    stringResource(R.string.cli_home_status_filter_pending)
                },
                // Reads as a live component rather than a footnote: with no VPN or TOR the firewall
                // is the only thing running, and a dim line there looked like nothing was on.
                tone = if (live) CliLineTone.FIREWALL else CliLineTone.WARN,
            ),
            // Blocking is armed by its own switch: normalization clears it for an empty lane, so a
            // stale assignment map must not advertise a firewall rule the core is not applying.
            statusLaneRow(
                labelRes = R.string.cli_home_status_lane_block,
                packages = settings.expert.appAssignments
                    .lane(AppTunnelLane.BLOCK)
                    .takeIf { settings.expert.blockedPackagesEnabled }
                    .orEmpty(),
                tone = CliLineTone.ERR,
            ),
        )
    } else {
        emptyList()
    }
    return firewallRows + listOfNotNull(statusI2pRow(home = home), statusDnsFilterRow(settings = settings))
}

/**
 * I2P is an overlay network with no exit ip, so the value carries only the router phase. Shown
 * whenever the component is switched on, including while it is not engaged — a component the user
 * turned on has a state worth reporting even when that state is "off".
 *
 * Readiness uses the shared [networkUp] predicate: the CONNECTED phase is unreachable in
 * production, and comparing against it kept the panel forever "starting".
 */
@Composable
private fun statusI2pRow(home: HomeRouteUiState): CliTerminalRow? {
    val i2p = home.settings.i2p
    if (!i2p.enabled) return null
    val engaged = i2p.engaged
    val ready = engaged && home.i2pPhase.phase.networkUp
    return CliTerminalRow(
        // `-R` marks the relay: the router is also forwarding other people's transit traffic, which
        // costs bandwidth and battery, so it has to be visible wherever I2P is reported.
        key = stringResource(R.string.cli_st_i2p) + if (i2p.relayTransitTraffic) I2P_RELAY_SUFFIX else "",
        value = when {
            ready -> stringResource(R.string.cli_home_status_i2p_ready)
            engaged -> stringResource(R.string.cli_home_status_i2p_starting)
            else -> stringResource(R.string.cli_home_status_i2p_off)
        },
        tone = when {
            ready -> CliLineTone.I2P
            engaged -> CliLineTone.WARN
            else -> CliLineTone.DIM
        },
    )
}

/** DNS filtering in one line — printed only while it is on: an off filter is not a module state
 * worth a row in the short readout. */
@Composable
private fun statusDnsFilterRow(settings: Settings): CliTerminalRow? {
    val categories = settings.dns.enabledDnsFilterCategories().size
    val on = settings.dns.filteringEnabled && categories > 0
    if (!on) return null
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_dns_filter),
        value = String.format(Locale.US, stringResource(R.string.cli_home_status_dns_filter_on), categories),
        tone = CliLineTone.DNS_FILTER,
    )
}

/**
 * Informational notes at the tail of the status: facts worth knowing that are neither a route nor
 * a module state. Today: the tunnel resolves through the configured provider because the VPN
 * profile advertises no resolver of its own (same condition as the connect-time banner in
 * [com.foxhole.guard.ui.observeDnsNoticesInternal]).
 */
@Composable
internal fun cliStatusInfoRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
    includePendingPackages: Boolean = false,
): List<CliTerminalRow> {
    val providerInfo = providerDnsFallbackInfo(home = home, runtimes = runtimes)?.let { info ->
        CliTerminalRow(
            key = stringResource(R.string.cli_home_status_info),
            value = info,
            tone = CliLineTone.INFO,
        )
    }
    val pending = home.settings.expert.pendingQuarantinePackages
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    val pendingCount = pending.takeIf(List<String>::isNotEmpty)?.let { packages ->
        CliTerminalRow(
            key = stringResource(R.string.cli_home_status_info),
            value = pluralStringResource(
                R.plurals.cli_firewall_action_required_status,
                packages.size,
                packages.size,
            ),
            tone = CliLineTone.INFO,
        )
    }
    val pendingPackages = pending.takeIf { packages -> includePendingPackages && packages.isNotEmpty() }?.let { packages ->
        CliTerminalRow(
            key = stringResource(
                R.string.cli_firewall_action_required_packages,
                compactPendingPackages(packages),
            ),
            keyTone = CliLineTone.INFO,
            tone = CliLineTone.INFO,
        )
    }
    return listOfNotNull(providerInfo, pendingCount, pendingPackages)
}

internal fun compactPendingPackages(packages: List<String>): String {
    val normalized = packages.map(String::trim).filter(String::isNotBlank).distinct()
    val visible = normalized.take(MAX_PENDING_STATUS_PACKAGES)
    val rest = normalized.size - visible.size
    return visible.joinToString(" · ") + if (rest > 0) " · +$rest" else ""
}

@Composable
private fun providerDnsFallbackInfo(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): String? {
    val settings = home.settings
    // Все четыре условия — про одно: провайдерский DNS обещан, но этот протокол его не отдаёт.
    // Одно выражение вместо лестницы выходов: порядок тот же, а читается как один вопрос.
    val fallbackShown = runtimes.vpn &&
        settings.dns.useVpnProviderDns &&
        home.connection.trafficMode == TrafficMode.TUNNEL &&
        home.connection.protocolHint in com.foxhole.guard.ui.PROVIDER_DNS_INCAPABLE_PROTOCOLS
    if (!fallbackShown) return null
    val server = settings.dns.server.trim().takeIf { it.isNotBlank() } ?: return null
    return stringResource(R.string.cli_home_status_info_provider_dns, server)
}

// Shown after the I2P label when transit relaying is on.
private const val I2P_RELAY_SUFFIX = " -R"
private const val MAX_PENDING_STATUS_PACKAGES = 3
