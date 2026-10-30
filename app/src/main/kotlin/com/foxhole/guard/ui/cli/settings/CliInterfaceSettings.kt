package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPanelEdgeToEdgeContentPadding
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onAutoReconnectChanged
import com.foxhole.guard.ui.onAutoStartChanged

@Composable
internal fun CliInterfaceModeRow(mode: InterfaceMode, onChanged: (InterfaceMode) -> Unit, showInfo: Boolean = true) {
    val user = stringResource(R.string.interface_mode_user)
    val expert = stringResource(R.string.interface_mode_expert)
    CliDropdownRow(
        label = stringResource(R.string.interface_mode_title),
        icon = R.drawable.lin_settings,
        value = if (mode == InterfaceMode.USER) user else expert,
        selectedId = mode.name,
        options = listOf(CliDropdownOption("USER", user), CliDropdownOption("EXPERT", expert)),
        onSelect = { value -> onChanged(InterfaceMode.valueOf(value)) },
        infoText = if (showInfo) stringResource(R.string.interface_mode_description) else null,
    )
}

@Composable
internal fun CliUserConnectionSection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_network),
        titleColor = colors.fg,
        icon = R.drawable.lin_globe,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        contentPadding = CliPanelEdgeToEdgeContentPadding,
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_auto_reconnect),
            icon = R.drawable.lin_restart,
            checked = settings.connection.autoReconnect,
            onToggle = viewModel::onAutoReconnectChanged,
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_auto_start),
            icon = R.drawable.lin_power,
            checked = settings.connection.autoStartOnBoot,
            onToggle = viewModel::onAutoStartChanged,
        )
    }
}
