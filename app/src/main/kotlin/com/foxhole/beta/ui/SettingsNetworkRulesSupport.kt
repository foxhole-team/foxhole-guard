package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.foxhole.beta.R
import com.foxhole.beta.core.model.NetworkRulesSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint

@Composable
internal fun NetworkRulesWifiProfileRows(
    networkRules: NetworkRulesSettings,
    profiles: List<Profile>,
    activeProfileId: Long?,
    onNetworkRulesChanged: (NetworkRulesSettings) -> Unit,
) {
    val wifiProfileIds = listOf<Long?>(null) + profiles.map(Profile::id)
    val selectedWifiProfile = profiles.firstOrNull { profile -> profile.id == networkRules.wifiProfileId }
    val defaultWifiProfileId =
        remember(profiles, activeProfileId, networkRules.wifiProfileId) {
            networkRules.wifiProfileId
                ?: profiles.firstOrNull { profile -> profile.id != activeProfileId }?.id
                ?: profiles.firstOrNull()?.id
        }
    val protocolOptions = selectedWifiProfile?.networkRuleProtocolOptions().orEmpty()
    val selectedProtocolId = selectedWifiProfile?.defaultNetworkRuleProtocolOptionId(networkRules.wifiProtocolOptionId)
    var wifiProfileMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var wifiProtocolMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val wifiProfileValueUnselected = stringResource(R.string.network_rules_wifi_profile_value_unselected)
    val wifiProtocolValueUnselected = stringResource(R.string.network_rules_wifi_protocol_value_unselected)
    val selectedProtocolName =
        protocolOptions.protocolNameOrDefault(
            optionId = selectedProtocolId,
            fallback = wifiProtocolValueUnselected,
        )

    SettingSwitchRow(
        title = stringResource(R.string.network_rules_wifi_profile_title),
        checked = networkRules.useWifiProfile,
        enabled = profiles.isNotEmpty(),
        summary = if (profiles.isEmpty()) {
            stringResource(R.string.network_rules_wifi_profile_summary_empty)
        } else {
            stringResource(R.string.network_rules_wifi_profile_summary)
        },
        leadingIcon = Icons.Outlined.Router,
        onCheckedChange = { enabled ->
            val profileId = if (enabled) {
                networkRules.wifiProfileId ?: defaultWifiProfileId
            } else {
                networkRules.wifiProfileId
            }
            val profile = profiles.firstOrNull { it.id == profileId }
            val protocolOptionId = profile?.defaultNetworkRuleProtocolOptionId(networkRules.wifiProtocolOptionId)
            onNetworkRulesChanged(
                networkRules.copy(
                    useWifiProfile = enabled,
                    wifiProfileId = profileId,
                    wifiProtocolOptionId = protocolOptionId,
                ),
            )
        },
        titleMaxLines = 2,
        summaryMaxLines = 4,
        grouped = true,
    )
    SettingsControlGroupDivider()
    DropdownSettingRow(
        title = stringResource(R.string.network_rules_wifi_profile_picker_title),
        value = selectedWifiProfile?.name ?: wifiProfileValueUnselected,
        expanded = wifiProfileMenuExpanded,
        onExpandedChange = { wifiProfileMenuExpanded = it },
        values = wifiProfileIds,
        selected = networkRules.wifiProfileId,
        label = { profileId ->
            profiles.profileNameOrDefault(
                profileId = profileId,
                fallback = wifiProfileValueUnselected,
            )
        },
        onSelect = { profileId ->
            val profile = profiles.firstOrNull { it.id == profileId }
            val protocolOptionId = profile?.defaultNetworkRuleProtocolOptionId(networkRules.wifiProtocolOptionId)
            onNetworkRulesChanged(
                networkRules.copy(
                    wifiProfileId = profileId,
                    wifiProtocolOptionId = protocolOptionId,
                ),
            )
        },
        leadingIcon = Icons.Outlined.Router,
        optionIcon = { Icons.Outlined.VpnKey },
        enabled = networkRules.useWifiProfile && profiles.isNotEmpty(),
        grouped = true,
    )
    if (networkRules.useWifiProfile && protocolOptions.size > 1) {
        SettingsControlGroupDivider()
        DropdownSettingRow(
            title = stringResource(R.string.network_rules_wifi_protocol_picker_title),
            value = selectedProtocolName,
            expanded = wifiProtocolMenuExpanded,
            onExpandedChange = { wifiProtocolMenuExpanded = it },
            values = protocolOptions.map(ProfileProtocolOption::id),
            selected = selectedProtocolId ?: protocolOptions.first().id,
            label = { optionId ->
                protocolOptions.protocolNameOrDefault(
                    optionId = optionId,
                    fallback = wifiProtocolValueUnselected,
                )
            },
            onSelect = { optionId ->
                onNetworkRulesChanged(networkRules.copy(wifiProtocolOptionId = optionId))
            },
            leadingIcon = Icons.Outlined.VpnKey,
            optionIcon = { Icons.Outlined.VpnKey },
            enabled = networkRules.useWifiProfile,
            grouped = true,
        )
    }
}

internal fun List<Profile>.profileNameOrDefault(
    profileId: Long?,
    fallback: String,
): String =
    firstOrNull { profile -> profile.id == profileId }?.name ?: fallback

private fun Profile.networkRuleProtocolOptions(): List<ProfileProtocolOption> =
    protocolOptions
        .filter { option -> option.id.isNotBlank() }
        .distinctBy(ProfileProtocolOption::id)

private fun Profile.defaultNetworkRuleProtocolOptionId(currentOptionId: String?): String? {
    val options = networkRuleProtocolOptions()
    return currentOptionId
        ?.takeIf { optionId -> options.any { option -> option.id == optionId } }
        ?: selectedProtocolOptionId
            ?.takeIf { optionId -> options.any { option -> option.id == optionId } }
        ?: options.firstOrNull(ProfileProtocolOption::isSelected)?.id
        ?: options.firstOrNull()?.id
}

private fun List<ProfileProtocolOption>.protocolNameOrDefault(
    optionId: String?,
    fallback: String,
): String =
    firstOrNull { option -> option.id == optionId }?.networkRuleProtocolName() ?: fallback

private fun ProfileProtocolOption.networkRuleProtocolName(): String =
    displayName.ifBlank {
        when (protocolHint) {
            ProtocolHint.HYSTERIA2 -> "Hysteria2"
            ProtocolHint.SING_BOX -> "Sing-box"
            ProtocolHint.UNKNOWN -> "Unknown"
            else -> protocolHint.name
        }
    }
