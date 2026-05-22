@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AnomalyHistoryRetention
import com.foxhole.beta.core.model.AnomalySensitivity

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun SecuritySettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onNewAppQuarantineChanged: (Boolean) -> Unit,
    onInstalledAppMonitoringChanged: (Boolean) -> Unit,
    onSystemDnsProtectionChanged: (Boolean) -> Unit,
    onAnomalyEnabledChanged: (Boolean) -> Unit,
    onNotifyUnusualTrafficChanged: (Boolean) -> Unit,
    onAnomalySensitivitySelected: (AnomalySensitivity) -> Unit,
    onAnalyzeBackgroundTrafficChanged: (Boolean) -> Unit,
    onAnalyzeDestinationCountriesChanged: (Boolean) -> Unit,
    onAnomalyHistoryRetentionSelected: (AnomalyHistoryRetention) -> Unit,
) {
    var sensitivityExpanded by rememberSaveable { mutableStateOf(false) }
    var retentionExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingSecurityToggle by rememberSaveable { mutableStateOf<SecurityToggleTarget?>(null) }
    fun applySecurityToggle(
        target: SecurityToggleTarget,
        enabled: Boolean,
    ) {
        when (target) {
            SecurityToggleTarget.FIREWALL -> onFirewallEnabledChanged(enabled)
            SecurityToggleTarget.SYSTEM_DNS -> onSystemDnsProtectionChanged(enabled)
            SecurityToggleTarget.APP_MONITORING -> onInstalledAppMonitoringChanged(enabled)
            SecurityToggleTarget.NEW_APP_QUARANTINE -> onNewAppQuarantineChanged(enabled)
            SecurityToggleTarget.ANOMALY -> onAnomalyEnabledChanged(enabled)
            SecurityToggleTarget.ANOMALY_NOTIFY -> onNotifyUnusualTrafficChanged(enabled)
            SecurityToggleTarget.ANOMALY_BACKGROUND -> onAnalyzeBackgroundTrafficChanged(enabled)
            SecurityToggleTarget.ANOMALY_COUNTRIES -> onAnalyzeDestinationCountriesChanged(enabled)
        }
    }
    fun requestSecurityToggle(
        target: SecurityToggleTarget,
        enabled: Boolean,
    ) {
        if (enabled) {
            pendingSecurityToggle = target
        } else {
            applySecurityToggle(target, false)
        }
    }
    SettingsScaffold(
        title = stringResource(R.string.security_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "security_settings_screen",
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.security_settings_title),
                body = stringResource(R.string.security_settings_info_body),
                icon = Icons.Outlined.Shield,
            )
        },
    ) {
        if (BuildConfig.DEBUG) {
            item {
                InfoBlock(
                    title = stringResource(R.string.security_development_warning_title),
                    body = stringResource(R.string.security_development_warning_body),
                    toneColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            SecurityProtectionControlGroup(
                state = state,
                onFirewallEnabledChanged = { value ->
                    requestSecurityToggle(SecurityToggleTarget.FIREWALL, value)
                },
                onSystemDnsProtectionChanged = { value ->
                    requestSecurityToggle(SecurityToggleTarget.SYSTEM_DNS, value)
                },
                onInstalledAppMonitoringChanged = { value ->
                    requestSecurityToggle(SecurityToggleTarget.APP_MONITORING, value)
                },
                onNewAppQuarantineChanged = { value ->
                    requestSecurityToggle(SecurityToggleTarget.NEW_APP_QUARANTINE, value)
                },
            )
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_enabled_title),
                    checked = state.settings.anomaly.enabled,
                    summary = stringResource(R.string.anomaly_enabled_summary),
                    leadingIcon = Icons.Outlined.QueryStats,
                    onCheckedChange = { value -> requestSecurityToggle(SecurityToggleTarget.ANOMALY, value) },
                    summaryMaxLines = 4,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_notify_title),
                    checked = state.settings.anomaly.notifyUnusualTraffic,
                    summary = stringResource(R.string.anomaly_notify_summary),
                    leadingIcon = Icons.Outlined.Notifications,
                    onCheckedChange = { value -> requestSecurityToggle(SecurityToggleTarget.ANOMALY_NOTIFY, value) },
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
                    onCheckedChange = { value ->
                        requestSecurityToggle(SecurityToggleTarget.ANOMALY_BACKGROUND, value)
                    },
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
                    onCheckedChange = { value ->
                        requestSecurityToggle(SecurityToggleTarget.ANOMALY_COUNTRIES, value)
                    },
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
    pendingSecurityToggle?.let { target ->
        SecurityEarlyDevelopmentWarningDialog(
            onConfirm = {
                applySecurityToggle(target, true)
                pendingSecurityToggle = null
            },
            onDismiss = { pendingSecurityToggle = null },
        )
    }
}

private enum class SecurityToggleTarget {
    FIREWALL,
    SYSTEM_DNS,
    APP_MONITORING,
    NEW_APP_QUARANTINE,
    ANOMALY,
    ANOMALY_NOTIFY,
    ANOMALY_BACKGROUND,
    ANOMALY_COUNTRIES,
}

@Composable
private fun SecurityEarlyDevelopmentWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.foxholeDialogChrome(),
        shape = FoxholeDialogShape,
        title = {
            FoxholeDialogTitle(
                title = stringResource(R.string.security_toggle_warning_title),
                icon = Icons.Outlined.WarningAmber,
                iconTint = MaterialTheme.colorScheme.error,
                iconContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.security_toggle_warning_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = onConfirm,
                label = stringResource(R.string.enable_label),
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(
                onClick = onDismiss,
                label = stringResource(R.string.cancel),
            )
        },
    )
}

@Composable
private fun SecurityProtectionControlGroup(
    state: SettingsRouteUiState,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onSystemDnsProtectionChanged: (Boolean) -> Unit,
    onInstalledAppMonitoringChanged: (Boolean) -> Unit,
    onNewAppQuarantineChanged: (Boolean) -> Unit,
) {
    val installedAppMonitoringEnabled =
        state.settings.statistics.enabled && state.settings.statistics.appChangesEnabled
    SettingsControlGroup {
        SettingSwitchRow(
            title = stringResource(R.string.security_firewall_title),
            checked = state.settings.expert.firewallEnabled,
            summary = stringResource(R.string.security_firewall_summary),
            leadingIcon = Icons.Outlined.Shield,
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
            checked = installedAppMonitoringEnabled,
            onCheckedChange = onInstalledAppMonitoringChanged,
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
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingSwitchRow(
        title = stringResource(R.string.security_app_install_monitor_title),
        checked = checked,
        summary = stringResource(R.string.security_app_install_monitor_summary),
        leadingIcon = Icons.Outlined.Apps,
        onCheckedChange = onCheckedChange,
        summaryMaxLines = 5,
        grouped = true,
    )
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
