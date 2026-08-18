package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.core.model.packages
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.StatisticsRouteUiState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliDashedInfoNote
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliGlassHeaderScreen
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliSectionPreloader
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.components.rememberNowMsTicker
import com.foxhole.guard.ui.cli.home.activeRuntimes
import com.foxhole.guard.ui.cli.home.isTorOnlyLive
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import com.foxhole.guard.ui.onStatisticsEnabledChanged
import com.foxhole.guard.ui.onStatisticsWindowChanged
import com.foxhole.guard.ui.toStatisticsUiNowBucket

@Composable
internal fun CliStatsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.statisticsRouteState.collectAsStateWithLifecycle()
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    if (settingsOpen && state.settingsHydrated) {
        CliStatsSettingsSheet(
            viewModel = viewModel,
            settings = state.settings,
            onDismiss = { settingsOpen = false },
        )
    }
    val actions = remember(viewModel) {
        CliStatsActions(
            openSettings = { settingsOpen = true },
            enableStatistics = { viewModel.onStatisticsEnabledChanged(true) },
            selectWindow = viewModel::onStatisticsWindowChanged,
        )
    }
    CliStatsContent(state = state, home = home, actions = actions, modifier = modifier)
}

@Composable
internal fun CliStatsContent(
    state: StatisticsRouteUiState,
    home: HomeRouteUiState,
    actions: CliStatsActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val dashboard = state.statisticsDashboard
    CliGlassHeaderScreen(
        modifier = modifier,
        header = {
            CliScreenHeader(
                label = stringResource(R.string.cli_dock_stats),
                icon = R.drawable.pix_stats,
                trailing = {
                    CliStatsSettingsButton(
                        enabled = state.settingsHydrated,
                        onClick = actions.openSettings,
                    )
                },
            )
        },
    ) { topInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CliSpacing.md),
        ) {
            if (!state.settingsHydrated) {
                CliSectionPreloader(
                    text = stringResource(R.string.cli_common_loading_settings),
                    modifier = Modifier.padding(top = topInset),
                )
                return@Column
            }
            if (state.settings.statistics.enabled && !dashboard.ready) {
                CliSectionPreloader(
                    text = stringResource(R.string.cli_stats_collecting),
                    modifier = Modifier.padding(top = topInset),
                )
                return@Column
            }
            if (!state.settings.statistics.enabled) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = topInset),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.cli_stats_consent_body),
                            style = CliType.body,
                            color = colors.dim,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(CliSpacing.sm))
                        CliDashedInfoNote(
                            text = stringResource(R.string.cli_stats_consent_note),
                            centered = true,
                        )
                        Spacer(modifier = Modifier.height(CliSpacing.sm))
                        CliButton(
                            label = stringResource(R.string.cli_stats_enable_module),
                            onClick = actions.enableStatistics,
                        )
                    }
                }
                return@Column
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(modifier = Modifier.height(topInset))
                CliStatsBody(state = state, home = home, onSelectWindow = actions.selectWindow)
                CliChromeTailSpacer()
            }
        }
    }
}

internal data class CliStatsActions(
    val openSettings: () -> Unit,
    val enableStatistics: () -> Unit,
    val selectWindow: (StatisticsWindow) -> Unit,
)

@Composable
private fun CliStatsSettingsButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCliColors.current
    Box(
        modifier = Modifier
            .requiredSize(SETTINGS_BUTTON_SIZE)
            .offset(x = SETTINGS_BUTTON_EDGE_SHIFT)
            .cliPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_settings,
            contentDescription = stringResource(R.string.cli_stats_settings_title),
            size = SETTINGS_BUTTON_GLYPH_SIZE,
            tint = if (enabled) colors.accent else colors.faint,
        )
    }
}

@Composable
private fun CliStatsBody(
    state: StatisticsRouteUiState,
    home: HomeRouteUiState,
    onSelectWindow: (StatisticsWindow) -> Unit,
) {
    val hero = remember(
        state.appTrafficWindows,
        state.trafficWindows,
        state.i2pTrafficHistory,
        state.statisticsDashboard.nowMs,
    ) {
        cliStatsHero(
            windows = state.appTrafficWindows,
            deviceWindows = state.trafficWindows,
            i2pBuckets = state.i2pTrafficHistory.buckets,
            nowMs = state.statisticsDashboard.nowMs,
        )
    }
    val overview = remember(
        state.appTrafficWindows,
        state.protocolMetricEvents,
        state.settings,
        state.statisticsDashboard,
        state.trafficWindows,
        state.i2pTrafficHistory,
    ) {
        cliStatsOverview(
            window = state.settings.ui.statisticsWindow,
            appTrafficWindows = state.appTrafficWindows,
            metricEvents = state.protocolMetricEvents,
            settings = state.settings,
            lifetimeTotal = state.statisticsDashboard.statistics.total,
            lifetimeProtocols = state.statisticsDashboard.statistics.vpnProtocols,
            nowMs = state.statisticsDashboard.nowMs,
            deviceWindows = state.trafficWindows,
            i2pBuckets = state.i2pTrafficHistory.buckets,
        )
    }
    val runtimes = activeRuntimes(home = home, torOnlyLive = isTorOnlyLive(home))
    val chartLanes = cliStatsChartLanes(
        overview = overview,
        settings = state.settings,
        vpnActive = runtimes.vpn,
        torActive = runtimes.tor,
        i2pActive = runtimes.i2p,
    )
    val vpnTables = remember(
        state.settings.ui.statisticsWindow,
        state.trafficWindows,
        state.protocolMetricEvents,
        state.settings.profileTrafficTotals,
        state.statisticsDashboard.nowMs,
    ) {
        cliStatsVpnTables(
            window = state.settings.ui.statisticsWindow,
            deviceWindows = state.trafficWindows,
            metricEvents = state.protocolMetricEvents,
            profileTrafficTotals = state.settings.profileTrafficTotals,
            nowMs = state.statisticsDashboard.nowMs,
        )
    }
    Column {
        CliStatsOverviewPanel(
            overview = overview,
            lanes = chartLanes,
            onSelectWindow = onSelectWindow,
        )
        CliStatsFirewallGroup(state = state)
        CliStatsProfileTablePanel(state.statisticsDashboard.statistics.profileTraffic)
        CliStatsVpnProtocolTablePanel(
            window = state.settings.ui.statisticsWindow,
            rows = vpnTables.protocols,
            onSelectWindow = onSelectWindow,
        )
        CliStatsVpnTransportTablePanel(
            window = state.settings.ui.statisticsWindow,
            rows = vpnTables.transports,
            onSelectWindow = onSelectWindow,
        )
        CliStatsDnsPanel(state)
        CliStatsAppPanel(state)
        CliStatsTorPanel(state = state, home = home, hero = hero)
        CliStatsI2pPanel(state = state, home = home)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

internal fun cliStatsChartLanes(
    overview: CliStatsOverview,
    settings: Settings,
    vpnActive: Boolean,
    torActive: Boolean,
    i2pActive: Boolean,
): CliStatsChartLanes = CliStatsChartLanes(
    vpn = vpnActive || overview.vpnBytes > 0L,
    tor = torActive || settings.privacyRoute.enabled || overview.torBytes > 0L,
    i2p = i2pActive || (settings.i2p.enabled && settings.i2p.engaged) || overview.i2pBytes > 0L,
    firewall = settings.expert.firewallEnabled ||
        overview.buckets.any { bucket -> bucket.firewallBytes > 0L },
)

@Composable
private fun CliStatsOverviewPanel(
    overview: CliStatsOverview,
    lanes: CliStatsChartLanes,
    onSelectWindow: (StatisticsWindow) -> Unit,
) {
    val colors = LocalCliColors.current
    val dayLabel = stringResource(R.string.cli_stats_range_day)
    val weekLabel = stringResource(R.string.cli_stats_range_week)
    val monthLabel = stringResource(R.string.cli_stats_range_month)
    CliPanel(
        title = stringResource(R.string.cli_stats_overview_title),
        icon = R.drawable.pix_stats,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliDropdownRow(
            label = stringResource(R.string.cli_stats_key_range),
            value = statisticsWindowLabel(overview.window, dayLabel, weekLabel, monthLabel),
            options = StatisticsWindow.entries.map { window ->
                CliDropdownOption(
                    id = window.name,
                    label = statisticsWindowLabel(window, dayLabel, weekLabel, monthLabel),
                )
            },
            selectedId = overview.window.name,
            onSelect = { id -> onSelectWindow(StatisticsWindow.valueOf(id)) },
        )
        if (lanes.vpn) {
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_vpn_traffic),
                value = CliFormat.bytes(overview.vpnBytes),
                valueColor = colors.vpn,
            )
        }
        if (lanes.tor) {
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_tor_traffic),
                value = CliFormat.bytes(overview.torBytes),
                valueColor = colors.tor,
            )
        }
        if (lanes.i2p) {
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_i2p_traffic),
                value = CliFormat.bytes(overview.i2pBytes),
                valueColor = colors.i2p,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliStatsSparkline(
            buckets = overview.buckets,
            lanes = lanes,
            axis = overview.axis,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        overview.peakRateBytesPerSec?.let { peak ->
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_peak),
                value = stringResource(R.string.cli_stats_peak_value, CliFormat.bytes(peak)),
            )
        }
        if (overview.appsWithTraffic > 0) {
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_apps_window),
                value = overview.appsWithTraffic.toString(),
            )
        }
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_avg_latency),
            value = statsSourceValue(overview.latencySource, CliFormat.latency(overview.avgLatencyMs)),
            valueColor = if (overview.latencySource == CliStatsOverviewSource.NONE) colors.dim else colors.fg,
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_vpn_errors),
            value = statsSourceValue(overview.errorSource, CliFormat.percent(overview.vpnErrorRate)),
            valueColor = when {
                overview.errorSource == CliStatsOverviewSource.NONE -> colors.dim
                overview.vpnFailures > 0 -> colors.warn
                else -> colors.ok
            },
        )
        CliRowDivider()
        CliStatsProtocolErrorRow(
            key = stringResource(R.string.cli_stats_key_errors_worst),
            entry = overview.worstProtocol,
            source = overview.errorSource,
        )
        CliRowDivider()
        CliStatsProtocolErrorRow(
            key = stringResource(R.string.cli_stats_key_errors_best),
            entry = overview.bestProtocol,
            source = overview.errorSource,
        )
        if (overview.latencySource == CliStatsOverviewSource.LIFETIME ||
            overview.errorSource == CliStatsOverviewSource.LIFETIME
        ) {
            CliElbowLine(text = stringResource(R.string.cli_stats_lifetime_note), color = colors.warn)
        }
        if (overview.windowExceedsRetention) {
            CliElbowLine(text = stringResource(R.string.cli_stats_retention_short_note), color = colors.warn)
        }
    }
}

@Composable
private fun CliStatsProtocolErrorRow(
    key: String,
    entry: CliStatsProtocolErrors?,
    source: CliStatsOverviewSource,
) {
    val colors = LocalCliColors.current
    CliKeyValue(
        key = key,
        value = if (entry == null) {
            stringResource(R.string.cli_stats_source_none)
        } else {
            statsSourceValue(source, entry.protocol)
        },
        valueColor = if (entry == null) colors.dim else colors.fg,
    )
}

@Composable
private fun statsSourceValue(
    source: CliStatsOverviewSource,
    windowValue: String,
): String =
    when (source) {
        CliStatsOverviewSource.WINDOW -> windowValue
        CliStatsOverviewSource.LIFETIME ->
            "$windowValue · " + stringResource(R.string.cli_stats_source_lifetime)
        CliStatsOverviewSource.NONE -> stringResource(R.string.cli_stats_source_none)
    }

internal fun statisticsWindowLabel(
    window: StatisticsWindow,
    dayLabel: String,
    weekLabel: String,
    monthLabel: String,
): String =
    when (window) {
        StatisticsWindow.DAY -> dayLabel
        StatisticsWindow.WEEK -> weekLabel
        StatisticsWindow.MONTH -> monthLabel
    }

@Composable
private fun CliStatsFirewallGroup(state: StatisticsRouteUiState) {
    if (!state.settings.expert.firewallEnabled) return
    CliStatsFirewallPanel(state)
}

@Composable
private fun CliStatsFirewallPanel(state: StatisticsRouteUiState) {
    val colors = LocalCliColors.current
    val expert = state.settings.expert
    val blockedPackages = if (expert.blockedPackagesEnabled) expert.blockedLanePackages() else emptyList()
    val nowMs = state.statisticsDashboard.nowMs.toStatisticsUiNowBucket()
    val rows = remember(state.networkActivityEvents, blockedPackages, nowMs) {
        cliBlockedAppRows(state.networkActivityEvents, blockedPackages, nowMs)
    }
    val attempts = rows.sumOf { it.attempts }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        icon = R.drawable.pix_fire,
        title = stringResource(R.string.cli_stats_firewall_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_fw_blocked_apps),
            value = blockedPackages.size.toString(),
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_fw_attempts),
            value = attempts.toString(),
            valueColor = if (attempts > 0) colors.warn else colors.ok,
        )
        val installedIndex = remember(state.installedApps) {
            state.installedApps.associateBy { it.packageName }
        }
        rows.take(FIREWALL_ROWS_MAX).forEach { row ->
            CliRowDivider()
            CliKeyValue(key = appLabel(installedIndex, row.packageName), value = row.attempts.toString())
        }
    }
}

@Composable
private fun CliStatsDnsPanel(state: StatisticsRouteUiState) {
    if (!state.settings.dns.dnsRuleSetFilteringEnabled()) return
    val colors = LocalCliColors.current
    val dns = state.statisticsDashboard.dnsSummary
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        title = stringResource(R.string.cli_stats_dns_title),
        icon = R.drawable.pix_dns,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_dns_blocked),
            value = "${dns.blockedQueries} (${CliFormat.percent(dns.blockRatio)})",
            valueColor = colors.accent,
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_dns_allowed),
            value = dns.allowedQueries.toString(),
        )
        val categoryRows = remember(dns.categoryRows) {
            dns.categoryRows
                .filter { it.blockedQueries > 0 }
                .sortedByDescending { it.blockedQueries }
        }
        categoryRows.forEach { row ->
            CliRowDivider()
            CliKeyValue(key = row.category.name.lowercase(), value = row.blockedQueries.toString())
        }
        val appRows = remember(dns.appRows) {
            dns.appRows
                .filter { it.estimatedBlockedQueries > 0 }
                .sortedByDescending { it.estimatedBlockedQueries }
                .take(DNS_APP_ROWS_MAX)
        }
        appRows.forEach { row ->
            CliRowDivider()
            CliKeyValue(
                key = "  ${row.label.ifEmpty { row.packageName }}",
                value = row.estimatedBlockedQueries.toString(),
                valueColor = colors.dim,
            )
        }
    }
}

@Composable
private fun CliStatsTorPanel(
    state: StatisticsRouteUiState,
    home: HomeRouteUiState,
    hero: CliStatsHero,
) {
    val torPhase = home.torPhase
    if (torPhase.phase == TorNetworkPhase.OFFLINE && hero.torBytes24h <= 0L) return
    val colors = LocalCliColors.current
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        title = stringResource(R.string.cli_stats_tor_title),
        icon = R.drawable.pix_tor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_tor_phase),
            value = torPhase.phase.name.lowercase(),
            valueColor = if (torPhase.phase == TorNetworkPhase.CONNECTED) colors.ok else colors.warn,
        )
        if (torPhase.phase != TorNetworkPhase.CONNECTED) {
            torPhase.progress?.let { progress ->
                CliRowDivider()
                CliKeyValue(
                    key = stringResource(R.string.cli_stats_key_tor_bootstrap),
                    value = "$progress%",
                    valueColor = colors.warn,
                )
            }
        }
        if (torPhase.phase == TorNetworkPhase.CONNECTED && torPhase.connectedAt > 0L) {
            val nowMs by rememberNowMsTicker(torPhase.connectedAt)
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_tor_uptime),
                value = CliFormat.uptime(torPhase.connectedAt, nowMs),
            )
        }
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_tor_traffic_row),
            value = CliFormat.bytes(hero.torBytes24h),
            valueColor = colors.tor,
        )
        val scopeAll = state.settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS
        val torApps = state.settings.expert.packages(AppTunnelLane.TOR).distinct()
        if (scopeAll) {
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_tor_scope),
                value = stringResource(R.string.cli_route_tor_device).lowercase(),
            )
        } else {
            CliRowDivider()
            CliStatsTorAppScopeRow(
                packages = torApps,
                installedApps = state.installedApps,
            )
        }
        home.torIpInfo?.let { exit ->
            CliRowDivider()
            val geo = listOfNotNull(exit.countryCode, exit.city).joinToString("/")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.cli_stats_key_tor_exit),
                    style = CliType.body,
                    color = colors.dim,
                    modifier = Modifier.weight(1f),
                )
                exit.countryCode?.let { code ->
                    CliFlagIcon(countryCode = code)
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                }
                Text(
                    text = listOfNotNull(exit.ip, geo.takeIf(String::isNotEmpty)).joinToString(" · "),
                    style = CliType.body,
                    color = colors.fg,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CliStatsTorAppScopeRow(
    packages: List<String>,
    installedApps: List<InstalledAppOption>,
) {
    val colors = LocalCliColors.current
    val installed = remember(installedApps) { installedApps.associateBy(InstalledAppOption::packageName) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.cli_stats_key_tor_scope),
            style = CliType.body,
            color = colors.dim,
            modifier = Modifier.weight(1f),
        )
        if (packages.isEmpty()) {
            Text(
                text = stringResource(R.string.cli_stats_source_none),
                style = CliType.body,
                color = colors.dim,
            )
        } else {
            packages.take(TOR_APP_ICONS_MAX).forEach { packageName ->
                val app = installed[packageName]
                val icon = rememberCliAppIcon(
                    packageName = packageName,
                    versionCode = app?.versionCode,
                    lastUpdateTime = app?.lastUpdateTime,
                    bitmapSize = TOR_APP_ICON_SIZE,
                )
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = app?.label ?: packageName,
                        modifier = Modifier.size(TOR_APP_ICON_SIZE),
                    )
                } else {
                    CliPixIcon(
                        id = R.drawable.pix_apps,
                        contentDescription = packageName,
                        tint = colors.dim,
                        modifier = Modifier.size(TOR_APP_ICON_SIZE),
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
            }
            val hidden = packages.size - TOR_APP_ICONS_MAX
            if (hidden > 0) {
                Text(
                    text = "+$hidden",
                    style = CliType.small,
                    color = colors.tor,
                )
            }
        }
    }
}

private fun appLabel(installedIndex: Map<String, InstalledAppOption>, packageName: String): String =
    installedIndex[packageName]?.label?.ifEmpty { packageName } ?: packageName

private val SETTINGS_BUTTON_SIZE = 48.dp
private val SETTINGS_BUTTON_GLYPH_SIZE = 20.dp
private val SETTINGS_BUTTON_EDGE_SHIFT = 12.dp

private const val DNS_APP_ROWS_MAX = 4
private const val FIREWALL_ROWS_MAX = 4
private const val TOR_APP_ICONS_MAX = 6
private val TOR_APP_ICON_SIZE = 16.dp
