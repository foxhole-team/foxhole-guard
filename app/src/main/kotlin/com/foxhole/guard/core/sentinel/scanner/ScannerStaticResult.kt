package com.foxhole.guard.core.sentinel.scanner

import android.os.Bundle
import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.core.sentinel.apk.StaticCoverage
import com.foxhole.guard.core.sentinel.apk.StaticLimitation
import com.foxhole.guard.core.sentinel.dex.DexObservationKind

internal data class ScannerStaticResult(
    val status: InspectionStatus,
    val coverageComplete: Boolean,
    val coverage: Set<StaticCoverage>,
    val limitations: Set<StaticLimitation>,
    val weakObservations: Set<DexObservationKind>,
    val issues: Set<String>,
    val sourceFingerprint: String?,
    val elapsedMillis: Long?,
    val processId: Int,
    val processUid: Int,
    val yara: ScannerYaraResult? = null,
)

internal fun decodeScannerStaticReply(data: Bundle, senderUid: Int, owner: ScannerRequestOwner): ScannerStaticResult {
    require(isStandardIsolatedScannerUid(senderUid))
    require(data.getInt("protocol", -1) == ScannerIpcCodes.PROTOCOL_VERSION)
    require(data.getString("request_id") == owner.requestId)
    require(data.getString("installation_id") == owner.installationId)
    require(data.getString("rule_epoch") == owner.ruleEpoch)
    val uid = data.getInt("uid", -1)
    val pid = data.getInt("pid", -1)
    require(uid == senderUid && pid > 0)
    val yara = decodeScannerYaraReply(data)
    val status = InspectionStatus.valueOf(requireNotNull(data.getString("status")))
    val complete = data.getBoolean("coverage_complete", false)
    val coverage = boundedScannerCodes(data, "coverage", 3).map(StaticCoverage::valueOf).toSet()
    val limitations = boundedScannerCodes(data, "limitations", 8).map(StaticLimitation::valueOf).toSet()
    val observations = boundedScannerCodes(data, "weak_observations", 9).map(DexObservationKind::valueOf).toSet()
    val issues = boundedScannerCodes(data, "issues", 32).toSet()
    require(issues.all { it in ISSUE_CODES })
    val fingerprint = data.getString(
        "source_fingerprint"
    ).also { require(it == null || it.matches(Regex("[0-9a-f]{64}"))) }
    require(
        !complete || status == InspectionStatus.COMPLETE && coverage == StaticCoverage.entries.toSet() && issues.isEmpty() && fingerprint != null
    )
    require(status != InspectionStatus.COMPLETE || complete)
    require(
        !complete || limitations == StaticLimitation.entries.toSet() && data.getString("engine") == "bounded-apk-dex-2"
    )
    val elapsed = if (data.containsKey(
            "elapsed_ms"
        )
    ) {
        data.getLong("elapsed_ms", -1).also { require(it in 0..60000) }
    } else {
        null
    }
    require(data.keySet().all { it in RESULT_KEYS })
    return ScannerStaticResult(status, complete, coverage, limitations, observations, issues, fingerprint, elapsed, pid, uid, yara)
}

private fun boundedScannerCodes(data: Bundle, key: String, limit: Int): List<String> {
    val values = data.getStringArrayList(key).orEmpty()
    require(values.size <= limit && values.toSet().size == values.size)
    require(values.all { it.matches(Regex("[A-Z_]{1,64}")) })
    return values
}

private val RESULT_KEYS = setOf(
    "protocol", "request_id", "installation_id", "rule_epoch", "status", "engine", "coverage_complete", "coverage",
    "limitations", "issues", "source_fingerprint", "elapsed_ms", "work_units", "expanded_bytes", "weak_observations",
    "yara_requested", "yara_complete", "pid", "uid", "yara_scope", "yara_dex_scanned", "yara_dex_expected",
    "yara_statuses", "yara_reasons", "yara_rule_ids",
)

private val ISSUE_CODES = InspectionReason.entries.map { it.name }.toSet() +
    setOf("INPUT_UNAVAILABLE", "CANCELLED", "OWNER_TIMEOUT", "BUSY", "BAD_REQUEST")
