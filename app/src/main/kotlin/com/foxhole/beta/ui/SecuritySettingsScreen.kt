package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AnomalyHistoryRetention
import com.foxhole.beta.core.model.AnomalySensitivity

@Composable
fun SecuritySettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onNotifyUnusualTrafficChanged: (Boolean) -> Unit,
    onAnomalySensitivitySelected: (AnomalySensitivity) -> Unit,
    onAnalyzeBackgroundTrafficChanged: (Boolean) -> Unit,
    onAnalyzeDestinationCountriesChanged: (Boolean) -> Unit,
    onAnomalyHistoryRetentionSelected: (AnomalyHistoryRetention) -> Unit,
) {
    var sensitivityExpanded by rememberSaveable { mutableStateOf(false) }
    var retentionExpanded by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.security_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "security_settings_screen",
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.anomaly_algorithms_title),
                body = stringResource(R.string.anomaly_algorithms_info_body),
            )
        },
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.security_firewall_title),
                    checked = state.settings.expert.firewallEnabled,
                    summary = stringResource(R.string.security_firewall_summary),
                    infoBody = stringResource(R.string.security_firewall_info_body),
                    leadingIcon = ImageVector.vectorResource(R.drawable.ic_firewall_shield_key),
                    onCheckedChange = onFirewallEnabledChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
            }
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_notify_title),
                    checked = state.settings.anomaly.notifyUnusualTraffic,
                    summary = stringResource(R.string.anomaly_notify_summary),
                    infoBody = stringResource(R.string.anomaly_notify_info_body),
                    leadingIcon = Icons.Outlined.Notifications,
                    onCheckedChange = onNotifyUnusualTrafficChanged,
                    summaryMaxLines = 2,
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
                    infoBody = stringResource(R.string.anomaly_sensitivity_info_body),
                    leadingIcon = Icons.Outlined.QueryStats,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_background_title),
                    checked = state.settings.anomaly.analyzeBackgroundTraffic,
                    summary = stringResource(R.string.anomaly_background_summary),
                    infoBody = stringResource(R.string.anomaly_background_info_body),
                    leadingIcon = Icons.Outlined.QueryStats,
                    onCheckedChange = onAnalyzeBackgroundTrafficChanged,
                    summaryMaxLines = 2,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.anomaly_countries_title),
                    checked = state.settings.anomaly.analyzeDestinationCountries,
                    summary = stringResource(R.string.anomaly_countries_summary),
                    infoBody = stringResource(R.string.anomaly_countries_info_body),
                    leadingIcon = Icons.Outlined.QueryStats,
                    onCheckedChange = onAnalyzeDestinationCountriesChanged,
                    summaryMaxLines = 2,
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
                    infoBody = stringResource(R.string.anomaly_history_info_body),
                    leadingIcon = Icons.Outlined.QueryStats,
                    grouped = true,
                )
            }
        }
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
