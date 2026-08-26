package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.foxhole.guard.core.sentinel.anomaly.UsageAccessState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.clearAppTrafficLocalData
import com.foxhole.guard.ui.clearI2pTrafficStatistics
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDestructiveRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRetentionRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onAppTrafficStatsEnabledChanged
import com.foxhole.guard.ui.onStatisticsDockIconEnabledChanged
import com.foxhole.guard.ui.onStatisticsEnabledChanged
import com.foxhole.guard.ui.onStatisticsMetricEnabledChanged
import com.foxhole.guard.ui.onStatisticsRefreshIntervalSelected
import com.foxhole.guard.ui.onStatisticsRetentionSelected
import com.foxhole.guard.ui.onStatisticsSessionOnlyChanged
import com.foxhole.guard.ui.resetUsageTracking

@Composable
internal fun CliStatsSettingsSheet(
    viewModel: HomeViewModel,
    settings: Settings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usageAccessState = rememberUsageAccessState()
    val usageAccessGranted = usageAccessState == UsageAccessState.GRANTED
    CliStatsSettingsSheetFrame(
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
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

@Composable
internal fun CliStatsSettingsSheetFrame(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.cli_stats_settings_title),
        icon = R.drawable.lin_stats,
        sheetGesturesEnabled = false,
        content = content,
    )
}

@Composable
private fun CliStatsCollectionPanel(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val colors = LocalCliColors.current
    val enabled = settings.statistics.enabled
    val actionLabel = stringResource(
        if (enabled) R.string.cli_stats_disable_module else R.string.cli_stats_enable_module,
    )
    CliPanel(
        title = stringResource(R.string.cli_stats_set_collect_title),
        icon = R.drawable.lin_stats,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliToggleRow(
            label = actionLabel,
            icon = R.drawable.lin_stats,
            checked = enabled,
            onToggle = viewModel::onStatisticsEnabledChanged,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_stats_set_show_in_dock),
            icon = R.drawable.lin_stats,
            checked = settings.ui.statisticsDockIconEnabled,
            onToggle = viewModel::onStatisticsDockIconEnabledChanged,
        )
        CliRetentionRow(
            label = stringResource(R.string.cli_stats_set_retention),
            icon = R.drawable.lin_clock,
            policy = settings.statistics.effectiveRetention(),
            onSelect = viewModel::onStatisticsRetentionSelected,
            enabled = enabled,
        )
        CliDropdownRow(
            label = stringResource(R.string.cli_stats_set_refresh),
            icon = R.drawable.lin_restart,
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
            icon = R.drawable.lin_incognito,
            checked = settings.statistics.sessionOnly,
            onToggle = viewModel::onStatisticsSessionOnlyChanged,
            note = stringResource(R.string.cli_stats_set_session_only_note),
            noteColor = colors.info,
            enabled = enabled,
        )
    }
}

@Composable
private fun CliStatsMetricsPanel(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val enabled = settings.statistics.enabled
    val dnsFilterOn = settings.dns.dnsRuleSetFilteringEnabled()
    CliPanel(
        icon = R.drawable.lin_stats,
        title = stringResource(R.string.cli_stats_set_metrics_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.PROFILE_TRAFFIC,
            icon = R.drawable.lin_profiles,
            label = stringResource(R.string.cli_stats_set_metric_profiles),
            checked = settings.statistics.profileTrafficEnabled,
            enabled = enabled,
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.APP_TRAFFIC,
            icon = R.drawable.lin_apps,
            label = stringResource(R.string.cli_stats_set_metric_apps),
            checked = settings.statistics.appTrafficEnabled,
            enabled = enabled,
            note = stringResource(R.string.cli_stats_set_metric_apps_note),
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.DNS_FILTERING,
            icon = R.drawable.lin_dns,
            label = stringResource(R.string.cli_stats_set_metric_dns),
            checked = settings.statistics.dnsFilteringEnabled,
            enabled = enabled && dnsFilterOn,
            note = if (dnsFilterOn) null else stringResource(R.string.cli_stats_set_metric_dns_note),
        )
        CliStatsMetricRow(
            viewModel = viewModel,
            metric = StatisticsMetric.COUNTRY_TRAFFIC,
            icon = R.drawable.lin_map,
            label = stringResource(R.string.cli_stats_set_metric_countries),
            checked = settings.statistics.countryTrafficEnabled,
            enabled = enabled,
            note = if (settings.expert.networkActivityLogging) {
                null
            } else {
                stringResource(R.string.cli_stats_set_metric_countries_note)
            },
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
    icon: Int? = null,
) {
    val colors = LocalCliColors.current
    CliToggleRow(
        label = label,
        icon = icon,
        checked = checked,
        onToggle = { value -> viewModel.onStatisticsMetricEnabledChanged(metric, value) },
        note = note,
        noteColor = colors.info,
        enabled = enabled,
    )
}

@Composable
private fun CliStatsAppsPanel(
    viewModel: HomeViewModel,
    settings: Settings,
    usageAccessGranted: Boolean,
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    var enableAfterUsageGrant by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(usageAccessGranted, enableAfterUsageGrant) {
        if (shouldCompleteAppTrafficEnable(usageAccessGranted, enableAfterUsageGrant)) {
            enableAfterUsageGrant = false
            viewModel.onAppTrafficStatsEnabledChanged(true)
        }
    }
    CliPanel(
        icon = R.drawable.lin_apps,
        title = stringResource(R.string.cli_stats_set_apps_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_stats_set_app_usage),
            icon = R.drawable.lin_apps,
            checked = settings.appTrafficStatsEnabled,
            onToggle = { value ->
                if (value && !usageAccessGranted) {
                    enableAfterUsageGrant = true
                    openUsageAccessSettings(context)
                } else {
                    if (!value) enableAfterUsageGrant = false
                    viewModel.onAppTrafficStatsEnabledChanged(value)
                }
            },
            note = if (usageAccessGranted) null else stringResource(R.string.cli_stats_set_app_usage_note),
            noteColor = colors.info,
            enabled = settings.statistics.enabled,
        )
    }
}

@Composable
private fun CliStatsClearPanel(viewModel: HomeViewModel) {
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    CliPanel(
        title = stringResource(R.string.cli_stats_clear_title),
        icon = R.drawable.lin_trash,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliDestructiveRow(
            key = CLEAR_ALL_KEY,
            icon = R.drawable.lin_trash,
            label = stringResource(R.string.cli_stats_clear_all),
            confirmAction = confirmAction,
            onArm = { confirmAction = it },
            onConfirm = viewModel::resetUsageTracking,
        )
        CliDestructiveRow(
            key = CLEAR_APPS_KEY,
            icon = R.drawable.lin_apps,
            label = stringResource(R.string.cli_stats_clear_apps),
            confirmAction = confirmAction,
            onArm = { confirmAction = it },
            onConfirm = viewModel::clearAppTrafficLocalData,
        )
        CliDestructiveRow(
            key = CLEAR_I2P_KEY,
            icon = R.drawable.lin_incognito,
            label = stringResource(R.string.cli_stats_clear_i2p),
            confirmAction = confirmAction,
            onArm = { confirmAction = it },
            onConfirm = viewModel::clearI2pTrafficStatistics,
        )
    }
}

private fun refreshLabel(interval: StatisticsRefreshInterval): String = "${interval.seconds}s"

private const val CLEAR_ALL_KEY = "clear_statistics"
private const val CLEAR_APPS_KEY = "clear_app_statistics"
private const val CLEAR_I2P_KEY = "clear_i2p_statistics"
