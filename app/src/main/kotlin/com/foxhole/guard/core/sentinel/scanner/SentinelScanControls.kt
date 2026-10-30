package com.foxhole.guard.core.sentinel.scanner

import kotlinx.coroutines.flow.StateFlow

internal interface SentinelScanControls {
    val state: StateFlow<SentinelScanControlState>
    suspend fun updatePolicy(policy: SentinelScanWorkPolicy)
    suspend fun requestPackage(packageName: String)
    suspend fun requestVisibleBaseline()
    suspend fun cancel()
}

internal data class SentinelScanWorkPolicy(
    val masterEnabled: Boolean = false,
    val dexEnabled: Boolean = false,
    val yaraEnabled: Boolean = false,
    val notifyFindings: Boolean = false,
    val automaticNewUpdatedApps: Boolean = false,
    val chargingOnly: Boolean = true,
    val batteryNotLow: Boolean = true,
    val maxArtifactBytes: Int = 16 * 1024 * 1024,
    val ruleEpoch: String = "none",
) {
    init {
        require(maxArtifactBytes in setOf(16 * 1024 * 1024, 64 * 1024 * 1024))
        require(ruleEpoch.length in 1..128)
    }
}

internal data class SentinelScanEnvironment(val charging: Boolean, val batteryNotLow: Boolean, val storageNotLow: Boolean, val criticalBattery: Boolean = false)
internal data class SentinelScanSource(val packageName: String, val installationId: String, val journalEpoch: Long = 0L)
internal data class SentinelScanSummary(
    val source: SentinelScanSource,
    val complete: Boolean,
    val observations: Set<String>,
    val ruleIds: Set<String>,
    val issues: Set<String> = emptySet(),
    val outcome: SentinelScanStage = if (complete) SentinelScanStage.COMPLETE else SentinelScanStage.PARTIAL,
) {
    init {
        require(
            outcome in setOf(SentinelScanStage.COMPLETE, SentinelScanStage.PARTIAL, SentinelScanStage.UNAVAILABLE, SentinelScanStage.UNSUPPORTED)
        )
        require(complete == (outcome == SentinelScanStage.COMPLETE))
    }
}
internal enum class SentinelScanStage { IDLE, DISABLED, WAITING, RUNNING, COMPLETE, PARTIAL, STALE, CANCELLED, UNAVAILABLE, UNSUPPORTED }
internal enum class ScannerJournalOutcome { COMMITTED, DEFERRED, REJECTED }
internal data class SentinelScanControlState(
    val stage: SentinelScanStage = SentinelScanStage.DISABLED,
    val packageName: String? = null,
    val completedPackages: Int = 0,
    val requestedPackages: Int = 0,
    val lastSummary: SentinelScanSummary? = null,
    val cached: Boolean = false,
    val journalDeferred: Boolean = false,
)
