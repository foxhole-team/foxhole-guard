package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Settings
import com.foxhole.core.model.WEB_APPS_POLL_OPTIONS
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onWebAppsDockScreenChanged
import com.foxhole.guard.ui.onWebAppsIsolationChanged
import com.foxhole.guard.ui.onWebAppsPollIntervalChanged
import com.foxhole.guard.ui.onWebAppsPushServiceChanged
import com.foxhole.guard.ui.webAppNotificationsBlocked

@Composable
internal fun CliWebAppsSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val settings = state.settings

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_extras_webapps), icon = R.drawable.lin_webapps)

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliPanel(
                title = stringResource(R.string.cli_extras_webapps),
                icon = R.drawable.lin_link,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliWebAppsPushRows(viewModel = viewModel, settings = settings)
                CliSettingsAnimatedRows(visible = settings.webApps.pushServiceEnabled) {
                    val runtimeSnapshot by FoxholeVpnRuntimeBridge.snapshot.collectAsStateWithLifecycle()
                    CliSettingsAnimatedRows(
                        visible = settings.webApps.isolationEnabled &&
                            runtimeSnapshot.state != ConnectionState.CONNECTED,
                    ) {
                        CliElbowLine(text = stringResource(R.string.cli_webapps_push_idle))
                    }
                    var deliveryBlocked by remember { mutableStateOf(false) }
                    LifecycleResumeEffect(Unit) {
                        deliveryBlocked = viewModel.webAppNotificationsBlocked()
                        onPauseOrDispose { }
                    }
                    CliSettingsAnimatedRows(visible = deliveryBlocked) {
                        CliElbowLine(
                            text = stringResource(R.string.cli_webapps_notifications_disabled),
                            color = LocalCliColors.current.warn,
                        )
                    }
                    CliDropdownRow(
                        label = stringResource(R.string.cli_webapps_interval),
                        icon = R.drawable.lin_clock,
                        value = pollIntervalLabel(settings.webApps.pollIntervalMinutes),
                        options =
                        WEB_APPS_POLL_OPTIONS.map { minutes ->
                            CliDropdownOption(id = minutes.toString(), label = pollIntervalLabel(minutes))
                        },
                        selectedId = settings.webApps.pollIntervalMinutes.toString(),
                        onSelect = { id -> id.toIntOrNull()?.let(viewModel::onWebAppsPollIntervalChanged) },
                    )
                }
                CliToggleRow(
                    label = stringResource(R.string.cli_webapps_isolation),
                    icon = R.drawable.lin_forbidden,
                    checked = settings.webApps.isolationEnabled,
                    infoText = stringResource(R.string.cli_webapps_isolation_note),
                    onToggle = viewModel::onWebAppsIsolationChanged,
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_webapps_dock),
                    icon = R.drawable.lin_home,
                    checked = settings.webApps.dockScreenEnabled,
                    onToggle = viewModel::onWebAppsDockScreenChanged,
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliWebAppsPushRows(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    var consentOpen by remember { mutableStateOf(false) }
    CliToggleRow(
        label = stringResource(R.string.cli_webapps_push),
        icon = R.drawable.lin_info,
        checked = settings.webApps.pushServiceEnabled,
        infoText = stringResource(R.string.cli_webapps_push_note),
        onToggle = { enable ->
            if (enable && !settings.expert.firewallEnabled) {
                consentOpen = true
            } else {
                consentOpen = false
                viewModel.onWebAppsPushServiceChanged(enable)
            }
        },
    )
    if (consentOpen) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_cfg_firewall),
            icon = R.drawable.lin_fire,
            question = stringResource(R.string.cli_webapps_firewall_consent),
            confirmLabel = stringResource(R.string.cli_cfg_firewall_yes),
            onConfirm = {
                consentOpen = false
                viewModel.onWebAppsPushServiceChanged(true)
            },
            onDismiss = { consentOpen = false },
        )
    }
}

private fun pollIntervalLabel(minutes: Int): String = "${minutes}m"
