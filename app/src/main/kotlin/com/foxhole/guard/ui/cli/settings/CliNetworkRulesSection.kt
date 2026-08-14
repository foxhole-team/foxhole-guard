package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.NetworkRulesSettings
import com.foxhole.core.model.Profile
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.SettingsRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliDivider
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onNetworkRulesChanged
import com.foxhole.guard.ui.resolveNetworkRuleProtocolOptionId

/**
 * Network rules, rendered as a sub-block of the network group (no panel of its own): the
 * model is a flat wifi/cellular pair (no rule list), each block is enable + auto-connect +
 * profile override (+ protocol pin for multi-protocol profiles); cellular adds the data-saver pair
 * written as one toggle. Single write path: onNetworkRulesChanged.
 */
@Composable
internal fun CliNetworkRulesRows(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
) {
    val rules = state.settings.networkRules
    TransportRuleBlock(
        binding = wifiBinding(rules),
        profiles = state.profiles,
        onUpdate = viewModel::onNetworkRulesChanged,
    )
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    CliDivider()
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    TransportRuleBlock(
        binding = cellularBinding(rules),
        profiles = state.profiles,
        onUpdate = viewModel::onNetworkRulesChanged,
    )
    if (rules.cellularRulesEnabled) {
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_nr_data_saver),
            icon = R.drawable.pix_stats,
            checked = rules.skipSpeedTestsOnCellular && rules.skipSubscriptionRefreshOnCellular,
            onToggle = {
                viewModel.onNetworkRulesChanged(
                    rules.copy(
                        skipSpeedTestsOnCellular = it,
                        skipSubscriptionRefreshOnCellular = it,
                    ),
                )
            },
            note = stringResource(R.string.cli_cfg_nr_data_saver_note),
        )
    }
}

/** One transport's fields plus copy-writers, so wifi and cellular share the block UI. */
private class TransportRuleBinding(
    val labelRes: Int,
    val iconRes: Int,
    val enabled: Boolean,
    val autoConnect: Boolean,
    val profileId: Long?,
    val protocolOptionId: String?,
    val setEnabled: (Boolean) -> NetworkRulesSettings,
    val setAutoConnect: (Boolean) -> NetworkRulesSettings,
    val setProfile: (Profile?) -> NetworkRulesSettings,
    val setProtocol: (String?) -> NetworkRulesSettings,
)

private fun wifiBinding(rules: NetworkRulesSettings) = TransportRuleBinding(
    labelRes = R.string.cli_cfg_nr_wifi,
    iconRes = R.drawable.pix_link,
    enabled = rules.wifiRulesEnabled,
    autoConnect = rules.wifiAutoConnect,
    profileId = rules.wifiProfileId.takeIf { rules.useWifiProfile },
    protocolOptionId = rules.wifiProtocolOptionId,
    setEnabled = { rules.copy(wifiRulesEnabled = it) },
    setAutoConnect = { rules.copy(wifiAutoConnect = it) },
    setProfile = { profile ->
        rules.copy(
            useWifiProfile = profile != null,
            wifiProfileId = profile?.id,
            wifiProtocolOptionId = profile?.resolveNetworkRuleProtocolOptionId(profile.selectedProtocolOptionId),
        )
    },
    setProtocol = { rules.copy(wifiProtocolOptionId = it) },
)

private fun cellularBinding(rules: NetworkRulesSettings) = TransportRuleBinding(
    labelRes = R.string.cli_cfg_nr_cellular,
    iconRes = R.drawable.pix_device,
    enabled = rules.cellularRulesEnabled,
    autoConnect = rules.cellularAutoConnect,
    profileId = rules.cellularProfileId.takeIf { rules.useCellularProfile },
    protocolOptionId = rules.cellularProtocolOptionId,
    setEnabled = { rules.copy(cellularRulesEnabled = it) },
    setAutoConnect = { rules.copy(cellularAutoConnect = it) },
    setProfile = { profile ->
        rules.copy(
            useCellularProfile = profile != null,
            cellularProfileId = profile?.id,
            cellularProtocolOptionId = profile?.resolveNetworkRuleProtocolOptionId(profile.selectedProtocolOptionId),
        )
    },
    setProtocol = { rules.copy(cellularProtocolOptionId = it) },
)

@Composable
private fun TransportRuleBlock(
    binding: TransportRuleBinding,
    profiles: List<Profile>,
    onUpdate: (NetworkRulesSettings) -> Unit,
) {
    CliToggleRow(
        label = stringResource(binding.labelRes),
        icon = binding.iconRes,
        checked = binding.enabled,
        onToggle = { onUpdate(binding.setEnabled(it)) },
    )
    if (!binding.enabled) {
        return
    }
    CliRowDivider()
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_nr_auto_connect),
        icon = R.drawable.pix_power,
        checked = binding.autoConnect,
        onToggle = { onUpdate(binding.setAutoConnect(it)) },
        note = if (binding.autoConnect) {
            null
        } else {
            stringResource(R.string.cli_cfg_nr_recommend_note)
        },
    )
    CliRowDivider()
    val selectedProfile = profiles.firstOrNull { it.id == binding.profileId }
    CliDropdownRow(
        label = stringResource(R.string.cli_cfg_nr_profile),
        icon = R.drawable.pix_profiles,
        value = selectedProfile?.name ?: stringResource(R.string.cli_home_profile_none),
        options = listOf(
            CliDropdownOption(id = OPTION_NONE, label = stringResource(R.string.cli_home_profile_none)),
        ) + profiles.map { profile ->
            CliDropdownOption(id = profile.id.toString(), label = profile.name)
        },
        selectedId = selectedProfile?.id?.toString() ?: OPTION_NONE,
        onSelect = { id ->
            if (id == OPTION_NONE) {
                onUpdate(binding.setProfile(null))
            } else {
                profiles.firstOrNull { it.id.toString() == id }
                    ?.let { onUpdate(binding.setProfile(it)) }
            }
        },
    )
    val smartOptions = selectedProfile?.protocolOptions.orEmpty()
    if (smartOptions.size > 1) {
        CliRowDivider()
        val selectedOption =
            smartOptions.firstOrNull { it.id == binding.protocolOptionId }
                ?: smartOptions.firstOrNull { it.id == selectedProfile?.selectedProtocolOptionId }
                ?: smartOptions.first()
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_nr_protocol),
            icon = R.drawable.pix_shield,
            value = selectedOption.displayName,
            options = smartOptions.map { option ->
                CliDropdownOption(id = option.id, label = option.displayName)
            },
            selectedId = selectedOption.id,
            onSelect = { id -> onUpdate(binding.setProtocol(id)) },
        )
    }
}

private const val OPTION_NONE = "none"
