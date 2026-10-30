package com.foxhole.guard.core.sentinel.scanner

import android.os.Bundle
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReason
import com.foxhole.guard.sentinel.nativeengine.NativeYaraStatus

internal data class ScannerYaraResult(
    val verifiedDexComplete: Boolean,
    val scannedDexEntries: Int,
    val expectedDexEntries: Int,
    val statuses: Set<NativeYaraStatus>,
    val reasons: Set<NativeYaraReason>,
    val matchedRuleIds: Set<String>,
)

internal fun decodeScannerYaraReply(data: Bundle): ScannerYaraResult? {
    val requested = data.getBoolean("yara_requested", false)
    val complete = data.getBoolean("yara_complete", false)
    if (!requested) {
        require(!complete)
        return null
    }
    val scanned = data.getInt("yara_dex_scanned", 0)
    val expected = data.getInt("yara_dex_expected", 0)
    require(scanned in 0..128 && expected in 0..128 && scanned <= expected)
    require(data.getString("yara_scope") == "VERIFIED_DEX" || !complete && scanned == 0)
    val statuses = data.getStringArrayList(
        "yara_statuses"
    ).orEmpty().also { require(it.size <= NativeYaraStatus.entries.size) }
        .map(NativeYaraStatus::valueOf).toSet()
    val reasons = data.getStringArrayList(
        "yara_reasons"
    ).orEmpty().also { require(it.size <= NativeYaraReason.entries.size) }
        .map(NativeYaraReason::valueOf).toSet()
    val ids = data.getStringArrayList("yara_rule_ids").orEmpty()
    require(
        ids.size <= 64 && ids.size == ids.toSet().size && ids.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) }
    )
    require(
        !complete || scanned > 0 && scanned == expected && statuses == setOf(NativeYaraStatus.COMPLETE) && reasons == setOf(NativeYaraReason.SCANNED)
    )
    return ScannerYaraResult(complete, scanned, expected, statuses, reasons, ids.toSet())
}
