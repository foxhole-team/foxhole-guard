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
    val networkRules = state.settings.networkRules
    val profileId = networkRules.cellularProfileId
    val shouldUseCellularProfile =
        networkRules.useCellularProfile &&
            profileId != null &&
            container.networkFingerprintProvider.currentFingerprint()?.isCellularOrMetered() == true
    return if (shouldUseCellularProfile) {
        state.profiles.firstOrNull { profile -> profile.id == profileId }
    } else {
        null
    }
}
