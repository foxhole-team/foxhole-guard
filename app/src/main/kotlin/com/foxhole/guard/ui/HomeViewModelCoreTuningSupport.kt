package com.foxhole.guard.ui

import com.foxhole.guard.core.settings.CoreTuningParameter
import com.foxhole.guard.core.settings.resetCoreTuning
import com.foxhole.guard.core.settings.updateCoreTuning

internal fun HomeViewModel.onCoreTuningChanged(parameter: CoreTuningParameter, value: Int) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateCoreTuning(parameter, value)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onResetCoreTuning() {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.resetCoreTuning()
        container.connectionController.syncLocalGuard()
    }
}
