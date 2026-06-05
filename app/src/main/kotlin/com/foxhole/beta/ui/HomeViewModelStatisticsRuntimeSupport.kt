package com.foxhole.beta.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.onStatisticsUiVisibilityChangedInternal(visible: Boolean) {
    statisticsVisible = visible
    statisticsVisibleMutable.value = visible
    onTrafficUiVisibilityChangedInternal(dashboardVisible || statisticsVisible)
    val runtimeAllowed =
        appTrafficStatsRuntimeAllowed(
            settings = container.settingsRepository.settings.value,
        )
    syncAppTrafficStatsSampler(runtimeAllowed)
    if (visible && runtimeAllowed) {
        viewModelScope.launch {
            loadInstalledApps()
        }
    }
    if (visible && container.settingsRepository.settings.value.statistics.appChangesEnabled) {
        viewModelScope.launch {
            recordInstalledAppInventoryFromLoadedApps()
        }
    }
}

internal fun HomeViewModel.onStatisticsEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsEnabled(value)
        val runtimeAllowed =
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            )
        syncAppTrafficStatsSampler(runtimeAllowed)
        if (value && runtimeAllowed) {
            loadInstalledApps()
        }
        if (value && container.settingsRepository.settings.value.statistics.appChangesEnabled) {
            recordInstalledAppInventoryFromLoadedApps()
        }
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.onStatisticsRetentionSelectedInternal(value: StatisticsRetention) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsRetention(value)
    }
}

internal fun HomeViewModel.onStatisticsRefreshIntervalSelectedInternal(value: StatisticsRefreshInterval) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsRefreshInterval(value)
        syncAppTrafficStatsSampler(
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            ),
        )
    }
}

internal fun HomeViewModel.onStatisticsMetricEnabledChangedInternal(
    metric: StatisticsMetric,
    value: Boolean,
) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsMetricEnabled(metric, value)
        val runtimeAllowed =
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            )
        syncAppTrafficStatsSampler(runtimeAllowed)
        if (value && metric == StatisticsMetric.APP_TRAFFIC && runtimeAllowed) {
            loadInstalledApps()
        }
        if (value && metric == StatisticsMetric.APP_CHANGES) {
            recordInstalledAppInventoryFromLoadedApps()
        }
        if (metric == StatisticsMetric.COUNTRY_TRAFFIC) {
            syncLocalGuardWithPermissionRequest()
        }
    }
}

internal fun HomeViewModel.onAppTrafficStatsEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        if (value) {
            container.settingsRepository.updateStatisticsEnabled(true)
            container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.APP_TRAFFIC, true)
        }
        container.settingsRepository.updateAppTrafficStatsEnabled(value)
        if (!value) {
            container.anomalyRepository.clearAppTrafficPrivacyData()
        }
        container.connectionController.syncLocalGuard()
        val runtimeAllowed =
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            )
        syncAppTrafficStatsSampler(runtimeAllowed)
        if (value && runtimeAllowed) {
            loadInstalledApps()
        }
    }
}

internal fun HomeViewModel.onAppTrafficUsageAccessConsentChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAppTrafficUsageAccessConsent(value)
        if (!value) {
            container.anomalyRepository.clearAppTrafficPrivacyData()
            syncAppTrafficStatsSampler(false)
        }
    }
}

internal fun HomeViewModel.syncAppTrafficStatsSampler(enabled: Boolean) {
    if (!enabled) {
        appTrafficStatsJob?.cancel()
        appTrafficStatsJob = null
        appTrafficStatsIntervalMs = HomeViewModel.APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS
        return
    }
    val targetIntervalMs = appTrafficStatsSampleIntervalMs()
    if (appTrafficStatsJob != null && appTrafficStatsIntervalMs != targetIntervalMs) {
        appTrafficStatsJob?.cancel()
        appTrafficStatsJob = null
    }
    if (appTrafficStatsJob != null) {
        return
    }
    appTrafficStatsIntervalMs = targetIntervalMs
    appTrafficStatsJob =
        viewModelScope.launch {
            while (true) {
                sampleAppTrafficStats()
                delay(appTrafficStatsIntervalMs)
            }
        }
}

private fun HomeViewModel.appTrafficStatsSampleIntervalMs(): Long =
    if (statisticsVisible) {
        container.settingsRepository.settings.value.statistics.refreshInterval.seconds * 1_000L
    } else {
        HomeViewModel.APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS
    }

internal suspend fun HomeViewModel.sampleAppTrafficStats() {
    appTrafficStatsRecorder.recordSnapshot(minDurationMs = appTrafficStatsIntervalMs)
}

private suspend fun HomeViewModel.recordInstalledAppInventoryFromLoadedApps() {
    val apps = installedAppsMutable.value
    if (apps.isEmpty()) {
        loadInstalledApps()
    } else {
        container.settingsRepository.recordInstalledAppInventory(apps)
    }
}

internal fun HomeViewModel.appTrafficStatsRuntimeAllowed(
    settings: Settings,
): Boolean =
    appTrafficStatsRuntimeAllowed(
        settings = settings,
        usageAccessGranted = appTrafficUsageAccessGrantedMutable.value,
    )

internal fun appTrafficStatsRuntimeAllowed(
    settings: Settings,
    usageAccessGranted: Boolean,
): Boolean =
    settings.statistics.enabled &&
        settings.statistics.appTrafficEnabled &&
        settings.appTrafficStatsEnabled &&
        settings.appTrafficUsageAccessConsent &&
        usageAccessGranted
