package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.guard.core.data.AnomalyDao
import com.foxhole.guard.core.data.AnomalyEventEntity
import com.foxhole.guard.core.sentinel.scanner.ScannerJournalOwnerChanged
import com.foxhole.guard.core.sentinel.scanner.SentinelScanSummary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class StaticScanJournalWriter(
    private val dao: AnomalyDao,
    private val settingsRepository: SentinelSettingsReader,
    private val notify: (AnomalyEvent) -> Boolean,
    private val now: () -> Long,
    private val transaction: (suspend (suspend () -> Boolean) -> Boolean)?,
    private val epochCurrent: (Long) -> Boolean,
) {
    suspend fun record(summary: SentinelScanSummary, current: () -> Boolean): Boolean {
        val settings = settingsRepository.current()
        if (!settings.anomaly.enabled || !owned(summary, current)) return false
        if (summary.observations.isEmpty() && summary.ruleIds.isEmpty()) return true
        val event = staticScanEvent(summary, now())
        val id = commit(summary, event, current) ?: return false
        if (settings.anomaly.apkScan.notifyFindings && owned(summary, current)) notifyIfAllowed(event.copy(id = id))
        return true
    }

    private suspend fun commit(summary: SentinelScanSummary, event: AnomalyEvent, current: () -> Boolean): Long? {
        val transact = requireNotNull(transaction) { "Scanner journal transaction unavailable" }
        var id = 0L
        val accepted = transact {
            currentCoroutineContext().ensureActive()
            if (!owned(summary, current)) return@transact false
            id = dao.insertAnomalyEvent(AnomalyEventEntity.from(event))
            currentCoroutineContext().ensureActive()
            if (!owned(summary, current)) throw ScannerJournalOwnerChanged()
            true
        }
        return id.takeIf { accepted }
    }

    private suspend fun notifyIfAllowed(event: AnomalyEvent) {
        if (dao.globalNotificationCountSince(AnomalyType.APP_STATIC_REFERENCE.name, now() - COOLDOWN_MS) != 0) return
        if (notify(event)) dao.markAnomalyNotificationShown(event.id)
    }

    private fun owned(summary: SentinelScanSummary, current: () -> Boolean): Boolean = current() && epochCurrent(summary.source.journalEpoch)
    private companion object { const val COOLDOWN_MS = 6L * 60 * 60 * 1000 }
}

internal fun staticScanEvent(summary: SentinelScanSummary, now: Long): AnomalyEvent = AnomalyEvent(
    createdAtMs = now,
    type = AnomalyType.APP_STATIC_REFERENCE,
    severity = AnomalySeverity.ACTIVITY_LOG,
    score = 0,
    reason = "static_reference_presence",
    evidence = mapOf(
        "reference_count" to summary.observations.size.toString(),
        "rule_count" to summary.ruleIds.size.toString(),
        "coverage_complete" to summary.complete.toString(),
        "observations" to summary.observations.sorted().joinToString(",").take(1024),
        "rule_ids" to summary.ruleIds.sorted().joinToString(",").take(4096),
        "installation_id" to summary.source.installationId,
    ),
    packageName = summary.source.packageName,
)
