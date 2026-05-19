@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsSettings

@Composable
internal fun StatisticsSettingsDialog(
    state: SettingsRouteUiState,
    statisticsSettings: StatisticsSettings,
    appStatsSwitchChecked: Boolean,
    usageAccessGranted: Boolean,
    dnsFilteringAvailable: Boolean,
    retentionMenuExpanded: Boolean,
    refreshIntervalMenuExpanded: Boolean,
    onRetentionMenuExpandedChange: (Boolean) -> Unit,
    onRefreshIntervalMenuExpandedChange: (Boolean) -> Unit,
    onStatisticsEnabledChanged: (Boolean) -> Unit,
    onStatisticsRetentionSelected: (StatisticsRetention) -> Unit,
    onStatisticsRefreshIntervalSelected: (StatisticsRefreshInterval) -> Unit,
    onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
    onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    onUsageAccessRequired: () -> Unit,
    onUsageAccessCleared: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.statistics_settings_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatisticsStorageSettingsGroup(
                    state = state,
                    statisticsSettings = statisticsSettings,
                    retentionMenuExpanded = retentionMenuExpanded,
                    refreshIntervalMenuExpanded = refreshIntervalMenuExpanded,
                    onRetentionMenuExpandedChange = onRetentionMenuExpandedChange,
                    onRefreshIntervalMenuExpandedChange = onRefreshIntervalMenuExpandedChange,
                    onStatisticsEnabledChanged = onStatisticsEnabledChanged,
                    onStatisticsRetentionSelected = onStatisticsRetentionSelected,
                    onStatisticsRefreshIntervalSelected = onStatisticsRefreshIntervalSelected,
                )
                SettingsControlGroup {
                    SettingSwitchRow(
                        title = stringResource(R.string.app_statistics_enabled_title),
                        checked = appStatsSwitchChecked,
                        summary = stringResource(R.string.app_statistics_enabled_summary),
                        leadingIcon = Icons.Outlined.Apps,
                        onCheckedChange = { enabled ->
                            if (enabled && !usageAccessGranted) {
                                onUsageAccessRequired()
                            } else {
                                onUsageAccessCleared()
                                onAppTrafficStatsEnabledChanged(enabled)
                            }
                        },
                        enabled = statisticsSettings.enabled,
                        grouped = true,
                    )
                }
                StatisticsMetricSettingsGroup(
                    state = state,
                    statisticsSettings = statisticsSettings,
                    appStatsSwitchChecked = appStatsSwitchChecked,
                    usageAccessGranted = usageAccessGranted,
                    dnsFilteringAvailable = dnsFilteringAvailable,
                    onUsageAccessRequired = onUsageAccessRequired,
                    onStatisticsMetricEnabledChanged = onStatisticsMetricEnabledChanged,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
private fun StatisticsStorageSettingsGroup(
    state: SettingsRouteUiState,
    statisticsSettings: StatisticsSettings,
    retentionMenuExpanded: Boolean,
    refreshIntervalMenuExpanded: Boolean,
    onRetentionMenuExpandedChange: (Boolean) -> Unit,
    onRefreshIntervalMenuExpandedChange: (Boolean) -> Unit,
    onStatisticsEnabledChanged: (Boolean) -> Unit,
    onStatisticsRetentionSelected: (StatisticsRetention) -> Unit,
    onStatisticsRefreshIntervalSelected: (StatisticsRefreshInterval) -> Unit,
) {
    SettingsControlGroup {
        SettingSwitchRow(
            title = stringResource(R.string.statistics_enabled_title),
            checked = statisticsSettings.enabled,
            summary = stringResource(R.string.statistics_enabled_summary),
            leadingIcon = Icons.Outlined.BarChart,
            onCheckedChange = onStatisticsEnabledChanged,
            grouped = true,
        )
        SettingsControlGroupDivider()
        DropdownSettingRow(
            title = stringResource(R.string.statistics_retention_title),
            value = statisticsRetentionLabel(state.settings.statistics.retention),
            expanded = retentionMenuExpanded,
            onExpandedChange = onRetentionMenuExpandedChange,
            values = StatisticsRetention.entries,
            selected = state.settings.statistics.retention,
            label = { statisticsRetentionLabel(it) },
            onSelect = onStatisticsRetentionSelected,
            leadingIcon = Icons.Outlined.Storage,
            grouped = true,
        )
        SettingsControlGroupDivider()
        DropdownSettingRow(
            title = stringResource(R.string.statistics_refresh_interval_title),
            value = statisticsRefreshIntervalLabel(state.settings.statistics.refreshInterval),
            expanded = refreshIntervalMenuExpanded,
            onExpandedChange = onRefreshIntervalMenuExpandedChange,
            values = StatisticsRefreshInterval.entries,
            selected = state.settings.statistics.refreshInterval,
            label = { statisticsRefreshIntervalLabel(it) },
            onSelect = onStatisticsRefreshIntervalSelected,
            summary = stringResource(R.string.statistics_refresh_interval_summary),
            leadingIcon = Icons.Outlined.BarChart,
            grouped = true,
        )
    }
}

@Composable
private fun StatisticsMetricSettingsGroup(
    state: SettingsRouteUiState,
    statisticsSettings: StatisticsSettings,
    appStatsSwitchChecked: Boolean,
    usageAccessGranted: Boolean,
    dnsFilteringAvailable: Boolean,
    onUsageAccessRequired: () -> Unit,
    onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
) {
    SettingsControlGroup {
        StatisticsMetricSwitch(
            metric = StatisticsMetric.PROFILE_TRAFFIC,
            checked = statisticsSettings.profileTrafficEnabled,
            title = stringResource(R.string.statistics_metric_profiles),
            enabled = statisticsSettings.enabled,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.VPN_PROTOCOLS,
            checked = statisticsSettings.vpnProtocolsEnabled,
            title = stringResource(R.string.statistics_metric_protocols),
            enabled = statisticsSettings.enabled,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.PROFILE_COMPARISONS,
            checked = statisticsSettings.profileComparisonsEnabled,
            title = stringResource(R.string.statistics_metric_comparisons),
            enabled = statisticsSettings.enabled,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.TRANSPORTS,
            checked = statisticsSettings.transportsEnabled,
            title = stringResource(R.string.statistics_metric_transports),
            enabled = statisticsSettings.enabled,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.APP_TRAFFIC,
            checked = statisticsSettings.appTrafficEnabled,
            title = stringResource(R.string.statistics_metric_apps),
            enabled = statisticsSettings.enabled,
            onMetricChanged = { metric, enabled ->
                if (enabled && appStatsSwitchChecked && !usageAccessGranted) {
                    onUsageAccessRequired()
                }
                onStatisticsMetricEnabledChanged(metric, enabled)
            },
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.DNS_FILTERING,
            checked = statisticsSettings.dnsFilteringEnabled,
            title = stringResource(R.string.statistics_metric_dns_filtering),
            summary =
            if (dnsFilteringAvailable) {
                null
            } else {
                stringResource(R.string.statistics_metric_dns_filtering_summary)
            },
            enabled = statisticsSettings.enabled && dnsFilteringAvailable,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.COUNTRY_TRAFFIC,
            checked = statisticsSettings.countryTrafficEnabled,
            title = stringResource(R.string.statistics_metric_countries),
            summary =
            if (state.settings.expert.firewallEnabled) {
                null
            } else {
                stringResource(R.string.statistics_metric_countries_firewall_summary)
            },
            enabled = statisticsSettings.enabled && state.settings.expert.firewallEnabled,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.ANOMALIES,
            checked = statisticsSettings.anomalyMetricsEnabled,
            title = stringResource(R.string.statistics_metric_anomalies),
            enabled = statisticsSettings.enabled,
            onMetricChanged = onStatisticsMetricEnabledChanged,
        )
    }
}
