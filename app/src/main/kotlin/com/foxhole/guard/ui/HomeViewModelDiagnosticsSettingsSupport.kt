package com.foxhole.guard.ui
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.AnomalyHistoryRetention
import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.guard.core.settings.updateAllowInsecureTls
import com.foxhole.guard.core.settings.updateAllowPrivateOutboundHosts
import com.foxhole.guard.core.settings.updateAnalyzeBackgroundTraffic
import com.foxhole.guard.core.settings.updateAnalyzeDestinationCountries
import com.foxhole.guard.core.settings.updateAnomalyEnabled
import com.foxhole.guard.core.settings.updateAnomalyExcludedPackages
import com.foxhole.guard.core.settings.updateAnomalyHistoryRetention
import com.foxhole.guard.core.settings.updateAnomalySensitivity
import com.foxhole.guard.core.settings.updateBypassLan
import com.foxhole.guard.core.settings.updateDiagnosticsRetentionPolicy
import com.foxhole.guard.core.settings.updateNotifyUnusualTraffic
import com.foxhole.guard.core.settings.updateRawLiveDiagnostics
import com.foxhole.guard.core.settings.updateSniff
import com.foxhole.guard.core.settings.updateStrictRoute
import kotlinx.coroutines.launch

// Diagnostics, anomaly-detection and expert flag settings handlers for HomeViewModel.
// Extracted from HomeViewModelSettingsSupport (file split by domain); extension functions only.

internal fun HomeViewModel.onDiagnosticsRetentionSelected(value: RetentionPolicy) {
    viewModelScope.launch {
        container.settingsRepository.updateDiagnosticsRetentionPolicy(value)
    }
}

internal fun HomeViewModel.onRawLiveDiagnosticsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateRawLiveDiagnostics(value)
        container.diagnosticsLogger.applyLiveDiagnosticsPrivacySetting()
    }
}

internal fun HomeViewModel.onNotifyUnusualTrafficChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNotifyUnusualTraffic(value)
    }
}

internal fun HomeViewModel.onAnomalyEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalyEnabled(value)
    }
}

internal fun HomeViewModel.onAnomalySensitivitySelected(value: AnomalySensitivity) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalySensitivity(value)
    }
}

internal fun HomeViewModel.onAnalyzeBackgroundTrafficChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAnalyzeBackgroundTraffic(value)
    }
}

internal fun HomeViewModel.onAnalyzeDestinationCountriesChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAnalyzeDestinationCountries(value)
    }
}

internal fun HomeViewModel.onAnomalyHistoryRetentionSelected(value: AnomalyHistoryRetention) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalyHistoryRetention(value)
    }
}

internal fun HomeViewModel.onAnomalyExcludedPackagesChanged(value: List<String>) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalyExcludedPackages(value)
    }
}

internal fun HomeViewModel.onAllowInsecureTlsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAllowInsecureTls(value)
    }
}

internal fun HomeViewModel.onSniffChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSniff(value)
    }
}

internal fun HomeViewModel.onStrictRouteChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateStrictRoute(value)
    }
}

internal fun HomeViewModel.onBypassLanChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateBypassLan(value)
    }
}

internal fun HomeViewModel.onAllowPrivateOutboundHostsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAllowPrivateOutboundHosts(value)
    }
}
