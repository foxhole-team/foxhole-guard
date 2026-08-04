package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsMetric
import com.foxhole.core.model.StatisticsRefreshInterval
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.recordInstalledAppInventory
import com.foxhole.guard.core.settings.updateAppTrafficStatsEnabled
import com.foxhole.guard.core.settings.updateAppTrafficUsageAccessConsent
import com.foxhole.guard.core.settings.updateStatisticsComponentVisible
import com.foxhole.guard.core.settings.updateStatisticsEnabled
import com.foxhole.guard.core.settings.updateStatisticsMetricEnabled
import com.foxhole.guard.core.settings.updateStatisticsRefreshInterval
import com.foxhole.guard.core.settings.updateStatisticsRetentionPolicy
import com.foxhole.guard.core.settings.updateStatisticsSessionOnly
import com.foxhole.guard.core.settings.updateStatisticsWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Warms the statistics dashboard before the route lands: flips the visibility gate and briefly
 * subscribes the route state so the heavy aggregation runs during the navigation transition. The
 * built snapshot stays cached in the StateFlow, so the screen paints without the shimmer. The
 * route's own DisposableEffect remains the source of truth for turning visibility off.
 */
internal fun HomeViewModel.prewarmStatistics() {
    onStatisticsUiVisibilityChanged(true)
    viewModelScope.launch {
        withTimeoutOrNull(STATISTICS_PREWARM_TIMEOUT_MS) {
            statisticsRouteState.first { state -> state.statisticsDashboard.ready }
        }
    }
}

internal const val STATISTICS_PREWARM_TIMEOUT_MS = 3_000L

/**
 * Session-only statistics: collection keeps feeding the live screens,
 * but nothing survives past the connection session — every statistics store is wiped once on
 * cold start (covers force-kills mid-session) and again whenever an active session ends.
 */
internal fun HomeViewModel.startSessionOnlyStatisticsWipe() {
    viewModelScope.launch {
        // The wipe opens the statistics DB: hold until the PIN unlock installs the key.
        awaitDatabaseUnlocked()
        if (container.settingsRepository.settings.first().statistics.sessionOnly) {
            wipeSessionOnlyStatistics()
        }
        var wasActive = false
        container.connectionController.snapshot.collect { snapshot ->
            val active = snapshot.state in com.foxhole.core.model.ACTIVE_CONNECTION_STATES
            if (wasActive && !active &&
                container.settingsRepository.settings.value.statistics.sessionOnly
            ) {
                wipeSessionOnlyStatistics()
            }
            wasActive = active
        }
    }
}

// Returns a Result rather than swallowing: a background wipe has nothing to show, but the privacy
// toggle must admit when history stayed on disk.
private suspend fun HomeViewModel.wipeSessionOnlyStatistics(): Result<Unit> =
    runCatching {
        container.anomalyRepository.clearTrafficStatistics()
        container.localDataRepository.clearAppTrafficStats()
    }.map { }

internal fun HomeViewModel.onStatisticsSessionOnlyChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsSessionOnly(value)
        // Arming the mode mid-run cleans the already-persisted history right away, so the
        // switch honestly means "nothing outlives the session" from the moment it is flipped.
        if (value) {
            wipeSessionOnlyStatistics().onFailure {
                emitError(
                    getApplication<Application>().getString(R.string.privacy_local_data_clear_failed),
                )
            }
        }
    }
}

internal fun HomeViewModel.onStatisticsUiVisibilityChanged(visible: Boolean) {
    statisticsVisible = visible
    statisticsVisibleMutable.value = visible
    syncHighFrequencyTrafficVisibility()
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

internal fun HomeViewModel.onStatisticsEnabledChanged(value: Boolean) {
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

// Visibility only: reveals/hides the statistics screen + menu entry. Never starts collection (that
// stays the in-screen "Record statistics" toggle), so no sampler/inventory sync here.
internal fun HomeViewModel.onStatisticsComponentVisibleChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsComponentVisible(value)
    }
}

internal fun HomeViewModel.onStatisticsRetentionSelected(value: RetentionPolicy) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsRetentionPolicy(value)
    }
}

internal fun HomeViewModel.onStatisticsRefreshIntervalSelected(value: StatisticsRefreshInterval) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsRefreshInterval(value)
        syncAppTrafficStatsSampler(
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            ),
        )
    }
}

// The statistics screen's day/week dropdown. Persisted, never session-local: the user expects the
// picked window to survive restarts. Collection is untouched — the window only cuts what is shown.
internal fun HomeViewModel.onStatisticsWindowChanged(value: StatisticsWindow) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsWindow(value)
    }
}

internal fun HomeViewModel.onStatisticsMetricEnabledChanged(
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

internal fun HomeViewModel.onAppTrafficStatsEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        if (value) {
            container.settingsRepository.updateStatisticsEnabled(true)
            container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.APP_TRAFFIC, true)
        }
        // The recording toggle is only ever turned on once Usage Access is granted, so turning it
        // on is the user's consent; updateAppTrafficStatsEnabled forces appTrafficUsageAccessConsent
        // to match in the same transaction — otherwise the Apps card stays stuck on "enable app
        // traffic recording" because the collection/display gate also requires the consent flag.
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

internal fun HomeViewModel.onAppTrafficUsageAccessConsentChanged(value: Boolean) {
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
