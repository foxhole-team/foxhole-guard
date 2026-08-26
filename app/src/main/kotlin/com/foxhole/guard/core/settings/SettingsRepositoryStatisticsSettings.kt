package com.foxhole.guard.core.settings

import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.StatisticsMetric
import com.foxhole.core.model.StatisticsRefreshInterval
import com.foxhole.core.model.StatisticsWindow

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

suspend fun SettingsRepository.updateStatisticsDockIconEnabled(value: Boolean) =
    update { current ->
        current.copy(ui = current.ui.copy(statisticsDockIconEnabled = value))
    }

suspend fun SettingsRepository.updateStatisticsComponentVisible(value: Boolean) =
    update { current ->
        current.copy(statistics = current.statistics.copy(componentVisible = value))
    }

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
