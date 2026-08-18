package com.foxhole.guard.ui.cli.settings

import androidx.lifecycle.viewModelScope
import com.foxhole.guard.core.settings.updateKillSwitchEnabled
import com.foxhole.guard.ui.HomeViewModel
import kotlinx.coroutines.launch

internal fun HomeViewModel.onKillSwitchEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateKillSwitchEnabled(value)
    }
}
