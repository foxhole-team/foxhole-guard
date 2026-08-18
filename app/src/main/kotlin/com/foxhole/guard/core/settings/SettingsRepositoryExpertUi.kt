package com.foxhole.guard.core.settings

import com.foxhole.core.model.AnomalyHistoryRetention
import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.DashboardCard
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsWidgetId
import com.foxhole.core.model.TrafficMapSectionId
import com.foxhole.core.model.normalizedStatisticsWidgetOrder
import com.foxhole.core.model.normalizedTrafficMapSectionOrder
import com.foxhole.guard.BuildConfig

// Expert flags, dashboard card toggles, diagnostics and anomaly settings. Extracted from
// SettingsRepository (class split by domain).

suspend fun SettingsRepository.unlockExpertSettings(timestamp: Long = System.currentTimeMillis()) =
    update {
        it.copy(
            ui = it.ui.copy(showExpertSettings = true),
            expert = it.expert.copy(unlockedAt = timestamp),
        )
    }

suspend fun SettingsRepository.acknowledgeUnsafeWarning(timestamp: Long = System.currentTimeMillis()) =
    update { it.copy(expert = it.expert.copy(warningAcknowledgedAt = timestamp)) }

suspend fun SettingsRepository.updateBlockScreenshots(value: Boolean) =
    update { it.copy(expert = it.expert.copy(blockScreenshots = value)) }

suspend fun SettingsRepository.updateTrafficMapEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(trafficMapEnabled = value)) }

suspend fun SettingsRepository.updateNetworkCardEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(networkCardEnabled = value)) }

suspend fun SettingsRepository.updateTrafficCardEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(trafficCardEnabled = value)) }

suspend fun SettingsRepository.updateNetworkDnsInfoEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(networkDnsInfoEnabled = value)) }

suspend fun SettingsRepository.updateShowQuickAccessPanel(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showQuickAccessPanel = value)) }

suspend fun SettingsRepository.updateShowTorQuickLaunch(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showTorQuickLaunch = value)) }

suspend fun SettingsRepository.updateShowI2pQuickLaunch(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showI2pQuickLaunch = value)) }

suspend fun SettingsRepository.updateShowTorDashboardControls(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showTorDashboardControls = value)) }

suspend fun SettingsRepository.updateShowFirewallStatus(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showFirewallStatus = value)) }

suspend fun SettingsRepository.updateShowLanProxyQuickAccess(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showLanProxyQuickAccess = value)) }

suspend fun SettingsRepository.updateShowTrafficModeSelector(
    @Suppress("UNUSED_PARAMETER") value: Boolean,
) =
    update { it.copy(ui = it.ui.copy(showTrafficModeSelector = false)) }

suspend fun SettingsRepository.updateProfileListOrder(value: List<Long>) =
    update { it.copy(ui = it.ui.copy(profileListOrder = value.distinct())) }

suspend fun SettingsRepository.updateDashboardCardOrder(value: List<DashboardCard>) =
    update { current ->
        val withStatus =
            if (DashboardCard.STATUS in value) value else listOf(DashboardCard.STATUS) + value
        val normalized =
            (withStatus + DashboardCard.entries)
                .distinct()
                .filter { card -> card in DashboardCard.entries }
        current.copy(ui = current.ui.copy(dashboardCardOrder = normalized))
    }

suspend fun SettingsRepository.updateStatisticsWidgetOrder(value: List<StatisticsWidgetId>) =
    update { current ->
        current.copy(ui = current.ui.copy(statisticsWidgetOrder = normalizedStatisticsWidgetOrder(value)))
    }

suspend fun SettingsRepository.updateTrafficMapSectionOrder(value: List<TrafficMapSectionId>) =
    update { current ->
        current.copy(ui = current.ui.copy(trafficMapSectionOrder = normalizedTrafficMapSectionOrder(value)))
    }

suspend fun SettingsRepository.updateTrafficMapHistoryClearedAt(value: Long) =
    update { current ->
        current.copy(
            ui = current.ui.copy(
                trafficMapHistoryClearedAtMs = maxOf(current.ui.trafficMapHistoryClearedAtMs, value),
            ),
        )
    }

suspend fun SettingsRepository.updateFirewallEnabled(value: Boolean) =
    update { current -> updateFirewallEnabledIn(current, value) }

internal fun updateFirewallEnabledIn(
    current: Settings,
    value: Boolean,
): Settings =
    current.copy(
        expert = current.expert.copy(
            // Pending quarantine is fail-closed: the firewall remains its enforcement host until
            // every app receives an explicit Allow or Block decision.
            firewallEnabled = value || current.expert.pendingQuarantinePackages.isNotEmpty(),
        ),
    )

suspend fun SettingsRepository.updateKillSwitchEnabled(value: Boolean) =
    update { it.copy(expert = it.expert.copy(killSwitchEnabled = value)) }

suspend fun SettingsRepository.updateNetworkActivityLogging(value: Boolean) =
    update { it.copy(expert = it.expert.copy(networkActivityLogging = value)) }

suspend fun SettingsRepository.updateDiagnosticsRetentionPolicy(value: RetentionPolicy) =
    update { it.copy(expert = it.expert.copy(diagnosticsRetentionPolicy = value)) }

suspend fun SettingsRepository.updateRawLiveDiagnostics(value: Boolean) =
    update { it.copy(expert = it.expert.copy(rawLiveDiagnostics = value && BuildConfig.DEBUG)) }

suspend fun SettingsRepository.updateNotifyUnusualTraffic(value: Boolean) =
    update { it.copy(anomaly = it.anomaly.copy(notifyUnusualTraffic = value)) }

suspend fun SettingsRepository.updateAnomalyEnabled(value: Boolean) =
    update { it.copy(anomaly = it.anomaly.copy(enabled = value)) }

suspend fun SettingsRepository.updateAnomalySensitivity(value: AnomalySensitivity) =
    update { it.copy(anomaly = it.anomaly.copy(sensitivity = value)) }

suspend fun SettingsRepository.updateAnalyzeBackgroundTraffic(value: Boolean) =
    update { it.copy(anomaly = it.anomaly.copy(analyzeBackgroundTraffic = value)) }

suspend fun SettingsRepository.updateAnalyzeDestinationCountries(value: Boolean) =
    update { it.copy(anomaly = it.anomaly.copy(analyzeDestinationCountries = value)) }

suspend fun SettingsRepository.updateAnomalyHistoryRetention(value: AnomalyHistoryRetention) =
    update { it.copy(anomaly = it.anomaly.copy(historyRetention = value)) }

suspend fun SettingsRepository.updateAnomalyExcludedPackages(value: List<String>) =
    update {
        it.copy(
            anomaly = it.anomaly.copy(
                excludedPackages = value.filterNot { pkg -> pkg == BuildConfig.APPLICATION_ID }.distinct(),
            ),
        )
    }

suspend fun SettingsRepository.updateAllowInsecureTls(value: Boolean) =
    update { it.copy(expert = it.expert.copy(allowInsecureTls = value)) }

suspend fun SettingsRepository.updateSniff(value: Boolean) =
    update { current ->
        current.copy(
            connection = current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !value),
            expert = current.expert.copy(sniff = value),
        )
    }

suspend fun SettingsRepository.updateStrictRoute(value: Boolean) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value),
            expert = it.expert.copy(strictRoute = value),
        )
    }

suspend fun SettingsRepository.updateBypassLan(value: Boolean) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
            expert = it.expert.copy(bypassLan = value),
        )
    }

suspend fun SettingsRepository.updateAllowPrivateOutboundHosts(value: Boolean) =
    update { current ->
        current.copy(
            connection = current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !value),
            expert = current.expert.copy(allowPrivateOutboundHosts = value),
        )
    }
