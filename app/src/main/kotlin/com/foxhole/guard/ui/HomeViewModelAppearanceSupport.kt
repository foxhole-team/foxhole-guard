package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.guard.core.settings.updateAccentColor
import com.foxhole.guard.core.settings.updateHomeAdditionalInfoCategory
import com.foxhole.guard.core.settings.updateMonochromeEnabled
import com.foxhole.guard.core.settings.updatePixelArtEnabled
import com.foxhole.guard.core.settings.updateShowHomeAdditionalInfo
import kotlinx.coroutines.launch

internal fun HomeViewModel.onAccentColorSelected(value: AccentColor) {
    viewModelScope.launch {
        container.settingsRepository.updateAccentColor(value)
    }
}

internal fun HomeViewModel.onPixelArtEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updatePixelArtEnabled(value)
    }
}

internal fun HomeViewModel.onShowHomeAdditionalInfoChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowHomeAdditionalInfo(value)
    }
}

internal fun HomeViewModel.onHomeAdditionalInfoCategorySelected(value: HomeAdditionalInfoCategory) {
    viewModelScope.launch {
        container.settingsRepository.updateHomeAdditionalInfoCategory(value)
    }
}

internal fun HomeViewModel.onMonochromeEnabledChanged(value: Boolean) {
    viewModelScope.launch { container.settingsRepository.updateMonochromeEnabled(value) }
}
