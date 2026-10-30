package com.foxhole.guard.core.sentinel.scanner

import android.os.Bundle
import com.foxhole.guard.core.sentinel.apk.ApkInspection
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.core.sentinel.dex.DexObservationKind

internal fun scannerInspectionReply(owner: ScannerRequestOwner, inspection: ApkInspection): Bundle = Bundle().apply {
    putInt("protocol", ScannerIpcCodes.PROTOCOL_VERSION)
    putString("request_id", owner.requestId)
    putString("installation_id", owner.installationId)
    putString("rule_epoch", owner.ruleEpoch)
    putString("status", inspection.status.name)
    putString("engine", inspection.engineVersion)
    putBoolean("coverage_complete", inspection.coverage.complete)
    putStringArrayList("coverage", ArrayList(inspection.coverage.completed.map { it.name }))
    putStringArrayList("limitations", ArrayList(inspection.limitations.map { it.name }))
    putStringArrayList("issues", ArrayList(inspection.issues.map { it.reason.name }.distinct().take(32)))
    putString("source_fingerprint", inspection.sourceFingerprint)
    putLong("elapsed_ms", inspection.elapsedMillis)
    putLong("work_units", inspection.workUnits)
    putLong("expanded_bytes", inspection.expandedBytes)
    val observations = inspection.dexEntries.flatMap { it.inspection.observations }.groupBy { it.kind }
    val weakCodes = DexObservationKind.entries.filter { it in observations }.map { it.name }
    putStringArrayList("weak_observations", ArrayList(weakCodes))
    putBoolean("yara_requested", false)
    putBoolean("yara_complete", false)
}

internal fun scannerUnavailableReply(owner: ScannerRequestOwner, reason: String): Bundle = Bundle().apply {
    require(reason in setOf("INPUT_UNAVAILABLE", "CANCELLED", "OWNER_TIMEOUT", "BUSY", "BAD_REQUEST"))
    putInt("protocol", ScannerIpcCodes.PROTOCOL_VERSION)
    putString("request_id", owner.requestId)
    putString("installation_id", owner.installationId)
    putString("rule_epoch", owner.ruleEpoch)
    putString("status", InspectionStatus.UNAVAILABLE.name)
    putBoolean("coverage_complete", false)
    putStringArrayList("issues", arrayListOf(reason))
    putBoolean("yara_requested", false)
    putBoolean("yara_complete", false)
}
