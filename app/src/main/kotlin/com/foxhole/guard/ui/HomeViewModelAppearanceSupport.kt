package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.core.settings.updatePanelAppearance
import kotlinx.coroutines.launch

internal fun HomeViewModel.onPanelAppearanceSelected(value: PanelAppearance) {
    viewModelScope.launch {
        container.settingsRepository.updatePanelAppearance(value)
    }
}
