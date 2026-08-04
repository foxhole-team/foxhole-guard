package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.guard.core.settings.updateWidgetAlphaPercent
import com.foxhole.guard.core.settings.updateWidgetBlackBackground
import kotlinx.coroutines.launch

// Home widget defaults from app settings; the widgets pick up changes through the settings
// collector in FoxholeApplication.

internal fun HomeViewModel.onWidgetBlackBackgroundChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateWidgetBlackBackground(value)
    }
}

internal fun HomeViewModel.onWidgetAlphaPercentChanged(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateWidgetAlphaPercent(value)
    }
}
