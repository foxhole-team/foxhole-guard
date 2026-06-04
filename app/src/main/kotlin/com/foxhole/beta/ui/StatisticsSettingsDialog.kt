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
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsSettings

@Composable
internal fun StatisticsSettingsDialog(
    state: StatisticsRouteUiState,
    statisticsSettings: StatisticsSettings,
    appStatsSwitchChecked: Boolean,
    usageAccessGranted: Boolean,
    dnsFilteringAvailable: Boolean,
    retentionMenuExpanded: Boolean,
    actions: StatisticsSettingsDialogActions,
) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
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
                    actions = actions,
                )
                SettingsControlGroup {
                    SettingSwitchRow(
                        title = stringResource(R.string.app_statistics_enabled_title),
                        checked = appStatsSwitchChecked,
                        summary = stringResource(R.string.app_statistics_enabled_summary),
                        leadingIcon = Icons.Outlined.Apps,
                        onCheckedChange = { enabled ->
                            if (enabled && !usageAccessGranted) {
                                actions.onUsageAccessRequired()
                            } else {
                                actions.onUsageAccessCleared()
                                actions.onAppTrafficStatsEnabledChanged(enabled)
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
                    actions = actions,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            FoxholeDialogDismissButton(onClick = actions.onDismiss)
        },
    )
}

internal data class StatisticsSettingsDialogActions(
    val onRetentionMenuExpandedChange: (Boolean) -> Unit,
    val onStatisticsEnabledChanged: (Boolean) -> Unit,
    val onStatisticsRetentionSelected: (StatisticsRetention) -> Unit,
    val onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
    val onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    val onUsageAccessRequired: () -> Unit,
    val onUsageAccessCleared: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun StatisticsStorageSettingsGroup(
    state: StatisticsRouteUiState,
    statisticsSettings: StatisticsSettings,
    retentionMenuExpanded: Boolean,
    actions: StatisticsSettingsDialogActions,
) {
    SettingsControlGroup {
        SettingSwitchRow(
            title = stringResource(R.string.statistics_enabled_title),
            checked = statisticsSettings.enabled,
            summary = stringResource(R.string.statistics_enabled_summary),
            leadingIcon = Icons.Outlined.BarChart,
            onCheckedChange = actions.onStatisticsEnabledChanged,
            grouped = true,
        )
        SettingsControlGroupDivider()
        DropdownSettingRow(
            title = stringResource(R.string.statistics_retention_title),
            value = statisticsRetentionLabel(state.settings.statistics.retention),
            expanded = retentionMenuExpanded,
            onExpandedChange = actions.onRetentionMenuExpandedChange,
            values = StatisticsRetention.entries,
            selected = state.settings.statistics.retention,
            label = { statisticsRetentionLabel(it) },
            onSelect = actions.onStatisticsRetentionSelected,
            leadingIcon = Icons.Outlined.Storage,
            grouped = true,
        )
    }
}

@Composable
private fun StatisticsMetricSettingsGroup(
    state: StatisticsRouteUiState,
    statisticsSettings: StatisticsSettings,
    appStatsSwitchChecked: Boolean,
    usageAccessGranted: Boolean,
    dnsFilteringAvailable: Boolean,
    actions: StatisticsSettingsDialogActions,
) {
    SettingsControlGroup {
        StatisticsMetricSwitch(
            metric = StatisticsMetric.PROFILE_TRAFFIC,
            checked = statisticsSettings.profileTrafficEnabled,
            title = stringResource(R.string.statistics_metric_profiles),
            enabled = statisticsSettings.enabled,
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.VPN_PROTOCOLS,
            checked = statisticsSettings.vpnProtocolsEnabled,
            title = stringResource(R.string.statistics_metric_protocols),
            enabled = statisticsSettings.enabled,
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.PROFILE_COMPARISONS,
            checked = statisticsSettings.profileComparisonsEnabled,
            title = stringResource(R.string.statistics_metric_comparisons),
            enabled = statisticsSettings.enabled,
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.TRANSPORTS,
            checked = statisticsSettings.transportsEnabled,
            title = stringResource(R.string.statistics_metric_transports),
            enabled = statisticsSettings.enabled,
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.APP_TRAFFIC,
            checked = statisticsSettings.appTrafficEnabled,
            title = stringResource(R.string.statistics_metric_apps),
            enabled = statisticsSettings.enabled,
            onMetricChanged = { metric, enabled ->
                if (enabled && appStatsSwitchChecked && !usageAccessGranted) {
                    actions.onUsageAccessRequired()
                }
                actions.onStatisticsMetricEnabledChanged(metric, enabled)
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
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
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
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
        )
        SettingsControlGroupDivider()
        StatisticsMetricSwitch(
            metric = StatisticsMetric.ANOMALIES,
            checked = statisticsSettings.anomalyMetricsEnabled,
            title = stringResource(R.string.statistics_metric_anomalies),
            enabled = statisticsSettings.enabled,
            onMetricChanged = actions.onStatisticsMetricEnabledChanged,
        )
    }
}
