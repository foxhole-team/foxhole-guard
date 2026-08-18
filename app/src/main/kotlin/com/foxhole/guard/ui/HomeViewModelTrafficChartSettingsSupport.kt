package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.TrafficCardView
import com.foxhole.core.model.TrafficChartPage
import com.foxhole.guard.core.settings.updateTrafficCardView
import com.foxhole.guard.core.settings.updateTrafficChartCombined
import com.foxhole.guard.core.settings.updateTrafficChartPage
import com.foxhole.guard.core.settings.updateTrafficChartRangeMinutes
import kotlinx.coroutines.launch

internal fun HomeViewModel.onTrafficCardViewChanged(value: TrafficCardView) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficCardView(value)
    }
}

internal fun HomeViewModel.onTrafficChartCombinedChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficChartCombined(value)
    }
}

internal fun HomeViewModel.onTrafficChartPageChanged(value: TrafficChartPage) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficChartPage(value)
    }
}

internal fun HomeViewModel.onTrafficChartRangeMinutesChanged(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficChartRangeMinutes(value)
    }
}
