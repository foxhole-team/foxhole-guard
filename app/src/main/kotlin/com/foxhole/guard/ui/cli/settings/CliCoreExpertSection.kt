package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.CoreTuningSettings
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.CoreTuningParameter
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPanelEdgeToEdgeContentPadding
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Composable
internal fun CliCoreExpertSection(
    tuning: CoreTuningSettings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChanged: (CoreTuningParameter, Int) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_core_expert_title),
        titleColor = colors.fg,
        icon = R.drawable.lin_settings,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        contentPadding = CliPanelEdgeToEdgeContentPadding,
    ) {
        Text(
            stringResource(R.string.cli_core_expert_note),
            style = CliType.small,
            color = colors.dim,
            modifier = Modifier.padding(horizontal = CliSpacing.md, vertical = CliSpacing.sm)
        )
        CoreTuningParameter.entries.forEach { parameter ->
            CliRowDivider()
            CliCoreTuningRow(parameter, parameter.value(tuning)) { onChanged(parameter, it) }
        }
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_core_reset),
            icon = R.drawable.lin_restart,
            enabled = tuning != CoreTuningSettings(),
            onTap = onReset
        )
    }
}

@Composable
private fun CliCoreTuningRow(parameter: CoreTuningParameter, value: Int, onChanged: (Int) -> Unit) {
    val colors = LocalCliColors.current
    val label = stringResource(parameter.labelRes())
    val default = parameter.value(CoreTuningSettings())
    val defaultLabel = stringResource(R.string.cli_core_default)
    val autoLabel = stringResource(R.string.cli_core_worker_auto)
    val supportsCustom = parameter != CoreTuningParameter.WORKERS
    val customOptions = if (supportsCustom) {
        listOf(CliDropdownOption(CLI_OPT_CUSTOM, stringResource(R.string.cli_common_custom)))
    } else {
        emptyList()
    }
    val options = parameter.presets.map { preset ->
        val defaultSuffix = if (preset == default) " · $defaultLabel" else ""
        CliDropdownOption(preset.toString(), parameter.format(preset, autoLabel) + defaultSuffix)
    } + customOptions
    var customOpen by rememberSaveable(parameter.name) { mutableStateOf(false) }
    var text by rememberSaveable(parameter.name) { mutableStateOf("") }
    var invalid by rememberSaveable(parameter.name) { mutableStateOf(false) }
    CliDropdownRow(
        label = label,
        icon = R.drawable.lin_settings,
        value = parameter.format(value, autoLabel),
        selectedId = if (value in parameter.presets || !supportsCustom) value.toString() else CLI_OPT_CUSTOM,
        options = options,
        onSelect = { id ->
            if (id == CLI_OPT_CUSTOM && supportsCustom) {
                text = value.toString()
                invalid = false
                customOpen = true
            } else {
                id.toIntOrNull()?.let(onChanged)
            }
        },
    )
    if (customOpen && supportsCustom) {
        CliInputModal(
            title = label,
            prompt = "${parameter.bounds.first}–${parameter.bounds.last}",
            value = text,
            onValueChange = {
                text = it.filter(Char::isDigit).take(6)
                invalid = false
            },
            onSubmit = {
                val parsed = text.toIntOrNull()
                if (parsed != null && parsed in parameter.bounds) {
                    onChanged(parsed)
                    customOpen = false
                } else {
                    invalid = true
                }
            },
            onDismiss = { customOpen = false },
            numeric = true,
            belowInput = {
                Text(
                    stringResource(R.string.cli_core_range, parameter.bounds.first, parameter.bounds.last),
                    style = CliType.small,
                    color = if (invalid) colors.err else colors.dim
                )
            },
        )
    }
}

private fun CoreTuningParameter.format(value: Int, autoLabel: String): String = when (this) {
    CoreTuningParameter.WORKERS -> if (value == CoreTuningSettings.AUTO_WORKER_THREADS) autoLabel else value.toString()
    CoreTuningParameter.CONNECT_TIMEOUT, CoreTuningParameter.HANDSHAKE_TIMEOUT, CoreTuningParameter.DNS_TIMEOUT -> "$value ms"
    CoreTuningParameter.RELAY_BUFFER -> "$value B"
    else -> value.toString()
}

private fun CoreTuningParameter.labelRes(): Int = when (this) {
    CoreTuningParameter.WORKERS -> R.string.cli_core_workers
    CoreTuningParameter.CONNECT_TIMEOUT -> R.string.cli_core_connect_timeout
    CoreTuningParameter.HANDSHAKE_TIMEOUT -> R.string.cli_core_handshake_timeout
    CoreTuningParameter.RELAY_BUFFER -> R.string.cli_core_relay_buffer
    CoreTuningParameter.TCP_FLOWS -> R.string.cli_core_tcp_flows
    CoreTuningParameter.UDP_FLOWS -> R.string.cli_core_udp_flows
    CoreTuningParameter.DNS_TIMEOUT -> R.string.cli_core_dns_timeout
    CoreTuningParameter.DNS_CACHE -> R.string.cli_core_dns_cache
}
