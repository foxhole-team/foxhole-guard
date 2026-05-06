package com.foxhole.beta.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.foxhole.beta.R

@Composable
fun SecuritySettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.security_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "security_settings_screen",
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.security_firewall_title),
                    checked = state.settings.expert.firewallEnabled,
                    summary = stringResource(R.string.security_firewall_summary),
                    infoBody = stringResource(R.string.security_firewall_info_body),
                    leadingIcon = ImageVector.vectorResource(R.drawable.ic_firewall_shield_key),
                    leadingIconContainerColor = Color.Transparent,
                    onCheckedChange = onFirewallEnabledChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
            }
        }
    }
}
