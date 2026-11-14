package com.foxhole.guard.core.sentinel.scanner

import android.os.Bundle
import com.foxhole.guard.core.sentinel.apk.ApkInspection

internal fun Bundle.putScannerArtifactCoverage(inspection: ApkInspection) {
    putParcelableArrayList("artifact_reports", ArrayList(inspection.artifacts.map { artifact -> Bundle().apply {
        putString("source", artifact.sourceId)
        putString("kind", artifact.kind.name)
        putLong("bytes", artifact.inputBytes)
        putBoolean("complete", artifact.inputComplete)
        putString("sha256", artifact.sha256)
        putInt("declared", artifact.entriesDeclared)
        putInt("verified", artifact.entriesVerified)
        putBoolean("validated", artifact.containerValidated)
    } }))
    putInt("coverage_requested", inspection.coverage.requestedArtifacts)
    putInt("coverage_opened", inspection.coverage.openedArtifacts)
    putInt("dex_declared", inspection.coverage.declaredDexEntries)
    putInt("dex_completed", inspection.coverage.completedDexEntries)
}

internal data class ScannerArtifactCoverage(
    val reported: Boolean,
    val artifacts: List<ScanArtifactReport>,
    val coverage: ScanCoverageReport,
)

@Suppress("DEPRECATION")
internal fun decodeScannerArtifactCoverage(
    data: Bundle,
    complete: Boolean,
    expected: List<ScannerDescriptorClaim>?,
): ScannerArtifactCoverage {
    val present = ARTIFACT_RESULT_KEYS.filter(data::containsKey)
    if (present.isEmpty()) return ScannerArtifactCoverage(false, emptyList(), ScanCoverageReport(requestedArtifacts = expected?.size ?: 0))
    require(present.size == ARTIFACT_RESULT_KEYS.size)
    val values = data.getParcelableArrayList<Bundle>("artifact_reports").orEmpty()
    require(values.size <= 32)
    val artifacts = values.map { row ->
        require(row.keySet() == setOf("source", "kind", "bytes", "complete", "sha256", "declared", "verified", "validated"))
        val source = requireNotNull(row.getString("source")).also { require(it.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) }
        val kind = requireNotNull(row.getString("kind")).also { require(it in setOf("BASE", "SPLIT")) }
        val bytes = row.getLong("bytes", -1).also { require(it in 0..67108864L) }
        val full = row.getBoolean("complete", false)
        val hash = row.getString("sha256")
        require(if (full) hash?.matches(Regex("[a-f0-9]{64}")) == true else hash == null)
        val declared = row.getInt("declared", -1).also { require(it in 0..4096) }
        val verified = row.getInt("verified", -1).also { require(it in 0..declared) }
        expected?.let { claims ->
            val claim = claims.single { it.label == source }
            require(claim.kind.name == kind && bytes <= claim.bytes && (!full || bytes == claim.bytes))
        }
        ScanArtifactReport(source, kind, bytes, full, hash, declared, verified, row.getBoolean("validated", false))
    }
    require(artifacts.map { it.sourceId }.toSet().size == artifacts.size)
    val requested = data.getInt("coverage_requested", -1).also { require(it in 1..32) }
    val opened = data.getInt("coverage_opened", -1).also { require(it in 0..requested) }
    val dexDeclared = data.getInt("dex_declared", -1).also { require(it in 0..4096) }
    val dexCompleted = data.getInt("dex_completed", -1).also { require(it in 0..minOf(dexDeclared, 128)) }
    require(artifacts.size <= requested && (expected == null || requested == expected.size))
    val expanded = data.getLong("expanded_bytes", -1).also { require(it in 0..134217728L) }
    val work = data.getLong("work_units", -1).also { require(it in 0..10000000L) }
    require(!complete || artifacts.size == requested && opened == requested && dexCompleted > 0 && dexCompleted == dexDeclared &&
        artifacts.all { it.inputComplete && it.containerValidated && it.entriesVerified == it.entriesDeclared })
    return ScannerArtifactCoverage(true, artifacts, ScanCoverageReport(requested, opened, dexDeclared, dexCompleted, expanded, work))
}

internal val ARTIFACT_RESULT_KEYS = setOf("artifact_reports", "coverage_requested", "coverage_opened", "dex_declared", "dex_completed")
