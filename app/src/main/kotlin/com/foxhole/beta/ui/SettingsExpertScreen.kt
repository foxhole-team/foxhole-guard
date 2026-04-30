package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
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
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.ProxyInboundSettings

@Composable
fun ExpertSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onShowExpertSettingsChanged: (Boolean) -> Unit,
    onAcknowledgeUnsafeWarning: () -> Unit,
    onSniffChanged: (Boolean) -> Unit,
    onRouteOnlyChanged: (Boolean) -> Unit,
    onStrictRouteChanged: (Boolean) -> Unit,
    onBypassLanChanged: (Boolean) -> Unit,
    onAllowPrivateOutboundHostsChanged: (Boolean) -> Unit,
    onNetworkActivityLoggingChanged: (Boolean) -> Unit,
    onSmartStartReplayLoggingChanged: (Boolean) -> Unit,
    onDiagnosticsRetentionSelected: (DiagnosticsRetention) -> Unit,
    onAllowHttpConfigImportsChanged: (Boolean) -> Unit,
    onAllowInsecureTlsChanged: (Boolean) -> Unit,
    onLocalProxyAuthEnabledChanged: (Boolean) -> Unit,
    onLocalProxyAuthChanged: (LocalAuthSettings) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onSocksSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onHttpSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onMixedSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onClashApiChanged: (ClashApiSettings) -> Unit,
    onResetToSafeDefaults: () -> Unit,
) {
    var socksDialog by rememberSaveable { mutableStateOf(false) }
    var httpDialog by rememberSaveable { mutableStateOf(false) }
    var mixedDialog by rememberSaveable { mutableStateOf(false) }
    var clashDialog by rememberSaveable { mutableStateOf(false) }
    var showWarning by rememberSaveable { mutableStateOf(false) }
    var diagnosticsRetentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingUnsafeAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val wifiLanAddress by rememberWifiLanAddress()

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
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body = stringResource(R.string.expert_settings_info_body),
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.show_advanced_settings_title),
                checked = state.settings.ui.showExpertSettings,
                summary = stringResource(R.string.show_advanced_settings_summary),
                leadingIcon = Icons.Outlined.Shield,
                onCheckedChange = onShowExpertSettingsChanged,
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.sniff_traffic),
                checked = state.settings.expert.sniff,
                summary = stringResource(R.string.sniff_traffic_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onSniffChanged(true) }
                    } else {
                        onSniffChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.route_only),
                checked = state.settings.expert.routeOnly,
                summary = stringResource(R.string.route_only_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onRouteOnlyChanged(true) }
                    } else {
                        onRouteOnlyChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.strict_route),
                checked = state.settings.expert.strictRoute,
                summary = stringResource(R.string.strict_route_summary),
                onCheckedChange = onStrictRouteChanged,
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.bypass_lan),
                checked = state.settings.expert.bypassLan,
                summary = stringResource(R.string.bypass_lan_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onBypassLanChanged(true) }
                    } else {
                        onBypassLanChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.allow_private_outbound_hosts),
                checked = state.settings.expert.allowPrivateOutboundHosts,
                summary = stringResource(R.string.allow_private_outbound_hosts_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onAllowPrivateOutboundHostsChanged(true) }
                    } else {
                        onAllowPrivateOutboundHostsChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.network_activity_logging_title),
                checked = state.settings.expert.networkActivityLogging,
                summary = stringResource(R.string.network_activity_logging_summary),
                onCheckedChange = onNetworkActivityLoggingChanged,
                summaryMaxLines = 4,
            )
        }
        item {
            DropdownSettingRow(
                title = stringResource(R.string.diagnostics_retention_title),
                value = diagnosticsRetentionLabel(state.settings.expert.diagnosticsRetention),
                expanded = diagnosticsRetentionMenuExpanded,
                onExpandedChange = { diagnosticsRetentionMenuExpanded = it },
                values = DiagnosticsRetention.entries,
                selected = state.settings.expert.diagnosticsRetention,
                label = { diagnosticsRetentionLabel(it) },
                onSelect = onDiagnosticsRetentionSelected,
                summary = stringResource(R.string.diagnostics_retention_summary),
                leadingIcon = Icons.Outlined.Info,
                optionIcon = { Icons.Outlined.Tune },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.smart_start_replay_logging_title),
                checked = state.settings.expert.smartStartReplayLogging,
                summary = stringResource(R.string.smart_start_replay_logging_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onSmartStartReplayLoggingChanged(true) }
                    } else {
                        onSmartStartReplayLoggingChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.allow_http_config_imports_title),
                checked = state.settings.expert.allowHttpConfigImports,
                summary = stringResource(R.string.allow_http_config_imports_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onAllowHttpConfigImportsChanged(true) }
                    } else {
                        onAllowHttpConfigImportsChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.allow_insecure_tls_title),
                checked = state.settings.expert.allowInsecureTls,
                summary = stringResource(R.string.allow_insecure_tls_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onAllowInsecureTlsChanged(true) }
                    } else {
                        onAllowInsecureTlsChanged(false)
                    }
                },
                summaryMaxLines = 3,
            )
        }
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.proxy_auth_title),
                checked = state.settings.expert.localSurfaces.auth.enabled,
                summary = stringResource(R.string.proxy_auth_summary),
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requireWarning { onLocalProxyAuthEnabledChanged(true) }
                    } else {
                        onLocalProxyAuthEnabledChanged(false)
                    }
                },
            )
        }
        item {
            LocalProxyAuthEditor(
                auth = state.settings.expert.localSurfaces.auth,
                onAuthChanged = { value ->
                    if (value.username.isNotBlank() && value.password.isNotBlank()) {
                        requireWarning { onLocalProxyAuthChanged(value) }
                    } else {
                        onLocalProxyAuthChanged(value)
                    }
                },
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.socks_inbound),
                value = surfaceSummary(state.settings.expert.localSurfaces.socks, state.settings.expert.localSurfaces.auth.enabled),
                onClick = { socksDialog = true },
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.http_inbound),
                value = surfaceSummary(state.settings.expert.localSurfaces.http, state.settings.expert.localSurfaces.auth.enabled),
                onClick = { httpDialog = true },
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.mixed_inbound),
                value = surfaceSummary(state.settings.expert.localSurfaces.mixed, state.settings.expert.localSurfaces.auth.enabled),
                onClick = { mixedDialog = true },
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.clash_api),
                value = clashSummary(state.settings.expert.localSurfaces.clashApi),
                onClick = { clashDialog = true },
            )
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

    if (socksDialog) {
        ProxySurfaceDialog(
            title = stringResource(R.string.socks_inbound),
            initialValue = state.settings.expert.localSurfaces.socks,
            auth = state.settings.expert.localSurfaces.auth,
            lanAccessEnabled = state.settings.expert.localSurfaces.allowLanAccess,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { socksDialog = false },
            onConfirm = { value ->
                if (value.enabled) {
                    requireWarning { onSocksSurfaceChanged(value) }
                } else {
                    onSocksSurfaceChanged(value)
                }
            },
        )
    }

    if (httpDialog) {
        ProxySurfaceDialog(
            title = stringResource(R.string.http_inbound),
            initialValue = state.settings.expert.localSurfaces.http,
            auth = state.settings.expert.localSurfaces.auth,
            lanAccessEnabled = state.settings.expert.localSurfaces.allowLanAccess,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { httpDialog = false },
            onConfirm = { value ->
                if (value.enabled) {
                    requireWarning { onHttpSurfaceChanged(value) }
                } else {
                    onHttpSurfaceChanged(value)
                }
            },
        )
    }

    if (mixedDialog) {
        ProxySurfaceDialog(
            title = stringResource(R.string.mixed_inbound),
            initialValue = state.settings.expert.localSurfaces.mixed,
            auth = state.settings.expert.localSurfaces.auth,
            lanAccessEnabled = state.settings.expert.localSurfaces.allowLanAccess,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { mixedDialog = false },
            onConfirm = { value ->
                if (value.enabled) {
                    requireWarning { onMixedSurfaceChanged(value) }
                } else {
                    onMixedSurfaceChanged(value)
                }
            },
        )
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
