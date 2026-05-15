@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AnomalyHistoryRetention
import com.foxhole.beta.core.model.AnomalySensitivity

@Composable
fun SecuritySettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onNewAppQuarantineChanged: (Boolean) -> Unit,
    onSystemDnsProtectionChanged: (Boolean) -> Unit,
    onAnomalyEnabledChanged: (Boolean) -> Unit,
    onNotifyUnusualTrafficChanged: (Boolean) -> Unit,
    onAnomalySensitivitySelected: (AnomalySensitivity) -> Unit,
    onAnalyzeBackgroundTrafficChanged: (Boolean) -> Unit,
    onAnalyzeDestinationCountriesChanged: (Boolean) -> Unit,
    onAnomalyHistoryRetentionSelected: (AnomalyHistoryRetention) -> Unit,
    highlightAppInstallMonitor: Boolean = false,
) {
    var sensitivityExpanded by rememberSaveable { mutableStateOf(false) }
    var retentionExpanded by rememberSaveable { mutableStateOf(false) }
    val installedAppChangeCount = state.settings.installedAppInventoryAudit.recentChanges.size
    SettingsScaffold(
        title = stringResource(R.string.security_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "security_settings_screen",
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.security_development_warning_title),
                body = stringResource(R.string.security_development_warning_body),
                toneColor = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            SecurityProtectionControlGroup(
                state = state,
                installedAppChangeCount = installedAppChangeCount,
                highlightAppInstallMonitor = highlightAppInstallMonitor,
                onFirewallEnabledChanged = onFirewallEnabledChanged,
                onSystemDnsProtectionChanged = onSystemDnsProtectionChanged,
                onNewAppQuarantineChanged = onNewAppQuarantineChanged,
            )
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_enabled_title),
                    checked = state.settings.anomaly.enabled,
                    summary = stringResource(R.string.anomaly_enabled_summary),
                    leadingIcon = Icons.Outlined.QueryStats,
                    onCheckedChange = onAnomalyEnabledChanged,
                    summaryMaxLines = 4,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_notify_title),
                    checked = state.settings.anomaly.notifyUnusualTraffic,
                    summary = stringResource(R.string.anomaly_notify_summary),
                    leadingIcon = Icons.Outlined.Notifications,
                    onCheckedChange = onNotifyUnusualTrafficChanged,
                    enabled = state.settings.anomaly.enabled,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.anomaly_sensitivity_title),
                    value = anomalySensitivityLabel(state.settings.anomaly.sensitivity),
                    expanded = sensitivityExpanded,
                    onExpandedChange = { sensitivityExpanded = it },
                    values = AnomalySensitivity.entries,
                    selected = state.settings.anomaly.sensitivity,
                    label = { anomalySensitivityLabel(it) },
                    onSelect = onAnomalySensitivitySelected,
                    leadingIcon = Icons.Outlined.QueryStats,
                    enabled = state.settings.anomaly.enabled,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_background_title),
                    checked = state.settings.anomaly.analyzeBackgroundTraffic,
                    summary = stringResource(R.string.anomaly_background_summary),
                    leadingIcon = Icons.Outlined.QueryStats,
                    onCheckedChange = onAnalyzeBackgroundTrafficChanged,
                    enabled = state.settings.anomaly.enabled,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_countries_title),
                    checked = state.settings.anomaly.analyzeDestinationCountries,
                    summary = stringResource(R.string.anomaly_countries_summary),
                    leadingIcon = Icons.Outlined.QueryStats,
                    onCheckedChange = onAnalyzeDestinationCountriesChanged,
                    enabled = state.settings.anomaly.enabled,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.anomaly_history_title),
                    value = anomalyHistoryRetentionLabel(state.settings.anomaly.historyRetention),
                    expanded = retentionExpanded,
                    onExpandedChange = { retentionExpanded = it },
                    values = AnomalyHistoryRetention.entries,
                    selected = state.settings.anomaly.historyRetention,
                    label = { anomalyHistoryRetentionLabel(it) },
                    onSelect = onAnomalyHistoryRetentionSelected,
                    leadingIcon = Icons.Outlined.QueryStats,
                    enabled = state.settings.anomaly.enabled,
                    grouped = true,
                )
            }
        }
    }
}

@Composable
private fun SecurityProtectionControlGroup(
    state: SettingsRouteUiState,
    installedAppChangeCount: Int,
    highlightAppInstallMonitor: Boolean,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onSystemDnsProtectionChanged: (Boolean) -> Unit,
    onNewAppQuarantineChanged: (Boolean) -> Unit,
) {
    val installedAppMonitorValue =
        pluralStringResource(
            R.plurals.security_app_install_monitor_value,
            installedAppChangeCount,
            installedAppChangeCount,
        )
    SettingsControlGroup {
        SettingSwitchRow(
            title = stringResource(R.string.security_firewall_title),
            checked = state.settings.expert.firewallEnabled,
            summary = stringResource(R.string.security_firewall_summary),
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_firewall_shield_key),
            onCheckedChange = onFirewallEnabledChanged,
            summaryMaxLines = 3,
            grouped = true,
        )
        SettingsControlGroupDivider()
        SettingSwitchRow(
            title = stringResource(R.string.security_system_dns_protection_title),
            checked = state.settings.expert.systemDnsProtectionEnabled,
            summary = stringResource(R.string.security_system_dns_protection_summary),
            leadingIcon = Icons.Outlined.Dns,
            onCheckedChange = onSystemDnsProtectionChanged,
            summaryMaxLines = 3,
            grouped = true,
        )
        SettingsControlGroupDivider()
        AppInstallMonitorSettingsRow(
            value = installedAppMonitorValue,
            highlight = highlightAppInstallMonitor,
        )
        SettingsControlGroupDivider()
        SettingSwitchRow(
            title = stringResource(R.string.security_new_app_quarantine_title),
            checked = state.settings.expert.newAppQuarantineEnabled,
            summary = stringResource(R.string.security_new_app_quarantine_summary),
            leadingIcon = Icons.Outlined.Apps,
            onCheckedChange = onNewAppQuarantineChanged,
            summaryMaxLines = Int.MAX_VALUE,
            grouped = true,
        )
    }
}

@Composable
private fun AppInstallMonitorSettingsRow(
    value: String,
    highlight: Boolean,
) {
    val content: @Composable () -> Unit = {
        SettingValueRow(
            title = stringResource(R.string.security_app_install_monitor_title),
            value = value,
            summary = stringResource(R.string.security_app_install_monitor_summary),
            leadingIcon = Icons.Outlined.Apps,
            onClick = null,
            summaryMaxLines = 5,
            grouped = true,
        )
    }
    if (highlight) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        ) {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun anomalySensitivityLabel(value: AnomalySensitivity): String =
    stringResource(
        when (value) {
            AnomalySensitivity.NORMAL -> R.string.anomaly_sensitivity_normal
            AnomalySensitivity.STRICT -> R.string.anomaly_sensitivity_strict
        },
    )

@Composable
private fun anomalyHistoryRetentionLabel(value: AnomalyHistoryRetention): String =
    stringResource(
        when (value) {
            AnomalyHistoryRetention.HOURS_24 -> R.string.anomaly_history_24h
            AnomalyHistoryRetention.DAYS_7 -> R.string.anomaly_history_7d
            AnomalyHistoryRetention.DAYS_30 -> R.string.anomaly_history_30d
        },
    )
