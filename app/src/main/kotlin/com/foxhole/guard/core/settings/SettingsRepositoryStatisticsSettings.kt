package com.foxhole.guard.core.settings

import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.StatisticsMetric
import com.foxhole.core.model.StatisticsRefreshInterval
import com.foxhole.core.model.StatisticsWindow

// Statistics collection toggles. Extracted from SettingsRepository (class split by domain).

suspend fun SettingsRepository.updateStatisticsEnabled(value: Boolean) =
    update { current ->
        current.copy(
            statistics =
            current.statistics.copy(
                enabled = value,
                profileTrafficEnabled = current.statistics.profileTrafficEnabled || value,
            ),
        )
    }

/** Show/hide the statistics feature (its screen + menu entry). Visibility only — never touches
 * collection, which is opted into inside the statistics screen. */
suspend fun SettingsRepository.updateStatisticsComponentVisible(value: Boolean) =
    update { current ->
        current.copy(statistics = current.statistics.copy(componentVisible = value))
    }

// Session-only statistics: stores are wiped on session end and cold start.
suspend fun SettingsRepository.updateStatisticsSessionOnly(value: Boolean) =
    update { current ->
        current.copy(
            statistics = current.statistics.copy(sessionOnly = value),
        )
    }

suspend fun SettingsRepository.updateStatisticsRetentionPolicy(value: RetentionPolicy) =
    update { current ->
        current.copy(
            statistics = current.statistics.copy(retentionPolicy = value),
        )
    }

suspend fun SettingsRepository.updateStatisticsRefreshInterval(value: StatisticsRefreshInterval) =
    update { current ->
        current.copy(
            statistics = current.statistics.copy(refreshInterval = value),
        )
    }

/**
 * The statistics screen's day/week dropdown, remembered forever.
 *
 * No safe-mode clause is needed here: Settings.normalized() rebuilds `ui` with a named copy() that
 * only overrides themeMode/onboardingCompleted/showExpertSettings/supportBotHandleOverride/
 * trafficMapEnabled, and updateSafeModeEnabled never rewrites UiSettings — unlike traffic/
 * privacyRoute/expert, which safe mode replaces wholesale. The fast UI store mirrors a whitelist of
 * dashboard flags only, so this value always comes back from the encrypted payload.
 */
suspend fun SettingsRepository.updateStatisticsWindow(value: StatisticsWindow) =
    update { it.copy(ui = it.ui.copy(statisticsWindow = value)) }

suspend fun SettingsRepository.updateStatisticsMetricEnabled(
    metric: StatisticsMetric,
    value: Boolean,
) = update { current ->
    val statistics =
        when (metric) {
            StatisticsMetric.PROFILE_TRAFFIC -> current.statistics.copy(profileTrafficEnabled = value)
            StatisticsMetric.APP_TRAFFIC -> current.statistics.copy(appTrafficEnabled = value)
            StatisticsMetric.DNS_FILTERING -> current.statistics.copy(dnsFilteringEnabled = value)
            StatisticsMetric.COUNTRY_TRAFFIC -> current.statistics.copy(countryTrafficEnabled = value)
            StatisticsMetric.ANOMALIES -> current.statistics.copy(anomalyMetricsEnabled = value)
            StatisticsMetric.APP_CHANGES -> current.statistics.copy(appChangesEnabled = value)
        }
    current.copy(statistics = statistics)
}
