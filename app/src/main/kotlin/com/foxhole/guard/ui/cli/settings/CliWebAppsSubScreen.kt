package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Settings
import com.foxhole.core.model.WEB_APPS_POLL_OPTIONS
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onWebAppsDockScreenChanged
import com.foxhole.guard.ui.onWebAppsPollIntervalChanged
import com.foxhole.guard.ui.onWebAppsPushServiceChanged

/** Web apps settings: the push watchdog with its firewall consent, the interval and the dock. */
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_extras_webapps), icon = R.drawable.pix_webapps)
        CliPanel(
            title = stringResource(R.string.cli_extras_webapps),
            icon = R.drawable.pix_link,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliWebAppsPushRows(viewModel = viewModel, settings = settings)
            if (settings.webApps.pushServiceEnabled) {
                // The watchdog is gated on a raised guard, so say plainly that it is idle.
                val runtimeSnapshot by FoxholeVpnRuntimeBridge.snapshot.collectAsStateWithLifecycle()
                if (runtimeSnapshot.state != ConnectionState.CONNECTED) {
                    CliElbowLine(text = stringResource(R.string.cli_webapps_push_idle))
                }
            }
            if (settings.webApps.pushServiceEnabled) {
                CliDropdownRow(
                    label = stringResource(R.string.cli_webapps_interval),
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
                label = stringResource(R.string.cli_webapps_dock),
                checked = settings.webApps.dockScreenEnabled,
                onToggle = viewModel::onWebAppsDockScreenChanged,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/**
 * The push service toggle. Enabling it with the firewall down goes through the terminal y/n form:
 * the firewall canon always asks, with no silent path.
 */
@Composable
private fun CliWebAppsPushRows(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val colors = LocalCliColors.current
    var consentOpen by remember { mutableStateOf(false) }
    CliToggleRow(
        label = stringResource(R.string.cli_webapps_push),
        checked = settings.webApps.pushServiceEnabled,
        note = stringResource(R.string.cli_webapps_push_note),
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
        CliElbowLine(
            text = stringResource(R.string.cli_webapps_firewall_consent),
            color = colors.warn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliChip(
                label = stringResource(R.string.cli_cfg_firewall_yes),
                color = colors.ok,
                onClick = {
                    consentOpen = false
                    viewModel.onWebAppsPushServiceChanged(true)
                },
            )
            CliChip(
                label = stringResource(R.string.cli_common_no_cancel),
                color = colors.err,
                onClick = { consentOpen = false },
            )
        }
    }
}

private fun pollIntervalLabel(minutes: Int): String = "${minutes}m"
