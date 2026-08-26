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

private const val MAX_LANE_ICONS = 6

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

internal data class CliActiveRuntimes(
    val vpn: Boolean,
    val proxy: Boolean,
    val tor: Boolean,
    val torBesideVpn: Boolean,
    val i2p: Boolean,
    val torScope: PrivacyRouteScope? = null,
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
        torBesideVpn =
        tor &&
            profileLive &&
            connection.appliedTorRoute?.bypassVpnTunnel == true,
        i2p = settings.i2p.enabled && settings.i2p.engaged,
        torScope = connection.appliedTorRoute?.scope,
    )
}

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
    val torScenario =
        cliTorScenario(
            torLive = runtimes.tor,
            appliedScope = runtimes.torScope,
        )
    val bothLegs = vpnScenario != null && torScenario != null
    val scenarioKey = stringResource(R.string.cli_home_status_scenario)
    val vpnRow = vpnScenario?.let { scenario ->
        CliTerminalRow(
            key = if (bothLegs) "$scenarioKey · ${stringResource(R.string.cli_st_vpn)}" else scenarioKey,
            value = cliScenarioLabel(scenario),
            tone = CliLineTone.INFO,
        )
    }
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

internal fun cliLocalProxySurfaceServing(connection: ConnectionSnapshot): Boolean =
    connection.state == ConnectionState.CONNECTED &&
        connection.trafficMode == TrafficMode.PROXY &&
        connection.profileId != null &&
        connection.profileId != LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != TOR_ONLY_PROFILE_ID

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

@Composable
internal fun statusRouteRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    if (!cliRouteRulesInForce(runtimes)) return emptyList()
    val settings = home.settings
    val assignments = settings.expert.appAssignments
    val torModule = cliTorLaneVisible(settings)
    val wholeDeviceTor = runtimes.tor && runtimes.torScope == PrivacyRouteScope.ALL_APPS
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
    val vpnScope = vpnLaneScope(settings = settings, vpnLive = runtimes.vpn)
    val vpnRow = when (vpnScope) {
        CliVpnLaneScope.WHOLE_DEVICE -> CliTerminalRow(
            key = stringResource(R.string.cli_home_status_lane_vpn),
            value = stringResource(R.string.cli_home_status_lane_all),
            tone = CliLineTone.VPN,
        )
        CliVpnLaneScope.INERT -> null
        CliVpnLaneScope.PER_APP -> statusLaneRow(
            labelRes = R.string.cli_home_status_lane_vpn,
            packages = assignments.lane(AppTunnelLane.VPN),
            tone = CliLineTone.VPN,
        )
    }
    val excludeRow = statusLaneRow(
        labelRes = R.string.cli_home_status_lane_exclude,
        packages = settings.expert.tunnelKeptOutPackages().toSet(),
        tone = CliLineTone.DIM,
    )
    val wholeDevice = wholeDeviceTor || vpnScope == CliVpnLaneScope.WHOLE_DEVICE
    val restRow = if (wholeDevice) null else statusRestRow(home = home, runtimes = runtimes)
    val rulesRow = statusRulesRow(preset = home.activePreset)
    return listOfNotNull(torRow, vpnRow, excludeRow, restRow, rulesRow)
}

private fun Map<String, AppTunnelLane>.lane(lane: AppTunnelLane): Set<String> =
    filterValues { it == lane }.keys

internal fun cliTorLaneVisible(settings: Settings): Boolean = settings.privacyRoute.permitted

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

@Composable
private fun statusRulesRow(preset: RoutingPreset?): CliTerminalRow? {
    val rules = preset?.rules.orEmpty().filter { it.enabled }
    if (rules.isEmpty()) return null
    val viaVpn = rules.count { it.action == RoutingRuleAction.PROXY }
    val viaTor = rules.count { it.action == RoutingRuleAction.TOR }
    val direct = rules.count { it.action == RoutingRuleAction.DIRECT }
    val blocked = rules.count { it.action == RoutingRuleAction.BLOCK }
    val summary = listOfNotNull(
        stringResource(R.string.cli_home_status_rules_vpn, viaVpn).takeIf { viaVpn > 0 },
        stringResource(R.string.cli_home_status_rules_tor, viaTor).takeIf { viaTor > 0 },
        stringResource(R.string.cli_home_status_rules_direct, direct).takeIf { direct > 0 },
        stringResource(R.string.cli_home_status_rules_block, blocked).takeIf { blocked > 0 },
    ).joinToString(" · ")
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_rules),
        value = summary,
        tone = CliLineTone.INFO,
    )
}

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
                tone = if (live) CliLineTone.FIREWALL else CliLineTone.WARN,
            ),
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

@Composable
private fun statusI2pRow(home: HomeRouteUiState): CliTerminalRow? {
    val i2p = home.settings.i2p
    if (!i2p.enabled) return null
    val engaged = i2p.engaged
    val ready = engaged && home.i2pPhase.phase.networkUp
    return CliTerminalRow(
        key = stringResource(R.string.cli_st_i2p) + if (i2p.relayTransitTraffic) I2P_RELAY_SUFFIX else "",
        value = when {
            ready -> stringResource(R.string.cli_home_status_i2p_ready)
            engaged -> stringResource(R.string.cli_home_status_i2p_starting)
            else -> stringResource(R.string.cli_home_status_i2p_off)
        },
        tone = when {
            ready -> CliLineTone.I2P
            engaged -> CliLineTone.PENDING
            else -> CliLineTone.DIM
        },
    )
}

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

@Composable
internal fun cliStatusInfoRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
    includePendingPackages: Boolean = false,
): List<CliTerminalRow> {
    val providerEvent = stringResource(R.string.cli_home_status_info_event_provider_dns)
    val firewallEvent = stringResource(R.string.cli_home_status_info_event_firewall)
    val providerRows = providerDnsFallbackServer(home = home, runtimes = runtimes)?.let { server ->
        listOf(
            CliTerminalRow(
                key = providerEvent,
                value = stringResource(R.string.cli_home_status_info_value_provider_dns, server),
                tone = CliLineTone.INFO,
                keyTone = CliLineTone.INFO,
                inlineValue = true,
            ),
        )
    }.orEmpty()
    val pending = home.settings.expert.pendingQuarantinePackages
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    val pendingRows = pending.takeIf(List<String>::isNotEmpty)?.let { packages ->
        cliInfoNoticeRows(
            event = firewallEvent,
            description = pluralStringResource(
                R.plurals.cli_home_status_info_value_apps,
                packages.size,
                packages.size,
            ),
            kind = stringResource(R.string.cli_home_status_info_kind, firewallEvent),
        )
    }.orEmpty()
    val pendingPackages = pending.takeIf { packages -> includePendingPackages && packages.isNotEmpty() }?.let { packages ->
        CliTerminalRow(
            key = stringResource(R.string.cli_home_status_info_event_pending_apps),
            value = compactPendingPackages(packages),
            keyTone = CliLineTone.INFO,
            tone = CliLineTone.INFO,
            valueLeading = true,
            inlineValue = true,
        )
    }
    return providerRows + pendingRows + listOfNotNull(pendingPackages)
}

internal fun cliInfoNoticeRows(
    event: String,
    description: String,
    kind: String,
): List<CliTerminalRow> = listOf(
    CliTerminalRow(
        key = event,
        value = description,
        tone = CliLineTone.INFO,
        keyTone = CliLineTone.INFO,
        valueLeading = true,
        inlineValue = true,
    ),
    CliTerminalRow(
        key = kind,
        tone = CliLineTone.INFO,
        keyTone = CliLineTone.INFO,
        typed = true,
    ),
)

internal fun compactPendingPackages(packages: List<String>): String {
    val normalized = packages.map(String::trim).filter(String::isNotBlank).distinct()
    val visible = normalized.take(MAX_PENDING_STATUS_PACKAGES)
    val rest = normalized.size - visible.size
    return visible.joinToString(" · ") + if (rest > 0) " · +$rest" else ""
}

private fun providerDnsFallbackServer(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): String? {
    val settings = home.settings
    val fallbackShown = runtimes.vpn &&
        settings.dns.useVpnProviderDns &&
        home.connection.trafficMode == TrafficMode.TUNNEL &&
        home.connection.protocolHint in com.foxhole.guard.ui.PROVIDER_DNS_INCAPABLE_PROTOCOLS
    if (!fallbackShown) return null
    return settings.dns.server.trim().takeIf { it.isNotBlank() }
}

private const val I2P_RELAY_SUFFIX = " -R"
private const val MAX_PENDING_STATUS_PACKAGES = 3
