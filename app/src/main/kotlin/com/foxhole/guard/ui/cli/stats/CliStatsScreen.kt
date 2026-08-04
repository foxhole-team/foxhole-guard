package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.PrivacyRouteScope
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
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.components.rememberNowMsTicker
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import com.foxhole.guard.ui.onStatisticsEnabledChanged
import com.foxhole.guard.ui.onStatisticsWindowChanged
import com.foxhole.guard.ui.toStatisticsUiNowBucket

/**
 * Statistics as terminal tables. Group order is the user's own: (1) overview behind a persisted
 * day/week window, (2) firewall — attempts and anomalies, shown only while the firewall is on,
 * (3) profiles and protocols, (4) dns — only while the filter is on, (5) apps. The Tor detail table
 * keeps its fixed 24h numbers and stays below the five groups.
 *
 * The header gear opens [CliStatsSettingsSheet]: it is the only surface that turns collection on, so
 * it stays reachable through every empty state. Data is the same StatisticsDashboardUiState the
 * classic screen renders (plus the home route's Tor phase); production is visibility-gated, so
 * CliApp flips onStatisticsUiVisibilityChanged for us.
 */
@Composable
internal fun CliStatsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val state by viewModel.statisticsRouteState.collectAsStateWithLifecycle()
    // Tor phase rides the home route state only; the statistics route does not carry it.
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    val dashboard = state.statisticsDashboard
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_dock_stats),
            icon = R.drawable.pix_stats,
            trailing = {
                CliStatsSettingsButton(
                    enabled = state.settingsHydrated,
                    onClick = { settingsOpen = true },
                )
            },
        )
        // The sheet rises BEFORE every early exit: collection is enabled exactly when the screen
        // is empty. Hydration is the only gate: before it a write from the sheet would persist a
        // bootstrap default instead of the real value.
        if (settingsOpen && state.settingsHydrated) {
            CliStatsSettingsSheet(
                viewModel = viewModel,
                settings = state.settings,
                onDismiss = { settingsOpen = false },
            )
        }
        // Donor gating order: settings hydration -> statistics consent (opt-in, default OFF)
        // -> dashboard readiness. Zeros while collection is off must not read as measurements.
        if (!state.settingsHydrated) {
            CliPanel(
                icon = R.drawable.pix_stats,
                title = stringResource(R.string.cli_stats_title),
                modifier = Modifier.fillMaxWidth()
            ) {
                CliLoadingRow(text = stringResource(R.string.cli_common_loading_settings))
            }
            return
        }
        if (!state.settings.statistics.enabled) {
            CliPanel(
                icon = R.drawable.pix_stats,
                title = stringResource(R.string.cli_stats_title),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.cli_stats_consent_body),
                    style = CliType.body,
                    color = colors.dim,
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliButton(label = stringResource(R.string.cli_common_btn_enable), onClick = {
                    viewModel.onStatisticsEnabledChanged(true)
                })
            }
            return
        }
        if (!dashboard.ready) {
            CliPanel(
                icon = R.drawable.pix_stats,
                title = stringResource(R.string.cli_stats_title),
                modifier = Modifier.fillMaxWidth()
            ) {
                CliLoadingRow(text = stringResource(R.string.cli_stats_collecting))
            }
            return
        }

        CliStatsBody(viewModel = viewModel, state = state, home = home)
    }
}

/** Header gear — the only entry into statistics settings (the CLI has no top bar). */
@Composable
private fun CliStatsSettingsButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCliColors.current
    Box(
        // requiredSize: the 48dp target overlaps the fixed header slot without inflating the row.
        modifier = Modifier
            .requiredSize(SETTINGS_BUTTON_SIZE)
            .cliPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_settings,
            contentDescription = stringResource(R.string.cli_stats_settings_title),
            // Dimmed until settings hydrate: the sheet cannot open before then, and a gear that
            // presses but does nothing reads as a broken screen.
            tint = if (enabled) colors.accent else colors.faint,
        )
    }
}

// Group order is the user's explicit requirement: overview, firewall, profiles+protocols, dns,
// apps. The Tor table goes last: not part of the five, but its data is visible nowhere else.
@Composable
private fun CliStatsBody(
    viewModel: HomeViewModel,
    state: StatisticsRouteUiState,
    home: HomeRouteUiState,
) {
    val hero = remember(
        state.appTrafficWindows,
        state.anomalyEvents,
        state.settings,
        state.statisticsDashboard.nowMs,
    ) {
        cliStatsHero(
            windows = state.appTrafficWindows,
            anomalyEvents = state.anomalyEvents,
            settings = state.settings,
            nowMs = state.statisticsDashboard.nowMs,
        )
    }
    // Inside the dashboard.ready gate, so nowMs is a real bucketed clock, not 0.
    val overview = remember(
        state.appTrafficWindows,
        state.protocolMetricEvents,
        state.i2pTraffic,
        state.settings,
        state.statisticsDashboard,
    ) {
        cliStatsOverview(
            window = state.settings.ui.statisticsWindow,
            appTrafficWindows = state.appTrafficWindows,
            metricEvents = state.protocolMetricEvents,
            i2pTraffic = state.i2pTraffic,
            settings = state.settings,
            lifetimeTotal = state.statisticsDashboard.statistics.total,
            lifetimeProtocols = state.statisticsDashboard.statistics.vpnProtocols,
            nowMs = state.statisticsDashboard.nowMs,
        )
    }
    Column {
        CliStatsOverviewPanel(viewModel = viewModel, overview = overview)
        CliStatsFirewallGroup(state = state, hero = hero)
        CliStatsProfilePanel(state)
        CliStatsProtocolPanel(state)
        CliStatsTransportPanel(state)
        CliStatsDnsPanel(state)
        CliStatsAppPanel(state)
        CliStatsTorPanel(state = state, home = home, hero = hero)
    }
}

/**
 * Group (1): vpn/tor/i2p traffic, average latency, vpn errors and best/worst protocols for the
 * chosen window; the day/week dropdown persists forever. Two labels are mandatory or the panel
 * lies: the i2p row is session-scoped for any window, and "all time" marks a cumulative number
 * that does not react to the dropdown.
 */
@Composable
private fun CliStatsOverviewPanel(
    viewModel: HomeViewModel,
    overview: CliStatsOverview,
) {
    val colors = LocalCliColors.current
    // Window labels resolve before building options: no stringResource inside a map lambda.
    val dayLabel = stringResource(R.string.cli_stats_range_day)
    val weekLabel = stringResource(R.string.cli_stats_range_week)
    CliPanel(
        title = stringResource(R.string.cli_stats_overview_title),
        icon = R.drawable.pix_stats,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliDropdownRow(
            label = stringResource(R.string.cli_stats_key_range),
            value = statisticsWindowLabel(overview.window, dayLabel, weekLabel),
            options = StatisticsWindow.entries.map { window ->
                CliDropdownOption(id = window.name, label = statisticsWindowLabel(window, dayLabel, weekLabel))
            },
            selectedId = overview.window.name,
            onSelect = { id -> viewModel.onStatisticsWindowChanged(StatisticsWindow.valueOf(id)) },
        )
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_vpn_traffic),
            value = CliFormat.bytes(overview.vpnBytes),
            valueColor = colors.vpn,
        )
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_tor_traffic),
            value = CliFormat.bytes(overview.torBytes),
            valueColor = colors.tor,
        )
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_i2p_traffic),
            value = CliFormat.bytes(overview.i2pSessionBytes),
        )
        CliElbowLine(text = stringResource(R.string.cli_stats_i2p_session_note))
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_avg_latency),
            value = statsSourceValue(overview.latencySource, CliFormat.latency(overview.avgLatencyMs)),
            valueColor = if (overview.latencySource == CliStatsOverviewSource.NONE) colors.dim else colors.fg,
        )
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_vpn_errors),
            value = statsSourceValue(
                overview.errorSource,
                "${CliFormat.percent(overview.vpnErrorRate)} · ${overview.vpnFailures}/${overview.vpnAttempts}",
            ),
            valueColor = when {
                overview.errorSource == CliStatsOverviewSource.NONE -> colors.dim
                overview.vpnFailures > 0 -> colors.warn
                else -> colors.ok
            },
        )
        CliStatsProtocolErrorRow(
            key = stringResource(R.string.cli_stats_key_errors_worst),
            entry = overview.worstProtocol,
            source = overview.errorSource,
        )
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
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/** `protocol · error share` or the empty state: a zero here would be a different claim. */
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
            statsSourceValue(source, "${entry.protocol} · ${CliFormat.percent(entry.errorRate)}")
        },
        valueColor = if (entry == null) colors.dim else colors.fg,
    )
}

/** Value with a source label: window as-is, cumulative gets a note, empty reads "no data". */
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

private fun statisticsWindowLabel(
    window: StatisticsWindow,
    dayLabel: String,
    weekLabel: String,
): String =
    when (window) {
        StatisticsWindow.DAY -> dayLabel
        StatisticsWindow.WEEK -> weekLabel
    }

/**
 * Group (2): blocked-connection attempts and anomalies. Visible ONLY while the firewall is on —
 * the user's explicit requirement, so the anomaly detector hides with it.
 */
@Composable
private fun CliStatsFirewallGroup(
    state: StatisticsRouteUiState,
    hero: CliStatsHero,
) {
    if (!state.settings.expert.firewallEnabled) return
    CliStatsFirewallPanel(state)
    CliStatsAnomalyPanel(state = state, hero = hero)
}

@Composable
private fun CliStatsFirewallPanel(state: StatisticsRouteUiState) {
    val colors = LocalCliColors.current
    val expert = state.settings.expert
    // The BLOCK-lane list only counts while the blocking switch is on.
    val blockedPackages = if (expert.blockedPackagesEnabled) expert.blockedLanePackages() else emptyList()
    val nowMs = state.statisticsDashboard.nowMs.toStatisticsUiNowBucket()
    val rows = remember(state.networkActivityEvents, blockedPackages, nowMs) {
        cliBlockedAppRows(state.networkActivityEvents, blockedPackages, nowMs)
    }
    val attempts = rows.sumOf { it.attempts }
    CliPanel(
        icon = R.drawable.pix_fire,
        title = stringResource(R.string.cli_stats_firewall_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_fw_blocked_apps),
            value = blockedPackages.size.toString(),
        )
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_fw_attempts),
            value = attempts.toString(),
            valueColor = if (attempts > 0) colors.warn else colors.ok,
        )
        // An index instead of firstOrNull-per-row over the whole app inventory.
        val installedIndex = remember(state.installedApps) {
            state.installedApps.associateBy { it.packageName }
        }
        rows.take(FIREWALL_ROWS_MAX).forEach { row ->
            CliKeyValue(key = appLabel(installedIndex, row.packageName), value = row.attempts.toString())
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/**
 * Anomaly detail: 24h count and share of anomalous traffic, severity breakdown and latest
 * events. Visible only while the detector is enabled.
 */
@Composable
private fun CliStatsAnomalyPanel(
    state: StatisticsRouteUiState,
    hero: CliStatsHero,
) {
    if (!state.settings.anomaly.enabled) return
    val colors = LocalCliColors.current
    // remember on every panel selection: the whole screen recomposes on each statistics tick,
    // and re-sorting unchanged input is wasted work (hero/overview canon).
    val events = remember(state.anomalyEvents) {
        state.anomalyEvents.sortedByDescending { it.createdAtMs }
    }
    CliPanel(
        title = stringResource(R.string.cli_stats_anomalies_title),
        icon = R.drawable.pix_status,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_anomalies),
            value = "${CliFormat.percent(hero.anomalyTrafficRatio24h)} · ${hero.anomalyCount24h}",
            valueColor = if (hero.anomalyCount24h > 0) colors.warn else colors.ok,
        )
        AnomalySeverity.entries.forEach { severity ->
            val count = events.count { it.severity == severity }
            if (count > 0) {
                CliKeyValue(
                    key = severity.name.lowercase(),
                    value = count.toString(),
                    valueColor = if (severity == AnomalySeverity.HIGH) colors.err else colors.dim,
                )
            }
        }
        val installedIndex = remember(state.installedApps) {
            state.installedApps.associateBy { it.packageName }
        }
        events.take(ANOMALY_ROWS_MAX).forEach { event ->
            val app = event.packageName
                ?.let { pkg -> appLabel(installedIndex, pkg) }
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "[${CliFormat.clock(event.createdAtMs)}] ",
                    style = CliType.small,
                    color = colors.faint,
                )
                Text(
                    text = listOfNotNull(event.type.name.lowercase(), app).joinToString(" · "),
                    style = CliType.small,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.cli_stats_anomalies_empty),
                style = CliType.small,
                color = colors.dim,
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/** Group (3), part one: traffic per profile. */
@Composable
private fun CliStatsProfilePanel(state: StatisticsRouteUiState) {
    val rows = remember(state.statisticsDashboard.statistics.profileTraffic) {
        state.statisticsDashboard.statistics.profileTraffic
            .sortedByDescending { it.rxBytes + it.txBytes }
            .take(PROFILE_ROWS_MAX)
    }
    if (rows.isEmpty()) return
    CliPanel(
        icon = R.drawable.pix_profiles,
        title = stringResource(R.string.cli_stats_profiles_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) CliRowDivider()
            CliKeyValue(
                key = row.profileName,
                value = stringResource(
                    R.string.cli_common_updown_value,
                    CliFormat.bytes(row.rxBytes),
                    CliFormat.bytes(row.txBytes),
                ),
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/**
 * Group (3), part two: per-protocol statistics (statistics.vpnProtocols) — it was computed but
 * never shown. The panel draws even when empty: otherwise the section goes invisible again and
 * nobody can tell what exactly is missing.
 */
@Composable
private fun CliStatsProtocolPanel(state: StatisticsRouteUiState) {
    val colors = LocalCliColors.current
    val total = state.statisticsDashboard.statistics.total
    val rows = remember(state.statisticsDashboard.statistics.vpnProtocols) {
        state.statisticsDashboard.statistics.vpnProtocols
            .filter { it.hasMeasuredAttempts || it.totalBytes > 0L }
            .sortedByDescending { it.totalAttempts }
            .take(PROTOCOL_ROWS_MAX)
    }
    CliPanel(
        icon = R.drawable.pix_shield,
        title = stringResource(R.string.cli_stats_protocols_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_vpn_sessions),
            value = total.vpnSessions.toString(),
        )
        if (rows.isEmpty()) {
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_success_errors),
                value = stringResource(R.string.cli_stats_source_none),
                valueColor = colors.dim,
            )
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) CliRowDivider()
            CliKeyValue(
                key = row.protocol.name.lowercase(),
                value = "${row.successCount}/${row.failureCount} · ${CliFormat.latency(row.avgLatencyMs)}",
                valueColor = if (row.failureCount > 0) colors.warn else colors.ok,
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/** Group (3), part three: tcp/udp transports — exactly what the table draws. */
@Composable
private fun CliStatsTransportPanel(state: StatisticsRouteUiState) {
    val transports = remember(state.statisticsDashboard.statistics.transports) {
        state.statisticsDashboard.statistics.transports
            .filter { it.totalAttempts > 0 || it.rxBytes + it.txBytes > 0 }
    }
    if (transports.isEmpty()) return
    CliPanel(
        icon = R.drawable.pix_link,
        title = stringResource(R.string.cli_stats_transports_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        transports.forEachIndexed { index, row ->
            if (index > 0) CliRowDivider()
            CliKeyValue(
                key = row.transport.name.lowercase(),
                value = stringResource(
                    R.string.cli_common_updown_value,
                    CliFormat.bytes(row.rxBytes),
                    CliFormat.bytes(row.txBytes),
                ) + " · ok ${row.successCount}/${row.totalAttempts}",
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/**
 * Group (4): dns filter — block/pass, category breakdown and top apps. Visible exactly while the
 * filter is on (user requirement), not once queries accumulate.
 */
@Composable
private fun CliStatsDnsPanel(state: StatisticsRouteUiState) {
    if (!state.settings.dns.dnsRuleSetFilteringEnabled()) return
    val colors = LocalCliColors.current
    val dns = state.statisticsDashboard.dnsSummary
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
            CliKeyValue(key = row.category.name.lowercase(), value = row.blockedQueries.toString())
        }
        val appRows = remember(dns.appRows) {
            dns.appRows
                .filter { it.estimatedBlockedQueries > 0 }
                .sortedByDescending { it.estimatedBlockedQueries }
                .take(DNS_APP_ROWS_MAX)
        }
        appRows.forEach { row ->
            CliKeyValue(
                key = "  ${row.label.ifEmpty { row.packageName }}",
                value = row.estimatedBlockedQueries.toString(),
                valueColor = colors.dim,
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/** Group (5): apps — app icon + label + ↓rx ↑tx. */
@Composable
private fun CliStatsAppPanel(state: StatisticsRouteUiState) {
    val colors = LocalCliColors.current
    val rows = remember(state.statisticsDashboard.appRows) {
        state.statisticsDashboard.appRows
            .filter { it.totalBytes > 0 }
            .sortedByDescending { it.totalBytes }
            .take(APP_ROWS_MAX)
    }
    if (rows.isEmpty()) return
    CliPanel(
        icon = R.drawable.pix_apps,
        title = stringResource(R.string.cli_stats_apps_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) CliRowDivider()
            val installed = state.installedApps.firstOrNull { it.packageName == row.packageName }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = rememberCliAppIcon(
                    packageName = row.packageName,
                    versionCode = installed?.versionCode,
                    lastUpdateTime = installed?.lastUpdateTime,
                    bitmapSize = 16.dp,
                )
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                } else {
                    Box(modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = row.label.ifEmpty { row.packageName },
                    style = CliType.body,
                    color = if (row.badges.isEmpty()) colors.fg else colors.warn,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        R.string.cli_common_updown_value,
                        CliFormat.bytes(row.rxBytes),
                        CliFormat.bytes(row.txBytes),
                    ),
                    style = CliType.body,
                    color = colors.dim,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

/**
 * Tor detail: phase/bootstrap/uptime + 24h traffic, route scope and exit node (flag + ip + geo).
 * Visible while anything Tor-meaningful exists. Numbers are deliberately fixed 24h — the
 * overview window does not cut them, as the key's label states.
 */
@Composable
private fun CliStatsTorPanel(
    state: StatisticsRouteUiState,
    home: HomeRouteUiState,
    hero: CliStatsHero,
) {
    val torPhase = home.torPhase
    if (torPhase.phase == TorNetworkPhase.OFFLINE && hero.torBytes24h <= 0L) return
    val colors = LocalCliColors.current
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
                CliKeyValue(
                    key = stringResource(R.string.cli_stats_key_tor_bootstrap),
                    value = "$progress%",
                    valueColor = colors.warn,
                )
            }
        }
        if (torPhase.phase == TorNetworkPhase.CONNECTED && torPhase.connectedAt > 0L) {
            val nowMs by rememberNowMsTicker(torPhase.connectedAt)
            CliKeyValue(
                key = stringResource(R.string.cli_stats_key_tor_uptime),
                value = CliFormat.uptime(torPhase.connectedAt, nowMs),
            )
        }
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_tor_traffic_row),
            value = CliFormat.bytes(hero.torBytes24h),
            valueColor = colors.tor,
        )
        val scopeAll = state.settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS
        val torApps = state.settings.expert.packages(AppTunnelLane.TOR)
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_tor_scope),
            value = if (scopeAll) {
                stringResource(R.string.cli_route_tor_device).lowercase()
            } else {
                "${stringResource(R.string.cli_route_tor_apps).lowercase()} · ${torApps.size}"
            },
        )
        home.torIpInfo?.let { exit ->
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
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

private fun appLabel(installedIndex: Map<String, InstalledAppOption>, packageName: String): String =
    installedIndex[packageName]?.label?.ifEmpty { packageName } ?: packageName

private val SETTINGS_BUTTON_SIZE = 48.dp

private const val PROFILE_ROWS_MAX = 6
private const val PROTOCOL_ROWS_MAX = 6
private const val APP_ROWS_MAX = 6
private const val DNS_APP_ROWS_MAX = 4
private const val ANOMALY_ROWS_MAX = 4
private const val FIREWALL_ROWS_MAX = 4
