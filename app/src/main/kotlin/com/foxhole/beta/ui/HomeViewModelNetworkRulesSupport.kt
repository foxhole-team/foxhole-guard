package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.network.isCellularOrMetered
import com.foxhole.beta.core.network.scopedByNetworkRules

internal fun HomeViewModel.currentNetworkFingerprintForSmartRules(): NetworkFingerprint? {
    val fingerprint = container.networkFingerprintProvider.currentFingerprint() ?: return null
    return fingerprint.scopedByNetworkRules(uiState.value.settings.networkRules)
}

internal fun HomeViewModel.shouldSkipSpeedTestsOnCurrentNetwork(): Boolean {
    val networkRules = uiState.value.settings.networkRules
    if (!networkRules.skipSpeedTestsOnCellular) {
        return false
    }
    return container.networkFingerprintProvider.currentFingerprint()?.isCellularOrMetered() == true
}

internal fun HomeViewModel.mobileNetworkProfileOverride(state: HomeUiState = uiState.value): Profile? {
    val fingerprint = container.networkFingerprintProvider.currentFingerprint() ?: return null
    val override = currentNetworkProfileOverride(state)
    return if (fingerprint.isCellularOrMetered()) {
        override?.profile
    } else {
        null
    }
}

internal fun HomeViewModel.currentNetworkProfileOverride(state: HomeUiState = uiState.value): NetworkProfileOverride? {
    val networkRules = state.settings.networkRules
    val fingerprint = container.networkFingerprintProvider.currentFingerprint() ?: return null
    return when {
        fingerprint.transport == "wifi" &&
            networkRules.wifiRulesEnabled &&
            networkRules.useWifiProfile ->
            state.profiles.firstOrNull { profile -> profile.id == networkRules.wifiProfileId }?.let { profile ->
                NetworkProfileOverride(
                    profile = profile,
                    protocolOptionId = profile.validNetworkRuleProtocolOption(networkRules.wifiProtocolOptionId),
                )
            }
        fingerprint.isCellularOrMetered() &&
            networkRules.cellularRulesEnabled &&
            networkRules.useCellularProfile ->
            state.profiles.firstOrNull { profile -> profile.id == networkRules.cellularProfileId }?.let { profile ->
                NetworkProfileOverride(profile = profile)
            }
        else -> null
    }
}

private fun Profile.validNetworkRuleProtocolOption(optionId: String?): String? =
    optionId
        ?.takeIf(String::isNotBlank)
        ?.takeIf { requestedId -> protocolOptions.any { option -> option.id == requestedId } }
