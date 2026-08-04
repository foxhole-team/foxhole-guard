package com.foxhole.guard.ui.cli.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState

// The app list occupies one log line: more than three names will not fit at 360dp, so the tail
// collapses into "+N".
private const val MAX_LANE_APPS = 3

/**
 * The `status` answer: what is actually running, as key/value pairs printed line by line. No
 * modal — the block goes to the same log as everything else, so the only graphic allowed is the
 * country flag.
 *
 * One row per live runtime (VPN, proxy, TOR standalone, TOR-in-VPN, TOR beside it, I2P) with its
 * own exit ip and geo, then a short summary of what goes where: each lane's pinned apps, where the
 * rest falls through, and the live site rules.
 *
 * The firewall is never a runtime here. It raises its own VpnService (published as CONNECTED under
 * [LOCAL_GUARD_PROFILE_ID]) but routes nothing — it only filters traffic already flowing. So it
 * lands as one dim `filter` note, and it is turned on in settings: this block has no control for
 * it.
 */
@Composable
internal fun cliStatusRows(
    home: HomeRouteUiState,
    torOnlyLive: Boolean,
): List<CliTerminalRow> {
    val runtimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    val runtimeRows = statusRuntimeRows(home = home, runtimes = runtimes)
    val routeRows = statusRouteRows(home = home, runtimes = runtimes)
    val rulesRow = statusRulesRow(preset = home.activePreset)
    val filterRow = statusFilterRow(firewallEnabled = home.settings.expert.firewallEnabled)
    return runtimeRows + routeRows + listOfNotNull(rulesRow, filterRow)
}

/**
 * The routes that actually carry traffic. The local guard is deliberately absent: it is a filter,
 * not a route, so a firewall-only device reports every flag here as false — «no connection».
 */
private data class CliActiveRuntimes(
    val vpn: Boolean,
    val proxy: Boolean,
    val tor: Boolean,
    val torBesideVpn: Boolean,
    val i2p: Boolean,
) {
    val any: Boolean
        get() = vpn || tor || i2p
}

private fun activeRuntimes(
    home: HomeRouteUiState,
    torOnlyLive: Boolean,
): CliActiveRuntimes {
    val connection = home.connection
    val settings = home.settings
    // A profile runtime — the sentinel ids are the firewall and the standalone TOR session, and
    // neither is a VPN/proxy egress.
    val profileLive = connection.state == ConnectionState.CONNECTED &&
        connection.profileId != null &&
        connection.profileId != LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != TOR_ONLY_PROFILE_ID
    val tor = (connection.state == ConnectionState.CONNECTED && connection.torActive) || torOnlyLive
    return CliActiveRuntimes(
        vpn = profileLive,
        proxy = profileLive && connection.trafficMode == TrafficMode.PROXY,
        tor = tor,
        // bypassVpnTunnel: TOR dials out NEXT to the tunnel instead of through it.
        torBesideVpn = tor && profileLive && settings.privacyRoute.bypassVpnTunnel,
        i2p = settings.i2p.enabled && settings.i2p.engaged,
    )
}

/** One row per live runtime: the mode as the key, its own exit ip + geo as the value. */
@Composable
private fun statusRuntimeRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    if (!runtimes.any) {
        return listOf(
            CliTerminalRow(
                key = stringResource(R.string.cli_home_status_route),
                value = stringResource(R.string.cli_home_status_mode_none),
                tone = CliLineTone.DIM,
            ),
        )
    }
    // With TOR inside the tunnel the app's own probe exits through TOR, so `ipInfo` is either the
    // TOR exit or the pre-connect device address — never the tunnel's own exit. Claiming it as the
    // VPN exit printed the device ip next to the VPN label; the address is simply not observable
    // from here, so the row says so. TOR beside the tunnel keeps a real measurement.
    val vpnExitObservable = !runtimes.tor || runtimes.torBesideVpn
    val vpnRow = if (runtimes.vpn) {
        statusExitRow(
            label = stringResource(if (runtimes.proxy) R.string.cli_rt_proxy else R.string.cli_st_vpn),
            ipInfo = home.ipInfo.takeIf { vpnExitObservable },
            tone = CliLineTone.VPN,
        )
    } else {
        null
    }
    val torRow = if (runtimes.tor) {
        statusExitRow(
            label = stringResource(torRuntimeLabelRes(runtimes)),
            ipInfo = home.torIpInfo,
            tone = CliLineTone.TOR,
        )
    } else {
        null
    }
    val i2pRow = if (runtimes.i2p) statusI2pRow(home = home) else null
    return listOfNotNull(vpnRow, torRow, i2pRow)
}

/**
 * I2P is an overlay network with no exit ip, so the value carries only the router phase.
 * Readiness uses the shared [networkUp] predicate: the CONNECTED phase is unreachable in
 * production, and comparing against it kept the panel forever "starting".
 */
@Composable
private fun statusI2pRow(home: HomeRouteUiState): CliTerminalRow =
    CliTerminalRow(
        key = stringResource(R.string.cli_st_i2p),
        value = stringResource(
            if (home.i2pPhase.phase.networkUp) {
                R.string.cli_home_status_i2p_ready
            } else {
                R.string.cli_home_status_i2p_starting
            },
        ),
        tone = CliLineTone.ACCENT,
    )

/** TOR alone / beside the tunnel / inside it — the canon labels of the status line. */
@StringRes
private fun torRuntimeLabelRes(runtimes: CliActiveRuntimes): Int =
    when {
        !runtimes.vpn -> R.string.cli_st_tor
        runtimes.torBesideVpn -> R.string.cli_st_vpn_tor
        else -> R.string.cli_st_tor_in_vpn
    }

@Composable
private fun statusExitRow(
    label: String,
    ipInfo: IpInfo?,
    tone: CliLineTone,
): CliTerminalRow {
    val exit = ipInfo
        ?.let { info ->
            listOfNotNull(
                info.ip.takeIf { it.isNotBlank() },
                info.countryCode?.takeIf { it.isNotBlank() }?.uppercase(),
                info.city?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
        ?.takeIf { it.isNotBlank() }
    return CliTerminalRow(
        key = label,
        value = exit ?: stringResource(R.string.cli_home_status_no_exit),
        tone = if (exit == null) CliLineTone.DIM else tone,
        flagCountry = if (exit == null) null else ipInfo.countryCode,
    )
}

/** «These apps → TOR, those → VPN, rest → direct»: the per-lane pins plus the fall-through. */
@Composable
private fun statusRouteRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    val settings = home.settings
    val assignments = settings.expert.appAssignments
    // An index rather than firstOrNull per row: the block lists packages of every lane.
    val installedIndex = home.installedApps.associateBy { it.packageName }
    val labelOf: (String) -> String = { pkg ->
        installedIndex[pkg]?.label?.ifBlank { pkg } ?: pkg
    }
    // Whole-device TOR names the device instead of listing every package on it.
    val wholeDeviceTor = runtimes.tor && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS
    val torRow = if (wholeDeviceTor) {
        CliTerminalRow(
            key = stringResource(R.string.cli_home_status_lane_tor),
            value = stringResource(R.string.cli_home_status_lane_all),
            tone = CliLineTone.TOR,
        )
    } else {
        statusLaneRow(
            labelRes = R.string.cli_home_status_lane_tor,
            packages = assignments.lane(AppTunnelLane.TOR),
            tone = CliLineTone.TOR,
            labelOf = labelOf,
        )
    }
    val vpnRow = statusLaneRow(
        labelRes = R.string.cli_home_status_lane_vpn,
        packages = assignments.lane(AppTunnelLane.VPN),
        tone = CliLineTone.VPN,
        labelOf = labelOf,
    )
    val blockRow = statusLaneRow(
        labelRes = R.string.cli_home_status_lane_block,
        packages = assignments.lane(AppTunnelLane.BLOCK),
        tone = CliLineTone.ERR,
        labelOf = labelOf,
    )
    val excludeRow = statusLaneRow(
        labelRes = R.string.cli_home_status_lane_exclude,
        packages = assignments.lane(AppTunnelLane.EXCLUDE),
        tone = CliLineTone.DIM,
        labelOf = labelOf,
    )
    val restRow = if (wholeDeviceTor) null else statusRestRow(home = home, runtimes = runtimes)
    return listOfNotNull(torRow, vpnRow, blockRow, excludeRow, restRow)
}

private fun Map<String, AppTunnelLane>.lane(lane: AppTunnelLane): Set<String> =
    filterValues { it == lane }.keys

/** An empty lane prints nothing: silence is honester than four consecutive "—" rows. */
@Composable
private fun statusLaneRow(
    @StringRes labelRes: Int,
    packages: Set<String>,
    tone: CliLineTone,
    labelOf: (String) -> String,
): CliTerminalRow? {
    if (packages.isEmpty()) return null
    return CliTerminalRow(
        key = stringResource(labelRes),
        value = laneAppsValue(names = packages.sorted().map(labelOf)),
        tone = tone,
    )
}

/** Three names then "+N": the value lives on one log line, and a long list would ellipsize. */
@Composable
private fun laneAppsValue(names: List<String>): String {
    val hidden = (names.size - MAX_LANE_APPS).coerceAtLeast(0)
    val shown = names.take(MAX_LANE_APPS).joinToString(", ")
    val more = stringResource(R.string.cli_home_status_lane_more, hidden)
    return if (hidden > 0) "$shown $more" else shown
}

/**
 * Where every app the user did NOT pin ends up. Include-mode tunnels the pinned apps only, so the
 * rest stay direct; full/exclude tunnelling hands them to the live VPN or proxy. With no route at
 * all everything is direct — the firewall filters that traffic, it never carries it.
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

/** The firewall's only appearance on home: a dim note that the filter is on, never a connection. */
@Composable
private fun statusFilterRow(firewallEnabled: Boolean): CliTerminalRow? {
    if (!firewallEnabled) return null
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_filter),
        value = stringResource(R.string.cli_home_status_filter_active),
        tone = CliLineTone.DIM,
    )
}
