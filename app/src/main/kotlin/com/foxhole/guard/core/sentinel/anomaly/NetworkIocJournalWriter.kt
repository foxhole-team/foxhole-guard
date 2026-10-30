package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.sentinel.detection.NetworkIocFinding
import com.foxhole.core.sentinel.detection.networkIocSeverity
import com.foxhole.guard.core.data.AnomalyDao
import com.foxhole.guard.core.data.AnomalyEventEntity

internal class NetworkIocJournalWriter(
    private val dao: AnomalyDao,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val notify: (AnomalyEvent) -> Boolean,
    private val nowProvider: () -> Long,
    private val canNotify: suspend (String, String?) -> Boolean,
    private val coalesceMillis: Long,
) {
    suspend fun persist(
        finding: NetworkIocFinding,
        settings: Settings,
    ) {
        if (anomalySuppressedForExcludedApp(settings.expert.appAssignments, finding.packageName)) {
            diagnosticsLogger.record("anomaly", "network ioc hit suppressed for excluded app")
            return
        }
        val severity = finding.hit.threatKind.networkIocSeverity()
        val previousSeverity =
            dao.latestAnomalyEventSince(
                type = AnomalyType.KNOWN_THREAT_DESTINATION.name,
                packageName = finding.packageName,
                since = nowProvider() - coalesceMillis,
            )?.let { entity -> runCatching { AnomalySeverity.valueOf(entity.severity) }.getOrNull() }
        if (shouldCoalesceAnomalyEvent(previousSeverity, severity)) {
            return
        }
        val shouldNotify =
            settings.anomaly.notifyUnusualTraffic &&

                severity.ordinal >= AnomalySeverity.NOTIFICATION.ordinal &&
                canNotify(AnomalyType.KNOWN_THREAT_DESTINATION.name, finding.packageName)
        val event = networkIocAnomalyEvent(finding, nowMs = nowProvider(), notificationShown = false)
        val persisted = event.copy(id = dao.insertAnomalyEvent(AnomalyEventEntity.from(event)))
        diagnosticsLogger.recordStructured(
            tag = "anomaly",
            headline = persisted.reason,
            "package=${finding.packageName}",
            "host=${finding.remoteHost}",
            "indicator=${finding.hit.indicator}",
        )
        if (shouldNotify && notify(persisted)) {
            dao.markAnomalyNotificationShown(persisted.id)
        }
    }
}
