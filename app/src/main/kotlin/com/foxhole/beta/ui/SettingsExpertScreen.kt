package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ClashApiSettings

@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
@Composable
fun ExpertSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onShowExpertSettingsChanged: (Boolean) -> Unit,
    onAcknowledgeUnsafeWarning: () -> Unit,
    onRouteOnlyChanged: (Boolean) -> Unit,
    onStrictRouteChanged: (Boolean) -> Unit,
    onAllowPrivateOutboundHostsChanged: (Boolean) -> Unit,
    onSmartStartReplayLoggingChanged: (Boolean) -> Unit,
    onAllowInsecureTlsChanged: (Boolean) -> Unit,
    onClashApiChanged: (ClashApiSettings) -> Unit,
    onResetToSafeDefaults: () -> Unit,
) {
    var clashDialog by rememberSaveable { mutableStateOf(false) }
    var showWarning by rememberSaveable { mutableStateOf(false) }
    var pendingUnsafeAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requireWarning(action: () -> Unit) {
        if (state.settings.expert.warningAcknowledgedAt != null) {
            action()
        } else {
            pendingUnsafeAction = action
            showWarning = true
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.expert_settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.expert_settings),
                body = stringResource(R.string.help_expert_full_body),
                icon = Icons.Outlined.Tune,
            )
        },
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.show_advanced_settings_title),
                    checked = state.settings.ui.showExpertSettings,
                    summary = stringResource(R.string.show_advanced_settings_summary),
                    leadingIcon = Icons.Outlined.Shield,
                    onCheckedChange = onShowExpertSettingsChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
            }
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.route_only),
                    checked = state.settings.expert.routeOnly,
                    summary = stringResource(R.string.route_only_summary),
                    leadingIcon = Icons.Outlined.AccountTree,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requireWarning { onRouteOnlyChanged(true) }
                        } else {
                            onRouteOnlyChanged(false)
                        }
                    },
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.strict_route),
                    checked = state.settings.expert.strictRoute,
                    summary = stringResource(R.string.strict_route_summary),
                    leadingIcon = Icons.Outlined.Shield,
                    onCheckedChange = onStrictRouteChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.allow_private_outbound_hosts),
                    checked = state.settings.expert.allowPrivateOutboundHosts,
                    summary = stringResource(R.string.allow_private_outbound_hosts_summary),
                    leadingIcon = Icons.Outlined.Router,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requireWarning { onAllowPrivateOutboundHostsChanged(true) }
                        } else {
                            onAllowPrivateOutboundHostsChanged(false)
                        }
                    },
                    summaryMaxLines = 3,
                    grouped = true,
                )
                if (BuildConfig.DEBUG) {
                    SettingsControlGroupDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.smart_start_replay_logging_title),
                        checked = state.settings.expert.smartStartReplayLogging,
                        summary = stringResource(R.string.smart_start_replay_logging_summary),
                        leadingIcon = Icons.Outlined.Speed,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                requireWarning { onSmartStartReplayLoggingChanged(true) }
                            } else {
                                onSmartStartReplayLoggingChanged(false)
                            }
                        },
                        summaryMaxLines = 3,
                        grouped = true,
                    )
                }
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.allow_insecure_tls_title),
                    checked = state.settings.expert.allowInsecureTls,
                    summary = stringResource(R.string.allow_insecure_tls_summary),
                    leadingIcon = Icons.Outlined.Public,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requireWarning { onAllowInsecureTlsChanged(true) }
                        } else {
                            onAllowInsecureTlsChanged(false)
                        }
                    },
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.clash_api),
                    value = clashSummary(state.settings.expert.localSurfaces.clashApi),
                    leadingIcon = Icons.Outlined.Tune,
                    onClick = { clashDialog = true },
                    grouped = true,
                )
            }
        }
        item {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onResetToSafeDefaults) {
                    Text(stringResource(R.string.reset_to_safe_defaults))
                }
            }
        }
    }

    if (clashDialog) {
        ClashApiDialog(
            initialValue = state.settings.expert.localSurfaces.clashApi,
            auth = state.settings.expert.localSurfaces.auth,
            onDismiss = { clashDialog = false },
            onConfirm = { value ->
                if (value.enabled) {
                    requireWarning { onClashApiChanged(value) }
                } else {
                    onClashApiChanged(value)
                }
            },
        )
    }

    if (showWarning) {
        ConfirmDialog(
            title = stringResource(R.string.expert_warning_title),
            body = stringResource(R.string.expert_warning_body),
            confirmLabel = stringResource(R.string.i_understand),
            icon = Icons.Outlined.Shield,
            onDismiss = {
                showWarning = false
                pendingUnsafeAction = null
            },
            onConfirm = {
                onAcknowledgeUnsafeWarning()
                pendingUnsafeAction?.invoke()
                showWarning = false
                pendingUnsafeAction = null
            },
        )
    }
}
