@file:Suppress("ImportOrdering")

package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.AnomalySignal
import com.foxhole.core.model.AppBaseline
import com.foxhole.core.model.AppNetworkUsageCategory
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.effectiveDiagnosticsRetention
import com.foxhole.core.model.effectiveRetention
import com.foxhole.core.model.ThreatIndicatorKind
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.sentinel.detection.AnomalyAssessment
import com.foxhole.core.sentinel.detection.AnomalyHistory
import com.foxhole.core.sentinel.detection.FoxholeSentinel
import com.foxhole.core.sentinel.detection.NetworkIocFinding
import com.foxhole.core.sentinel.detection.NetworkIocMatcher
import com.foxhole.core.sentinel.detection.networkIocScore
import com.foxhole.core.sentinel.detection.networkIocSeverity
import com.foxhole.guard.core.data.AnomalyCounterEntity
import com.foxhole.guard.core.data.AnomalyDao
import com.foxhole.guard.core.data.AnomalyEventEntity
import com.foxhole.guard.core.data.AppBaselineEntity
import com.foxhole.guard.core.data.AppNetworkPresenceEntity
import com.foxhole.guard.core.data.AppTrafficWindowEntity
import com.foxhole.guard.core.data.NetworkActivityEventEntity
import com.foxhole.guard.core.data.ProtocolMetricEventEntity
import com.foxhole.guard.core.data.SeenDestinationCountryEntity
import com.foxhole.guard.core.data.TrafficWindowEntity
import com.foxhole.guard.core.data.anomalyHourBucket
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AnomalyRepository(
    daoProvider: () -> AnomalyDao,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val notifier: SentinelDetectionNotifier,
    private val securityCore: FoxholeSentinel = FoxholeSentinel(),
    // Production injects threat intel; the default matches nothing.
    private val networkIocMatcher: () -> NetworkIocMatcher = { EMPTY_NETWORK_MATCHER },
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val appCategoryResolver: (String) -> AppNetworkUsageCategory = { AppNetworkUsageCategory.STANDARD },
    // Prevent database access before SQLCipher key installation.
    private val awaitDatabaseReady: suspend () -> Unit = {},
) {
    private val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED, daoProvider)
    private val baselineStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BaselineStore(dao) }

    // Serialize the non-transactional read/evaluate/write pipeline.
    private val recordMutex = Mutex()
    private var lastNetworkActivityCleanupAt = 0L

    val recentEvents: Flow<List<AnomalyEvent>> =
        flow {
            awaitDatabaseReady()
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        val cutoff = nowProvider() - settings.anomaly.historyRetention.retentionHours * HOUR_MS
                        dao.observeAnomalyEvents(
                            cutoff = cutoff,
                            // The old country-list heuristic did not measure Tor/I2P routing.
                            // Keep its rows for schema compatibility, but never present them as
                            // current FoxHole Sentinel findings.
                            excludedType = AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH.name,
                        ).map { entities -> entities.map(AnomalyEventEntity::toDomain) }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentAppTrafficWindows: Flow<List<AppTrafficWindow>> =
        flow {
            awaitDatabaseReady()
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!appTrafficLocalStorageAllowed(settings)) {
                            flowOf(emptyList())
                        } else {
                            dao.observeRecentAppTrafficWindows(
                                cutoff = statisticsRetentionCutoff(nowProvider(), settings.statistics),
                                limit = MAX_APP_TRAFFIC_WINDOW_ROWS,
                            )
                                .map { entities -> entities.map(AppTrafficWindowEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentTrafficWindows: Flow<List<TrafficWindow>> =
        flow {
            awaitDatabaseReady()
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!sentinelTrafficWindowCollectionEnabled(settings)) {
                            flowOf(emptyList())
                        } else {
                            dao.observeRecentTrafficWindows(
                                cutoff = statisticsRetentionCutoff(nowProvider(), settings.statistics),
                                limit = MAX_TRAFFIC_WINDOW_ROWS,
                            )
                                .map { entities -> entities.map(TrafficWindowEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>> =
        flow {
            awaitDatabaseReady()
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!settings.expert.networkActivityLogging) {
                            flowOf(emptyList())
                        } else {
                            dao.observeRecentNetworkActivityEvents(
                                cutoff = networkJournalRetentionCutoff(nowProvider(), settings),
                                limit = NETWORK_ACTIVITY_PREVIEW_LIMIT,
                            )
                                .map { entities -> entities.map(NetworkActivityEventEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    val recentProtocolMetricEvents: Flow<List<ProtocolMetricEvent>> =
        flow {
            awaitDatabaseReady()
            emitAll(
                settingsRepository.settings
                    .flatMapLatest { settings ->
                        if (!settings.statistics.enabled) {
                            flowOf(emptyList())
                        } else {
                            dao.observeProtocolMetricEvents(
                                startMs = statisticsRetentionCutoff(nowProvider(), settings.statistics),
                                endMs = Long.MAX_VALUE,
                            )
                                .map { entities -> entities.map(ProtocolMetricEventEntity::toDomain) }
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    /**
     * Persists one measured protocol outcome. No-ops while statistics collection is disabled and
     * prunes the table to the statistics retention on every write, so the event stream can never
     * outgrow the rest of the local statistics data.
     */
    suspend fun recordProtocolMetricEvent(event: ProtocolMetricEvent) {
        val settings = settingsRepository.current()
        if (!settings.statistics.enabled) {
            return
        }
        runCatching {
            dao.insertProtocolMetricEvent(ProtocolMetricEventEntity.from(event))
            dao.deleteProtocolMetricEventsBefore(
                statisticsRetentionCutoff(nowProvider(), settings.statistics),
            )
        }.onFailure {
            diagnosticsLogger.record(ANOMALY_LOG_TAG, "protocol metric event persist failed")
        }
    }

    suspend fun recordTrafficWindow(
        window: TrafficWindow,
        appWindows: List<AppTrafficWindow> = emptyList(),
    ): Unit = recordMutex.withLock {
        val settings = settingsRepository.current()
        // Recheck settings at the persistence boundary to close toggle races.
        if (!sentinelTrafficWindowCollectionEnabled(settings)) {
            return@withLock
        }
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
        // Exclude the window under evaluation from its historical context.
        val seenCountries = dao.getSeenDestinationCountries().associateBy(SeenDestinationCountryEntity::country)
        val observedWindowCount = dao.getAnomalyCounter(COUNTER_TRAFFIC_WINDOWS) ?: 0L
        val presenceByPackage = loadAppNetworkPresence(retainedAppWindows)
        val assessment =
            securityCore.evaluateTraffic(
                current = window,
                appWindows = retainedAppWindows,
                history = AnomalyHistory(
                    trafficWindows = history,
                    appWindowsByPackage = appHistories,
                    everSeenCountries = seenCountries.keys.mapTo(HashSet()) { it.uppercase(Locale.US) },
                    observedTrafficWindowCount = observedWindowCount,
                    appPresence = presenceByPackage.mapValues { (_, entity) -> entity.toDomain() },
                    trafficBaseline = loadTrafficBaseline(window, hourBucket),
                    appBaselinesByPackage = loadAppBaselines(window, retainedAppWindows),
                ),
                settings = settings.anomaly,
                appCategories = retainedAppWindows.associate { it.packageName to appCategoryResolver(it.packageName) },
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
        updateSeenDestinationCountries(seenCountries, window)
        refreshAppNetworkPresence(retainedAppWindows, presenceByPackage)
        dao.upsertAnomalyCounter(AnomalyCounterEntity(COUNTER_TRAFFIC_WINDOWS, observedWindowCount + 1))
        cleanupExpired(settings)
    }

    private suspend fun loadTrafficBaseline(
        window: TrafficWindow,
        hourBucket: Int,
    ) = dao.getTrafficBaseline(
        BaselineStore.trafficBaselineKey(
            profileId = window.profileId,
            protocol = window.protocol,
            networkType = window.networkType,
            hourBucket = hourBucket,
            metric = BaselineStore.METRIC_TOTAL_BYTES_PER_MIN,
        ),
    )?.toDomain()

    private suspend fun loadAppBaselines(
        window: TrafficWindow,
        appWindows: List<AppTrafficWindow>,
    ): Map<String, AppBaseline> {
        if (appWindows.isEmpty()) {
            return emptyMap()
        }
        val keysByPackage =
            appWindows.associate { appWindow ->
                appWindow.packageName to
                    BaselineStore.appBaselineKey(
                        packageName = appWindow.packageName,
                        profileId = window.profileId,
                        protocol = window.protocol,
                        networkType = appWindow.networkType,
                        hourBucket = anomalyHourBucket(appWindow.startedAtMs),
                        metric = BaselineStore.METRIC_APP_TX_BYTES_PER_MIN,
                    )
            }
        val entitiesByKey =
            dao.getAppBaselines(keysByPackage.values.toList())
                .associateBy(AppBaselineEntity::baselineKey)
        return keysByPackage
            .mapNotNull { (packageName, key) -> entitiesByKey[key]?.let { packageName to it.toDomain() } }
            .toMap()
    }

    private suspend fun loadAppNetworkPresence(
        appWindows: List<AppTrafficWindow>,
    ): Map<String, AppNetworkPresenceEntity> =
        if (appWindows.isEmpty()) {
            emptyMap()
        } else {
            dao.getAppNetworkPresence(appWindows.map(AppTrafficWindow::packageName).distinct())
                .associateBy(AppNetworkPresenceEntity::packageName)
        }

    private suspend fun updateSeenDestinationCountries(
        existing: Map<String, SeenDestinationCountryEntity>,
        window: TrafficWindow,
    ) {
        if (window.destinationCountries.isEmpty()) {
            return
        }
        val seenAt = window.startedAtMs + window.durationSec * 1000L
        val updates =
            window.destinationCountries.map { (country, bytes) ->
                val previous = existing[country]
                SeenDestinationCountryEntity(
                    country = country,
                    firstSeenMs = previous?.firstSeenMs ?: window.startedAtMs,
                    lastSeenMs = seenAt,
                    totalBytes = (previous?.totalBytes ?: 0L) + bytes.coerceAtLeast(0L),
                )
            }
        dao.upsertSeenDestinationCountries(updates)
    }

    private suspend fun refreshAppNetworkPresence(
        appWindows: List<AppTrafficWindow>,
        existing: Map<String, AppNetworkPresenceEntity>,
    ) {
        val active = appWindows.filter { appWindow -> appWindow.totalBytes > 0L }
        if (active.isEmpty()) {
            return
        }
        val updates =
            active
                .groupBy(AppTrafficWindow::packageName)
                .map { (packageName, windows) ->
                    val previous = existing[packageName]
                    val lastSeen = windows.maxOf { it.startedAtMs + it.durationSec * 1000L }
                    AppNetworkPresenceEntity(
                        packageName = packageName,
                        firstSeenMs = previous?.firstSeenMs ?: windows.minOf(AppTrafficWindow::startedAtMs),
                        lastSeenMs = maxOf(previous?.lastSeenMs ?: 0L, lastSeen),
                        windowCount = (previous?.windowCount ?: 0L) + windows.size,
                    )
                }
        dao.upsertAppNetworkPresence(updates)
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
        recordMutex.withLock {
            dao.insertAppTrafficWindows(windows.map(AppTrafficWindowEntity::from))
            // Every ingestion path must update presence to prevent false dormancy alarms.
            refreshAppNetworkPresence(windows, loadAppNetworkPresence(windows))
            // Keep retention inside the ingestion lock.
            cleanupExpired(settingsRepository.current())
        }
    }

    suspend fun recordNetworkActivityEvent(event: NetworkActivityEvent) {
        recordNetworkActivityEvents(listOf(event))
    }

    suspend fun recordNetworkActivityEvents(events: List<NetworkActivityEvent>) = recordMutex.withLock {
        val retainedEvents =
            events.filter { event ->
                event.packageNames.isNotEmpty() && event.remoteHost.isNotBlank()
            }
        if (retainedEvents.isEmpty()) {
            return@withLock
        }
        val settings = settingsRepository.current()
        dao.insertNetworkActivityEvents(retainedEvents.map(NetworkActivityEventEntity::from))
        cleanupNetworkActivityIfDue(settings)
        recordNetworkIocFindings(retainedEvents, settings)
    }

    /**
     * Network IOC pass over a fresh event batch: destinations are matched against the threat-intel
     * bundle, and a hit lands in the anomaly journal attributed to the app that opened the flow.
     * Gated on the Sentinel anomaly switch — matching is analysis, not statistics retention.
     */
    private suspend fun recordNetworkIocFindings(
        events: List<NetworkActivityEvent>,
        settings: Settings,
    ) {
        if (!settings.anomaly.enabled) {
            return
        }
        val matcher = networkIocMatcher()
        if (matcher.isEmpty) {
            return
        }
        dedupeNetworkIocFindings(securityCore.matchNetworkIndicators(events, matcher)).forEach { finding ->
            persistNetworkIocFinding(finding, settings)
        }
    }

    private suspend fun persistNetworkIocFinding(
        finding: NetworkIocFinding,
        settings: Settings,
    ) {
        if (anomalySuppressedForExcludedApp(settings.expert.appAssignments, finding.packageName)) {
            diagnosticsLogger.record(ANOMALY_LOG_TAG, "network ioc hit suppressed for excluded app")
            return
        }
        val severity = finding.hit.threatKind.networkIocSeverity()
        val previousSeverity =
            dao.latestAnomalyEventSince(
                type = AnomalyType.KNOWN_THREAT_DESTINATION.name,
                packageName = finding.packageName,
                since = nowProvider() - ONGOING_EVENT_COALESCE_MS,
            )?.let { entity -> runCatching { AnomalySeverity.valueOf(entity.severity) }.getOrNull() }
        if (shouldCoalesceAnomalyEvent(previousSeverity, severity)) {
            return
        }
        val shouldNotify =
            settings.anomaly.notifyUnusualTraffic &&
                // Journal shared-infrastructure matches without notifying.
                severity.ordinal >= AnomalySeverity.NOTIFICATION.ordinal &&
                canNotify(AnomalyType.KNOWN_THREAT_DESTINATION.name, finding.packageName)
        val event = networkIocAnomalyEvent(finding, nowMs = nowProvider(), notificationShown = false)
        val persisted = event.copy(id = dao.insertAnomalyEvent(AnomalyEventEntity.from(event)))
        diagnosticsLogger.recordStructured(
            tag = ANOMALY_LOG_TAG,
            headline = persisted.reason,
            "package=${finding.packageName}",
            "host=${finding.remoteHost}",
            "indicator=${finding.hit.indicator}",
        )
        if (shouldNotify && notifier.notify(persisted)) {
            dao.markAnomalyNotificationShown(persisted.id)
        }
    }

    /**
     * Under [recordMutex], like every ingestion path.
     *
     * "Clear" and "record" used to be able to interleave: a detection reads the coalescing window,
     * a clear wipes the table, and the detection then inserts the row the user just asked to be
     * gone — it reappears with no trace of why. The lock makes the erase and the decide-then-write
     * sequences one thing or the other, never halves of both.
     */
    suspend fun clearTrafficStatistics() =
        recordMutex.withLock {
            dao.deleteTrafficWindowsBefore(Long.MAX_VALUE)
            dao.deleteAppTrafficWindowsBefore(Long.MAX_VALUE)
            dao.deleteTrafficBaselines()
            dao.deleteAppBaselines()
            dao.deleteNetworkActivityEventsBefore(Long.MAX_VALUE)
            dao.deleteAnomalyEventsBefore(Long.MAX_VALUE)
            dao.deleteAllProtocolMetricEvents()
            dao.deleteSeenDestinationCountries()
            dao.deleteAppNetworkPresence()
            dao.deleteAnomalyCounters()
        }

    suspend fun clearAppTrafficPrivacyData() =
        recordMutex.withLock {
            dao.deleteAppTrafficWindowsBefore(Long.MAX_VALUE)
            dao.deleteAppBaselines()
            dao.deleteAppAnomalyEvents()
            dao.deleteAppNetworkPresence()
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
        if (anomalySuppressedForExcludedApp(settingsRepository.current().expert.appAssignments, packageName)) {
            // EXCLUDE disables inspection for the app.
            diagnosticsLogger.record(
                tag = ANOMALY_LOG_TAG,
                message = "anomaly suppressed for excluded app",
            )
            return
        }
        // Deduplicate adjacent windows; re-log only escalation.
        val previousSeverity =
            dao.latestAnomalyEventSince(
                type = strongest.type.name,
                packageName = packageName,
                since = nowProvider() - ONGOING_EVENT_COALESCE_MS,
            )?.let { entity -> runCatching { AnomalySeverity.valueOf(entity.severity) }.getOrNull() }
        if (shouldCoalesceAnomalyEvent(previousSeverity, assessment.severity)) {
            diagnosticsLogger.record(
                tag = ANOMALY_LOG_TAG,
                message = "coalesced ongoing ${strongest.type.name.lowercase(Locale.US)} event",
            )
            return
        }
        val shouldNotify =
            assessment.shouldNotify &&
                settings.notifyUnusualTraffic &&
                canNotify(strongest.type.name, packageName)
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
                notificationShown = false,
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
        if (shouldNotify && notifier.notify(persisted)) {
            dao.markAnomalyNotificationShown(persisted.id)
        }
    }

    private suspend fun canNotify(
        typeName: String,
        packageName: String?,
    ): Boolean {
        val since = nowProvider() - NOTIFICATION_COOLDOWN_MS
        return dao.notificationCountSince(
            type = typeName,
            packageName = packageName,
            since = since,
        ) == 0
    }

    /**
     * One explicit purge pass for app start: without it, retention is only enforced
     * lazily on writes and an idle install never ages anything out.
     */
    suspend fun cleanupExpiredNow() =
        recordMutex.withLock {
            cleanupExpired(settingsRepository.current())
        }

    /**
     * Re-attempts the notifications that never reached the shade.
     *
     * The notifier already refuses to mark an event as shown unless Android accepted the post, and
     * the overwhelmingly common refusal is POST_NOTIFICATIONS not being granted yet — so without a
     * retry the honest flag simply meant those detections were never told to anyone. This is that
     * retry: at app start, the detections found while notifications were impossible get one more
     * chance.
     *
     * Bounded twice over. Only events inside the retry window are eligible, so granting the
     * permission after a quiet week does not produce a week of alarms at once; and the same
     * per-type cooldown as the live path applies, so a burst collapses to one notification.
     */
    suspend fun deliverPendingNotifications() =
        recordMutex.withLock {
            val settings = settingsRepository.current()
            if (!settings.anomaly.enabled || !settings.anomaly.notifyUnusualTraffic) {
                return@withLock
            }
            dao
                .undeliveredAnomalyEvents(
                    since = nowProvider() - NOTIFICATION_RETRY_WINDOW_MS,
                    limit = MAX_NOTIFICATION_RETRIES,
                    excludedType = AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH.name,
                ).map(AnomalyEventEntity::toDomain)
                .forEach { event ->
                    if (!canNotify(event.type.name, event.packageName)) {
                        return@forEach
                    }
                    if (notifier.notify(event)) {
                        dao.markAnomalyNotificationShown(event.id)
                    }
                }
        }

    private suspend fun cleanupExpired(settings: Settings) {
        val now = nowProvider()
        val anomalyCutoff = now - settings.anomaly.historyRetention.retentionHours * HOUR_MS
        val statisticsCutoff = statisticsRetentionCutoff(now, settings.statistics)
        dao.deleteAnomalyEventsBefore(anomalyCutoff)
        dao.deleteTrafficWindowsBefore(statisticsCutoff)
        dao.deleteAppTrafficWindowsBefore(statisticsCutoff)
        dao.deleteNetworkActivityEventsBefore(networkJournalRetentionCutoff(now, settings))
        dao.deleteProtocolMetricEventsBefore(statisticsCutoff)
        trimStatisticsRows()
    }

    /**
     * The age cutoff is 0 under the "forever" retention, so it prunes nothing and the statistics
     * tables would grow without bound. The row caps are the backstop; they also match the limits
     * the UI observes, so nothing visible is trimmed away.
     */
    private suspend fun trimStatisticsRows() {
        dao.trimTrafficWindowsTo(MAX_TRAFFIC_WINDOW_ROWS)
        dao.trimAppTrafficWindowsTo(MAX_APP_TRAFFIC_WINDOW_ROWS)
        dao.trimNetworkActivityEventsTo(MAX_NETWORK_ACTIVITY_ROWS)
        dao.trimProtocolMetricEventsTo(MAX_PROTOCOL_METRIC_ROWS)
    }

    private suspend fun cleanupNetworkActivityIfDue(settings: Settings) {
        val now = nowProvider()
        if (now - lastNetworkActivityCleanupAt < NETWORK_ACTIVITY_CLEANUP_INTERVAL_MS) {
            return
        }
        lastNetworkActivityCleanupAt = now
        dao.deleteNetworkActivityEventsBefore(networkJournalRetentionCutoff(now, settings))
    }

    companion object {
        // Caps bound forever retention without trimming rows visible to queries.
        internal const val MAX_TRAFFIC_WINDOW_ROWS = 20_000
        internal const val MAX_APP_TRAFFIC_WINDOW_ROWS = 60_000
        internal const val MAX_NETWORK_ACTIVITY_ROWS = 50_000
        internal const val MAX_PROTOCOL_METRIC_ROWS = 20_000
        private const val HISTORY_LIMIT = 96
        private const val NETWORK_ACTIVITY_PREVIEW_LIMIT = 200
        private const val NETWORK_ACTIVITY_CLEANUP_INTERVAL_MS = 60L * 1000L
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val NOTIFICATION_COOLDOWN_MS = 6L * 60L * 60L * 1000L

        // Do not notify for detections older than one day.
        private const val NOTIFICATION_RETRY_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val MAX_NOTIFICATION_RETRIES = 5
        private const val ONGOING_EVENT_COALESCE_MS = 45L * 60L * 1000L
        private const val COUNTER_TRAFFIC_WINDOWS = "traffic_windows_observed"
        private const val ANOMALY_LOG_TAG = "anomaly"
        private val EMPTY_NETWORK_MATCHER = NetworkIocMatcher(ThreatIntelDocument())
    }
}

/** One journal row per (package, indicator) within a batch: a chatty flow is one sighting. */
internal fun dedupeNetworkIocFindings(findings: List<NetworkIocFinding>): List<NetworkIocFinding> =
    findings.distinctBy { finding -> finding.packageName to finding.hit.indicator }

/**
 * The journal row for one indicator match. Severity and score follow what the feed says the
 * indicator IS: every indicator used to score alike, so a hit on a threat's shared CDN address —
 * which thousands of ordinary apps reach — produced the same confident HIGH as a hit on its
 * command-and-control endpoint.
 *
 * The wording still has to stay on the right side of the datasets' own caveat: a hit names a listed
 * destination, it does not prove the device is compromised.
 */
internal fun networkIocAnomalyEvent(
    finding: NetworkIocFinding,
    nowMs: Long,
    notificationShown: Boolean,
): AnomalyEvent =
    AnomalyEvent(
        createdAtMs = nowMs,
        type = AnomalyType.KNOWN_THREAT_DESTINATION,
        severity = finding.hit.threatKind.networkIocSeverity(),
        score = finding.hit.threatKind.networkIocScore(),
        reason = networkIocReason(finding.hit.threatKind),
        evidence =
        mapOf(
            "host" to finding.remoteHost,
            "indicator" to finding.hit.indicator,
            "indicator_kind" to finding.hit.kind.name,
            "threat_kind" to finding.hit.threatKind.name,
        ),
        packageName = finding.packageName,
        profileId = finding.profileId?.toString(),
        protocol = finding.protocol,
        notificationShown = notificationShown,
    )

/** Describe only the threat classification asserted by the feed. */
private fun networkIocReason(threatKind: ThreatIndicatorKind): String =
    when (threatKind) {
        ThreatIndicatorKind.COMMAND_AND_CONTROL ->
            "Traffic reached a published malware command-and-control endpoint"
        ThreatIndicatorKind.MALWARE_DISTRIBUTION ->
            "Traffic reached a host published as a malware distribution source"
        ThreatIndicatorKind.SHARED_INFRASTRUCTURE ->
            "Traffic reached shared infrastructure listed alongside a known threat"
        ThreatIndicatorKind.UNCLASSIFIED ->
            "Traffic reached a destination from a published threat indicator list"
    }

/** An ongoing anomaly is coalesced into the previous journal row unless it escalated. */
internal fun anomalySuppressedForExcludedApp(
    assignments: Map<String, AppTunnelLane>,
    packageName: String?,
): Boolean = packageName != null && assignments[packageName] == AppTunnelLane.EXCLUDE

internal fun shouldCoalesceAnomalyEvent(
    previousSeverity: AnomalySeverity?,
    candidateSeverity: AnomalySeverity,
): Boolean = previousSeverity != null && previousSeverity.ordinal >= candidateSeverity.ordinal

internal fun statisticsRetentionCutoff(
    nowMs: Long,
    settings: StatisticsSettings,
): Long = settings.effectiveRetention().cutoffOrNull(nowMs) ?: 0L

internal fun appTrafficLocalStorageAllowed(settings: Settings): Boolean =
    settings.statistics.enabled &&
        settings.statistics.appTrafficEnabled &&
        settings.appTrafficStatsEnabled &&
        settings.appTrafficUsageAccessConsent

internal fun sentinelTrafficWindowCollectionEnabled(settings: Settings): Boolean =
    settings.anomaly.enabled || settings.statistics.enabled

internal fun networkJournalRetentionCutoff(nowMs: Long, settings: Settings): Long =
    settings.expert.effectiveDiagnosticsRetention().cutoffOrNull(nowMs) ?: 0L
