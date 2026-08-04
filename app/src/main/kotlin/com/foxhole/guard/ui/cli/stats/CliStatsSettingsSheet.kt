package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsMetric
import com.foxhole.core.model.StatisticsRefreshInterval
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.core.model.effectiveRetention
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.clearAppTrafficLocalData
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDestructiveRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRetentionRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onAppTrafficStatsEnabledChanged
import com.foxhole.guard.ui.onAppTrafficUsageAccessConsentChanged
import com.foxhole.guard.ui.onStatisticsEnabledChanged
import com.foxhole.guard.ui.onStatisticsMetricEnabledChanged
import com.foxhole.guard.ui.onStatisticsRefreshIntervalSelected
import com.foxhole.guard.ui.onStatisticsRetentionSelected
import com.foxhole.guard.ui.onStatisticsSessionOnlyChanged
import com.foxhole.guard.ui.resetUsageTracking

/**
 * The only surface that *enables* collection. Without it the stats screen is structurally empty:
 * the stream gates require flags that default to false and are set nowhere else in the CLI.
 *
 * Four groups: collection, the six canon metrics, per-app stats behind Usage Access, and
 * clearing. The clear buttons are labelled with what they actually erase — both reach well beyond
 * "statistics".
 */
@Composable
internal fun CliStatsSettingsSheet(
    viewModel: HomeViewModel,
    settings: Settings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usageAccessGranted = rememberUsageAccessGranted()
    // The permission was revoked in the system while consent still sits in settings: clear it, or
    // the sampler counts as allowed while being unable to read anything.
    LaunchedEffect(usageAccessGranted, settings.appTrafficUsageAccessConsent) {
        if (!usageAccessGranted && settings.appTrafficUsageAccessConsent) {
            viewModel.onAppTrafficUsageAccessConsentChanged(false)
        }
    }
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.cli_stats_settings_title),
        icon = R.drawable.pix_stats,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            CliStatsCollectionPanel(viewModel = viewModel, settings = settings)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliStatsMetricsPanel(viewModel = viewModel, settings = settings)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliStatsAppsPanel(
                viewModel = viewModel,
                settings = settings,
                usageAccessGranted = usageAccessGranted,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliStatsClearPanel(viewModel = viewModel)
        }
    }
}

/** The master collection toggle plus everything describing how it writes. */
@Composable
private fun CliStatsCollectionPanel(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val enabled = settings.statistics.enabled
    CliPanel(
        title = stringResource(R.string.cli_stats_set_collect_title),
        icon = R.drawable.pix_stats,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_stats_set_collect),
            checked = enabled,
            onToggle = viewModel::onStatisticsEnabledChanged,
        )
        CliRetentionRow(
            label = stringResource(R.string.cli_stats_set_retention),
            policy = settings.statistics.effectiveRetention(),
            onSelect = viewModel::onStatisticsRetentionSelected,
            enabled = enabled,
        )
        CliDropdownRow(
            label = stringResource(R.string.cli_stats_set_refresh),
            value = refreshLabel(settings.statistics.refreshInterval),
            options = StatisticsRefreshInterval.entries.map { interval ->
                CliDropdownOption(id = interval.name, label = refreshLabel(interval))
            },
            selectedId = settings.statistics.refreshInterval.name,
            onSelect = { id ->
                viewModel.onStatisticsRefreshIntervalSelected(StatisticsRefreshInterval.valueOf(id))
            },
            enabled = enabled,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_stats_set_session_only),
            checked = settings.statistics.sessionOnly,
            onToggle = viewModel::onStatisticsSessionOnlyChanged,
            note = stringResource(R.string.cli_stats_set_session_only_note),
            enabled = enabled,
        )
    }
}

/**
 * Exactly the six canon metrics; the ones dropped from the model are not resurrected here. While
 * the master toggle is off every row is inactive — a metric without collection means nothing.
 */
@Composable
private fun CliStatsMetricsPanel(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val enabled = settings.statistics.enabled
    val dnsFilterOn = settings.dns.dnsRuleSetFilteringEnabled()
    CliPanel(
        icon = R.drawable.pix_stats,
        title = stringResource(R.string.cli_stats_set_metrics_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.PROFILE_TRAFFIC,
            label = stringResource(R.string.cli_stats_set_metric_profiles),
            checked = settings.statistics.profileTrafficEnabled,
            enabled = enabled,
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.APP_TRAFFIC,
            label = stringResource(R.string.cli_stats_set_metric_apps),
            checked = settings.statistics.appTrafficEnabled,
            enabled = enabled,
            note = stringResource(R.string.cli_stats_set_metric_apps_note),
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.DNS_FILTERING,
            label = stringResource(R.string.cli_stats_set_metric_dns),
            checked = settings.statistics.dnsFilteringEnabled,
            enabled = enabled && dnsFilterOn,
            note = if (dnsFilterOn) null else stringResource(R.string.cli_stats_set_metric_dns_note),
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.COUNTRY_TRAFFIC,
            label = stringResource(R.string.cli_stats_set_metric_countries),
            checked = settings.statistics.countryTrafficEnabled,
            enabled = enabled,
            note = if (settings.expert.networkActivityLogging) {
                null
            } else {
                stringResource(R.string.cli_stats_set_metric_countries_note)
            },
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.ANOMALIES,
            label = stringResource(R.string.cli_stats_set_metric_anomalies),
            checked = settings.statistics.anomalyMetricsEnabled,
            enabled = enabled,
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.APP_CHANGES,
            label = stringResource(R.string.cli_stats_set_metric_app_changes),
            checked = settings.statistics.appChangesEnabled,
            enabled = enabled,
        )
    }
}

@Composable
private fun CliStatsMetricRow(
    viewModel: HomeViewModel,
    metric: StatisticsMetric,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    note: String? = null,
) {
    CliToggleRow(
        label = label,
        checked = checked,
        onToggle = { value -> viewModel.onStatisticsMetricEnabledChanged(metric, value) },
        note = note,
        enabled = enabled,
    )
}

/**
 * Per-app traffic recording. Without Usage Access the toggle cannot turn on at all: the tap goes
 * to system settings and the next tap enables it, once [rememberUsageAccessGranted] returns true.
 * Consent is written atomically with the recording flag, so no separate consent call is needed.
 */
@Composable
private fun CliStatsAppsPanel(
    viewModel: HomeViewModel,
    settings: Settings,
    usageAccessGranted: Boolean,
) {
    val context = LocalContext.current
    CliPanel(
        icon = R.drawable.pix_apps,
        title = stringResource(R.string.cli_stats_set_apps_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_stats_set_app_usage),
            checked = settings.appTrafficStatsEnabled && usageAccessGranted,
            onToggle = { value ->
                if (value && !usageAccessGranted) {
                    openUsageAccessSettings(context)
                } else {
                    viewModel.onAppTrafficStatsEnabledChanged(value)
                }
            },
            note = if (usageAccessGranted) null else stringResource(R.string.cli_stats_set_app_usage_note),
            enabled = settings.statistics.enabled,
        )
    }
}

/**
 * Clearing. Both buttons are labelled with what they erase: resetUsageTracking also wipes
 * anomaly_events, network_activity_events, protocol_metric_events and seen_destination_countries,
 * and clearAppTrafficLocalData additionally *disables* per-app traffic recording.
 */
@Composable
private fun CliStatsClearPanel(viewModel: HomeViewModel) {
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    CliPanel(
        title = stringResource(R.string.cli_stats_clear_title),
        icon = R.drawable.pix_trash,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliDestructiveRow(
            key = CLEAR_ALL_KEY,
            label = stringResource(R.string.cli_stats_clear_all),
            note = stringResource(R.string.cli_stats_clear_all_note),
            confirmAction = confirmAction,
            onArm = { confirmAction = it },
            onConfirm = viewModel::resetUsageTracking,
        )
        CliDestructiveRow(
            key = CLEAR_APPS_KEY,
            label = stringResource(R.string.cli_stats_clear_apps),
            note = stringResource(R.string.cli_stats_clear_apps_note),
            confirmAction = confirmAction,
            onArm = { confirmAction = it },
            onConfirm = viewModel::clearAppTrafficLocalData,
        )
    }
}

private fun refreshLabel(interval: StatisticsRefreshInterval): String = "${interval.seconds}s"

private const val CLEAR_ALL_KEY = "clear_statistics"
private const val CLEAR_APPS_KEY = "clear_app_statistics"
