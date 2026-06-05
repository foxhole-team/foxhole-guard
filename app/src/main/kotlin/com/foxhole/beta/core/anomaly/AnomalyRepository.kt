@file:Suppress("ImportOrdering")

package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.data.AnomalyDao
import com.foxhole.beta.core.data.AnomalyEventEntity
import com.foxhole.beta.core.data.AppTrafficWindowEntity
import com.foxhole.beta.core.data.NetworkActivityEventEntity
import com.foxhole.beta.core.data.TrafficWindowEntity
import com.foxhole.beta.core.data.anomalyHourBucket
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalySettings
import com.foxhole.beta.core.model.AnomalySignal
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AnomalyRepository(
    daoProvider: () -> AnomalyDao,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val notifier: AnomalyNotifier,
    private val engine: AnomalyEngine = AnomalyEngine(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED, daoProvider)
    private val baselineStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BaselineStore(dao) }
    private var lastNetworkActivityCleanupAt = 0L

    val recentEvents: Flow<List<AnomalyEvent>> =
        flow {
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        val cutoff = nowProvider() - settings.anomaly.historyRetention.retentionHours * HOUR_MS
                        dao.observeAnomalyEvents(cutoff).map { entities -> entities.map(AnomalyEventEntity::toDomain) }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentAppTrafficWindows: Flow<List<AppTrafficWindow>> =
        flow {
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!appTrafficLocalStorageAllowed(settings)) {
                            flowOf(emptyList())
                        } else {
                            dao.observeRecentAppTrafficWindows(
                                cutoff = statisticsRetentionCutoff(nowProvider(), settings.statistics.retention),
                            )
                                .map { entities -> entities.map(AppTrafficWindowEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentTrafficWindows: Flow<List<TrafficWindow>> =
        flow {
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!settings.trafficWindowStatsRuntimeEnabled()) {
                            flowOf(emptyList())
                        } else {
                            dao.observeRecentTrafficWindows(
                                cutoff = statisticsRetentionCutoff(nowProvider(), settings.statistics.retention),
                            )
                                .map { entities -> entities.map(TrafficWindowEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>> =
        flow {
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!settings.networkActivityStatsRuntimeEnabled()) {
                            flowOf(emptyList())
                        } else {
                            dao.observeRecentNetworkActivityEvents(
                                cutoff = statisticsRetentionCutoff(nowProvider(), settings.statistics.retention),
                                limit = NETWORK_ACTIVITY_PREVIEW_LIMIT,
                            )
                                .map { entities -> entities.map(NetworkActivityEventEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

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
        val retainedAppWindows =
            appWindows
                .takeIf { appTrafficLocalStorageAllowed(settings) }
                .orEmpty()
        val appHistories =
            batchedAppHistories(retainedAppWindows)
        val assessment =
            engine.evaluate(
                current = window,
                appWindows = retainedAppWindows,
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
        if (retainedAppWindows.isNotEmpty()) {
            dao.insertAppTrafficWindows(retainedAppWindows.map(AppTrafficWindowEntity::from))
        }
        baselineStore.updateTrafficBaseline(window, historyBeforeCurrent = history)
        retainedAppWindows.forEach { appWindow ->
            baselineStore.updateAppBaseline(
                window = appWindow,
                profileId = window.profileId,
                protocol = window.protocol,
                historyBeforeCurrent = appHistories[appWindow.packageName].orEmpty(),
            )
        }
        cleanupExpired(settings)
    }

    private suspend fun batchedAppHistories(
        appWindows: List<AppTrafficWindow>,
    ): Map<String, List<AppTrafficWindow>> {
        if (appWindows.isEmpty()) {
            return emptyMap()
        }
        return appWindows
            .groupBy { appWindow -> appWindow.networkType.name to anomalyHourBucket(appWindow.startedAtMs) }
            .flatMap { (bucket, bucketWindows) ->
                val packageNames = bucketWindows.map(AppTrafficWindow::packageName).distinct()
                dao.recentAppTrafficWindowsForPackages(
                    packageNames = packageNames,
                    networkType = bucket.first,
                    hourBucket = bucket.second,
                    limit = HISTORY_LIMIT,
                )
                    .groupBy(AppTrafficWindowEntity::packageName)
                    .map { (packageName, entities) ->
                        packageName to
                            entities
                                .take(HISTORY_LIMIT)
                                .map(AppTrafficWindowEntity::toDomain)
                    }
            }
            .toMap()
    }

    suspend fun recordAppTrafficWindows(windows: List<AppTrafficWindow>) {
        if (windows.isEmpty()) {
            return
        }
        if (!appTrafficLocalStorageAllowed(settingsRepository.current())) {
            return
        }
        dao.insertAppTrafficWindows(windows.map(AppTrafficWindowEntity::from))
        cleanupExpired(settingsRepository.current())
    }

    suspend fun recordNetworkActivityEvent(event: NetworkActivityEvent) {
        recordNetworkActivityEvents(listOf(event))
    }

    suspend fun recordNetworkActivityEvents(events: List<NetworkActivityEvent>) {
        val retainedEvents =
            events.filter { event ->
                event.packageNames.isNotEmpty() && event.remoteHost.isNotBlank()
            }
        if (retainedEvents.isEmpty()) {
            return
        }
        dao.insertNetworkActivityEvents(retainedEvents.map(NetworkActivityEventEntity::from))
        cleanupNetworkActivityIfDue(settingsRepository.current())
    }

    suspend fun clearTrafficStatistics() {
        dao.deleteTrafficWindowsBefore(Long.MAX_VALUE)
        dao.deleteAppTrafficWindowsBefore(Long.MAX_VALUE)
        dao.deleteTrafficBaselines()
        dao.deleteAppBaselines()
        dao.deleteNetworkActivityEventsBefore(Long.MAX_VALUE)
        dao.deleteAnomalyEventsBefore(Long.MAX_VALUE)
    }

    suspend fun clearAppTrafficPrivacyData() {
        dao.deleteAppTrafficWindowsBefore(Long.MAX_VALUE)
        dao.deleteAppBaselines()
        dao.deleteAppAnomalyEvents()
    }

    private suspend fun persistAssessment(
        assessment: AnomalyAssessment,
        window: TrafficWindow,
        settings: AnomalySettings,
    ) {
        if (assessment.signals.isEmpty()) {
            return
        }
        if (!assessment.shouldLogActivity) {
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

    private suspend fun cleanupExpired(settings: Settings) {
        val now = nowProvider()
        val anomalyCutoff = now - settings.anomaly.historyRetention.retentionHours * HOUR_MS
        val statisticsCutoff = statisticsRetentionCutoff(now, settings.statistics.retention)
        dao.deleteAnomalyEventsBefore(anomalyCutoff)
        dao.deleteTrafficWindowsBefore(statisticsCutoff)
        dao.deleteAppTrafficWindowsBefore(statisticsCutoff)
        dao.deleteNetworkActivityEventsBefore(statisticsCutoff)
    }

    private suspend fun cleanupNetworkActivityIfDue(settings: Settings) {
        val now = nowProvider()
        if (now - lastNetworkActivityCleanupAt < NETWORK_ACTIVITY_CLEANUP_INTERVAL_MS) {
            return
        }
        lastNetworkActivityCleanupAt = now
        dao.deleteNetworkActivityEventsBefore(statisticsRetentionCutoff(now, settings.statistics.retention))
    }

    companion object {
        private const val HISTORY_LIMIT = 96
        private const val NETWORK_ACTIVITY_PREVIEW_LIMIT = 200
        private const val NETWORK_ACTIVITY_CLEANUP_INTERVAL_MS = 60L * 1000L
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val NOTIFICATION_COOLDOWN_MS = 6L * 60L * 60L * 1000L
        private const val ANOMALY_LOG_TAG = "anomaly"
    }
}

internal fun statisticsRetentionCutoff(
    nowMs: Long,
    retention: StatisticsRetention,
): Long =
    when (retention) {
        StatisticsRetention.WEEK -> nowMs - 7L * 24L * 60L * 60L * 1000L
        StatisticsRetention.MONTH -> nowMs - 31L * 24L * 60L * 60L * 1000L
        StatisticsRetention.MONTHS_3 -> nowMs - 93L * 24L * 60L * 60L * 1000L
        StatisticsRetention.FOREVER -> 0L
    }

internal fun appTrafficLocalStorageAllowed(settings: Settings): Boolean =
    settings.statistics.enabled &&
        settings.statistics.appTrafficEnabled &&
        settings.appTrafficStatsEnabled &&
        settings.appTrafficUsageAccessConsent

private fun Settings.trafficWindowStatsRuntimeEnabled(): Boolean =
    statistics.enabled &&
        (
            statistics.dnsFilteringEnabled ||
                statistics.anomalyMetricsEnabled ||
                (statistics.countryTrafficEnabled && expert.firewallEnabled)
            )

private fun Settings.networkActivityStatsRuntimeEnabled(): Boolean =
    statistics.enabled &&
        statistics.appTrafficEnabled &&
        expert.networkActivityLogging
