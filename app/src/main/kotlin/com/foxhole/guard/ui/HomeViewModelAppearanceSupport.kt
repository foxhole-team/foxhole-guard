package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.core.settings.updateAccentColor
import com.foxhole.guard.core.settings.updatePanelAppearance
import com.foxhole.guard.core.settings.updateVisualStyle
import kotlinx.coroutines.launch

internal fun HomeViewModel.onPanelAppearanceSelected(value: PanelAppearance) {
    viewModelScope.launch {
        container.settingsRepository.updatePanelAppearance(value)
    }
}

internal fun HomeViewModel.onVisualStyleSelected(value: VisualStyle) {
    viewModelScope.launch {
        container.settingsRepository.updateVisualStyle(value)
    }
}

internal fun HomeViewModel.onAccentColorSelected(value: AccentColor) {
    viewModelScope.launch {
        container.settingsRepository.updateAccentColor(value)
    }
}

internal fun HomeViewModel.onRetroThemeRestored(restored: Boolean) {
    viewModelScope.launch {
        if (restored) {
            container.settingsRepository.updateVisualStyle(VisualStyle.PIXEL)
            return@launch
        }
        container.settingsRepository.updateVisualStyle(VisualStyle.PLAIN)
        container.settingsRepository.updatePanelAppearance(PanelAppearance.AUTO)
        onLocaleSelected(AppLocale.SYSTEM)
    }
}
