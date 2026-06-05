package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.foxhole.beta.R

@Composable
@Suppress("LongMethod")
fun PrivacyLocalDataSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onClearNetworkActivity: () -> Unit,
    onClearAppTrafficStats: () -> Unit,
    onClearProfilesAndSecrets: () -> Unit,
    onFactoryReset: () -> Unit,
) {
    var pendingAction by rememberSaveable { mutableStateOf<PrivacyLocalDataAction?>(null) }
    val usageAccessGranted = rememberUsageAccessGranted()
    val usageAccessEnabled = state.settings.appTrafficUsageAccessConsent && usageAccessGranted

    SettingsScaffold(
        title = stringResource(R.string.privacy_local_data_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "privacy_local_data_settings_screen",
    ) {
        item {
            SettingsControlGroup {
                SettingValueRow(
                    title = stringResource(R.string.privacy_local_data_usage_access_status_title),
                    value =
                    stringResource(
                        if (usageAccessEnabled) {
                            R.string.privacy_local_data_usage_access_on
                        } else {
                            R.string.privacy_local_data_usage_access_off
                        },
                    ),
                    summary = stringResource(R.string.privacy_local_data_usage_access_status_summary),
                    leadingIcon = FoxholeIcons.PrivacyLocalData,
                    onClick = null,
                    grouped = true,
                )
            }
        }
        item {
            SettingsControlGroup {
                PrivacyLocalDataInfoRow(
                    title = stringResource(R.string.privacy_local_data_stored_title),
                    summary = stringResource(R.string.privacy_local_data_stored_body),
                    icon = Icons.Outlined.Storage,
                )
                SettingsControlGroupDivider()
                PrivacyLocalDataInfoRow(
                    title = stringResource(R.string.privacy_local_data_used_title),
                    summary = stringResource(R.string.privacy_local_data_used_body),
                    icon = Icons.Outlined.BarChart,
                )
                SettingsControlGroupDivider()
                PrivacyLocalDataInfoRow(
                    title = stringResource(R.string.privacy_local_data_retention_title),
                    summary = stringResource(R.string.privacy_local_data_retention_body),
                    icon = Icons.Outlined.History,
                )
            }
        }
        item {
            SettingsControlGroup {
                PrivacyLocalDataActionRow(
                    action = PrivacyLocalDataAction.CLEAR_DIAGNOSTICS,
                    onClick = { pendingAction = PrivacyLocalDataAction.CLEAR_DIAGNOSTICS },
                )
                SettingsControlGroupDivider()
                PrivacyLocalDataActionRow(
                    action = PrivacyLocalDataAction.CLEAR_NETWORK_ACTIVITY,
                    onClick = { pendingAction = PrivacyLocalDataAction.CLEAR_NETWORK_ACTIVITY },
                )
                SettingsControlGroupDivider()
                PrivacyLocalDataActionRow(
                    action = PrivacyLocalDataAction.CLEAR_APP_TRAFFIC,
                    onClick = { pendingAction = PrivacyLocalDataAction.CLEAR_APP_TRAFFIC },
                )
                SettingsControlGroupDivider()
                PrivacyLocalDataActionRow(
                    action = PrivacyLocalDataAction.CLEAR_PROFILES,
                    onClick = { pendingAction = PrivacyLocalDataAction.CLEAR_PROFILES },
                )
                SettingsControlGroupDivider()
                PrivacyLocalDataActionRow(
                    action = PrivacyLocalDataAction.FACTORY_RESET,
                    modifier = Modifier.testTag("privacy_local_data_factory_reset_action"),
                    onClick = { pendingAction = PrivacyLocalDataAction.FACTORY_RESET },
                )
            }
        }
    }

    pendingAction?.let { action ->
        ConfirmDialog(
            title = stringResource(action.confirmTitleRes),
            body = stringResource(action.confirmBodyRes),
            confirmLabel = stringResource(action.confirmLabelRes),
            dismissLabel = stringResource(R.string.cancel),
            icon = action.icon,
            onDismiss = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                when (action) {
                    PrivacyLocalDataAction.CLEAR_DIAGNOSTICS -> onClearDiagnostics()
                    PrivacyLocalDataAction.CLEAR_NETWORK_ACTIVITY -> onClearNetworkActivity()
                    PrivacyLocalDataAction.CLEAR_APP_TRAFFIC -> onClearAppTrafficStats()
                    PrivacyLocalDataAction.CLEAR_PROFILES -> onClearProfilesAndSecrets()
                    PrivacyLocalDataAction.FACTORY_RESET -> onFactoryReset()
                }
            },
        )
    }
}

@Composable
private fun PrivacyLocalDataInfoRow(
    title: String,
    summary: String,
    icon: ImageVector,
) {
    SettingValueRow(
        title = title,
        value = "",
        summary = summary,
        leadingIcon = icon,
        onClick = null,
        trailingContent = {},
        summaryMaxLines = Int.MAX_VALUE,
        grouped = true,
    )
}

@Composable
private fun PrivacyLocalDataActionRow(
    action: PrivacyLocalDataAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SettingsNavigationRow(
        modifier = modifier,
        icon = action.icon,
        title = stringResource(action.titleRes),
        summary = stringResource(action.summaryRes),
        summaryMaxLines = Int.MAX_VALUE,
        grouped = true,
        onClick = onClick,
    )
}

private enum class PrivacyLocalDataAction(
    val icon: ImageVector,
    val titleRes: Int,
    val summaryRes: Int,
    val confirmTitleRes: Int,
    val confirmBodyRes: Int,
    val confirmLabelRes: Int,
) {
    CLEAR_DIAGNOSTICS(
        icon = Icons.Outlined.Troubleshoot,
        titleRes = R.string.privacy_local_data_clear_diagnostics_title,
        summaryRes = R.string.privacy_local_data_clear_diagnostics_summary,
        confirmTitleRes = R.string.privacy_local_data_clear_diagnostics_confirm_title,
        confirmBodyRes = R.string.privacy_local_data_clear_diagnostics_confirm_body,
        confirmLabelRes = R.string.privacy_local_data_clear_diagnostics_title,
    ),
    CLEAR_NETWORK_ACTIVITY(
        icon = Icons.Outlined.DeleteSweep,
        titleRes = R.string.privacy_local_data_clear_network_activity_title,
        summaryRes = R.string.privacy_local_data_clear_network_activity_summary,
        confirmTitleRes = R.string.privacy_local_data_clear_network_activity_confirm_title,
        confirmBodyRes = R.string.privacy_local_data_clear_network_activity_confirm_body,
        confirmLabelRes = R.string.privacy_local_data_clear_network_activity_title,
    ),
    CLEAR_APP_TRAFFIC(
        icon = Icons.Outlined.BarChart,
        titleRes = R.string.privacy_local_data_clear_app_traffic_title,
        summaryRes = R.string.privacy_local_data_clear_app_traffic_summary,
        confirmTitleRes = R.string.privacy_local_data_clear_app_traffic_confirm_title,
        confirmBodyRes = R.string.privacy_local_data_clear_app_traffic_confirm_body,
        confirmLabelRes = R.string.privacy_local_data_clear_app_traffic_title,
    ),
    CLEAR_PROFILES(
        icon = Icons.Outlined.FolderDelete,
        titleRes = R.string.privacy_local_data_clear_profiles_title,
        summaryRes = R.string.privacy_local_data_clear_profiles_summary,
        confirmTitleRes = R.string.privacy_local_data_clear_profiles_confirm_title,
        confirmBodyRes = R.string.privacy_local_data_clear_profiles_confirm_body,
        confirmLabelRes = R.string.privacy_local_data_clear_profiles_title,
    ),
    FACTORY_RESET(
        icon = Icons.Outlined.RestartAlt,
        titleRes = R.string.privacy_local_data_factory_reset_title,
        summaryRes = R.string.privacy_local_data_factory_reset_summary,
        confirmTitleRes = R.string.privacy_local_data_factory_reset_confirm_title,
        confirmBodyRes = R.string.privacy_local_data_factory_reset_confirm_body,
        confirmLabelRes = R.string.privacy_local_data_factory_reset_title,
    ),
}
