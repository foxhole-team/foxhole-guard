package com.foxhole.guard.core.sentinel.scanner

import kotlinx.serialization.Serializable

@Serializable
internal enum class ScanReportStatus { RUNNING, COMPLETE, PARTIAL, UNAVAILABLE, UNSUPPORTED, STALE, CANCELLED, INTERRUPTED }

@Serializable
internal data class ScanArtifactReport(
    val sourceId: String,
    val kind: String,
    val inputBytes: Long,
    val inputComplete: Boolean,
    val sha256: String?,
    val entriesDeclared: Int,
    val entriesVerified: Int,
    val containerValidated: Boolean,
)

@Serializable
internal data class ScanCoverageReport(
    val requestedArtifacts: Int = 0,
    val openedArtifacts: Int = 0,
    val declaredDexEntries: Int = 0,
    val completedDexEntries: Int = 0,
    val expandedBytes: Long = 0,
    val workUnits: Long = 0,
)

@Serializable
internal data class SentinelScanReport(
    val id: String,
    val processOwner: String,
    val packageName: String?,
    val installationId: String? = null,
    val packageVersion: Long? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val requestType: String,
    val status: ScanReportStatus = ScanReportStatus.RUNNING,
    val analyzerVersion: String? = null,
    val appVersion: String,
    val ruleEpoch: String?,
    val artifacts: List<ScanArtifactReport> = emptyList(),
    val coverage: ScanCoverageReport = ScanCoverageReport(),
    val observations: Set<String> = emptySet(),
    val ruleIds: Set<String> = emptySet(),
    val issues: Set<String> = emptySet(),
    val limitations: Set<String> = emptySet(),
    val cached: Boolean = false,
    val journalDeferred: Boolean = false,
    val artifactByteLimit: Int = 16 * 1024 * 1024,
    val completedPackages: Int = 0,
    val requestedPackages: Int? = 1,
    val requestSessionId: String = id,
    val requestComplete: Boolean? = null,
)
