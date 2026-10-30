package com.foxhole.guard.core.sentinel.scanner

import android.os.Bundle
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReason
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReport
import com.foxhole.guard.sentinel.nativeengine.NativeYaraStatus

internal fun addScannerYaraReply(data: Bundle, staticComplete: Boolean, expectedDexEntries: Int, reports: List<NativeYaraReport>) {
    val complete = completeVerifiedDexYaraCoverage(staticComplete, expectedDexEntries, reports)
    val ruleIds = reports.flatMap { it.matches }.map { it.ruleId }.distinct()
    data.putBoolean("yara_requested", true)
    data.putBoolean("yara_complete", complete && ruleIds.size <= 64)
    data.putString("yara_scope", "VERIFIED_DEX")
    data.putInt("yara_dex_scanned", reports.size)
    data.putInt("yara_dex_expected", expectedDexEntries)
    data.putStringArrayList("yara_statuses", ArrayList(reports.map { it.status.name }.distinct()))
    data.putStringArrayList("yara_reasons", ArrayList(reports.map { it.reason.name }.distinct()))
    data.putStringArrayList("yara_rule_ids", ArrayList(ruleIds.take(64)))
}

internal fun completeVerifiedDexYaraCoverage(staticComplete: Boolean, expectedDexEntries: Int, reports: List<NativeYaraReport>): Boolean =
    staticComplete && expectedDexEntries > 0 && reports.size == expectedDexEntries && reports.all {
        it.status == NativeYaraStatus.COMPLETE && it.reason == NativeYaraReason.SCANNED && it.coverageComplete
    }
