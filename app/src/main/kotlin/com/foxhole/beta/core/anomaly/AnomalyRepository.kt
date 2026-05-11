@file:Suppress("ImportOrdering")

package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.data.AnomalyDao
import com.foxhole.beta.core.data.AnomalyEventEntity
import com.foxhole.beta.core.data.AppTrafficWindowEntity
import com.foxhole.beta.core.data.TrafficWindowEntity
import com.foxhole.beta.core.data.anomalyHourBucket
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalySettings
import com.foxhole.beta.core.model.AnomalySignal
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AnomalyRepository(
    private val dao: AnomalyDao,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val notifier: AnomalyNotifier,
    private val engine: AnomalyEngine = AnomalyEngine(),
    private val baselineStore: BaselineStore = BaselineStore(dao),
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    val recentEvents: Flow<List<AnomalyEvent>> =
        settingsRepository.settings
            .flatMapLatest { settings ->
                val cutoff = nowProvider() - settings.anomaly.historyRetention.retentionHours * HOUR_MS
                dao.observeAnomalyEvents(cutoff).map { entities -> entities.map(AnomalyEventEntity::toDomain) }
            }

    val recentAppTrafficWindows: Flow<List<AppTrafficWindow>> =
        settingsRepository.settings
            .flatMapLatest { settings ->
                if (!settings.appTrafficStatsRuntimeEnabled()) {
                    flowOf(emptyList())
                } else {
                    dao.observeRecentAppTrafficWindows(cutoff = statisticsCutoff(settings.statistics.retention))
                        .map { entities -> entities.map(AppTrafficWindowEntity::toDomain) }
                }
            }

    val recentTrafficWindows: Flow<List<TrafficWindow>> =
        settingsRepository.settings
            .flatMapLatest { settings ->
                if (!settings.statistics.enabled || !settings.statistics.countryTrafficEnabled || !settings.expert.firewallEnabled) {
                    flowOf(emptyList())
                } else {
                    dao.observeRecentTrafficWindows(cutoff = statisticsCutoff(settings.statistics.retention))
                        .map { entities -> entities.map(TrafficWindowEntity::toDomain) }
                }
            }

    suspend fun recordTrafficWindow(
        window: TrafficWindow,
        appWindows: List<AppTrafficWindow> = emptyList(),
    ) {
        val settings = settingsRepository.current()
        val hourBucket = anomalyHourBucket(window.startedAtMs)
        val history =
            dao.recentTrafficWindows(
                profileId = window.profileId,
                protocol = window.protocol,
                networkType = window.networkType.name,
                hourBucket = hourBucket,
                limit = HISTORY_LIMIT,
            ).map(TrafficWindowEntity::toDomain)
        val appHistories =
            appWindows.associate { appWindow ->
                appWindow.packageName to
                    dao.recentAppTrafficWindows(
                        packageName = appWindow.packageName,
                        networkType = appWindow.networkType.name,
                        hourBucket = anomalyHourBucket(appWindow.startedAtMs),
                        limit = HISTORY_LIMIT,
                    ).map(AppTrafficWindowEntity::toDomain)
            }
        val assessment =
            engine.evaluate(
                current = window,
                appWindows = appWindows,
                history = AnomalyHistory(
                    trafficWindows = history,
                    appWindowsByPackage = appHistories,
                ),
                settings = settings.anomaly,
            )
        persistAssessment(
            assessment = assessment,
            window = window,
            settings = settings.anomaly,
        )
        dao.insertTrafficWindow(TrafficWindowEntity.from(window))
        if (appWindows.isNotEmpty()) {
            dao.insertAppTrafficWindows(appWindows.map(AppTrafficWindowEntity::from))
        }
        baselineStore.updateTrafficBaseline(window, historyBeforeCurrent = history)
        appWindows.forEach { appWindow ->
            baselineStore.updateAppBaseline(
                window = appWindow,
                profileId = window.profileId,
                protocol = window.protocol,
                historyBeforeCurrent = appHistories[appWindow.packageName].orEmpty(),
            )
        }
        cleanupExpired(settings.anomaly)
    }

    suspend fun recordAppTrafficWindows(windows: List<AppTrafficWindow>) {
        if (windows.isEmpty()) {
            return
        }
        dao.insertAppTrafficWindows(windows.map(AppTrafficWindowEntity::from))
        cleanupExpired(settingsRepository.current().anomaly)
    }

    suspend fun clearTrafficStatistics() {
        dao.deleteTrafficWindowsBefore(Long.MAX_VALUE)
        dao.deleteAppTrafficWindowsBefore(Long.MAX_VALUE)
        dao.deleteAnomalyEventsBefore(Long.MAX_VALUE)
    }

    private suspend fun persistAssessment(
        assessment: AnomalyAssessment,
        window: TrafficWindow,
        settings: AnomalySettings,
    ) {
        if (assessment.signals.isEmpty()) {
            return
        }
        diagnosticsLogger.record(
            tag = ANOMALY_LOG_TAG,
            message =
                buildString {
                    append("score=${assessment.score}")
                    append(" severity=${assessment.severity.name.lowercase(Locale.US)}")
                    append(" signals=")
                    append(assessment.signals.joinToString { it.type.name.lowercase(Locale.US) })
                },
        )
        if (!assessment.shouldLogActivity) {
            return
        }
        val strongest = assessment.signals.maxByOrNull(AnomalySignal::severity) ?: return
        val packageName = strongest.evidence["package"]
        val notificationShown =
            assessment.shouldNotify &&
                settings.notifyUnusualTraffic &&
                canNotify(strongest, packageName)
        val event =
            AnomalyEvent(
                createdAtMs = nowProvider(),
                type = strongest.type,
                severity = assessment.severity,
                score = assessment.score,
                reason = strongest.reason,
                evidence =
                    strongest.evidence +
                        mapOf(
                            "signal_count" to assessment.signals.size.toString(),
                            "signals" to assessment.signals.joinToString { it.type.name },
                        ),
                packageName = packageName,
                profileId = window.profileId,
                protocol = window.protocol,
                notificationShown = notificationShown,
            )
        val eventId = dao.insertAnomalyEvent(AnomalyEventEntity.from(event))
        val persisted = event.copy(id = eventId)
        diagnosticsLogger.recordStructured(
            tag = ANOMALY_LOG_TAG,
            headline = persisted.reason,
            "score=${persisted.score}",
            persisted.packageName?.let { "package=$it" },
            persisted.evidence["country"]?.let { "country=$it" },
        )
        if (notificationShown) {
            notifier.notify(persisted)
        }
    }

    private suspend fun canNotify(
        signal: AnomalySignal,
        packageName: String?,
    ): Boolean {
        val since = nowProvider() - NOTIFICATION_COOLDOWN_MS
        return dao.notificationCountSince(
            type = signal.type.name,
            packageName = packageName,
            since = since,
        ) == 0
    }

    private suspend fun cleanupExpired(settings: AnomalySettings) {
        val cutoff = nowProvider() - settings.historyRetention.retentionHours * HOUR_MS
        dao.deleteAnomalyEventsBefore(cutoff)
        dao.deleteTrafficWindowsBefore(cutoff)
        dao.deleteAppTrafficWindowsBefore(cutoff)
    }

    companion object {
        private const val HISTORY_LIMIT = 96
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val NOTIFICATION_COOLDOWN_MS = 30L * 60L * 1000L
        private const val ANOMALY_LOG_TAG = "anomaly"
    }
}

private fun statisticsCutoff(retention: StatisticsRetention): Long =
    when (retention) {
        StatisticsRetention.WEEK -> System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        StatisticsRetention.MONTH -> System.currentTimeMillis() - 31L * 24L * 60L * 60L * 1000L
        StatisticsRetention.MONTHS_3 -> System.currentTimeMillis() - 93L * 24L * 60L * 60L * 1000L
        StatisticsRetention.FOREVER -> 0L
    }

private fun Settings.appTrafficStatsRuntimeEnabled(): Boolean =
    statistics.enabled &&
        statistics.appTrafficEnabled &&
        appTrafficStatsEnabled
