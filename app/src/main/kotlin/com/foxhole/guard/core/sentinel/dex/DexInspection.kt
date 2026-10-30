package com.foxhole.guard.core.sentinel.dex

import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.core.sentinel.apk.StaticCoverage

internal enum class DexObservationKind {
    DEX_CLASS_LOADER_REFERENCE, RUNTIME_EXEC_REFERENCE, REFLECTION_REFERENCE,
    NETWORK_API_REFERENCE, CRYPTO_API_REFERENCE, ACCESSIBILITY_API_REFERENCE,
    SMS_API_REFERENCE, PACKAGE_INSTALL_API_REFERENCE, NATIVE_LIBRARY_API_REFERENCE,
}

/** A reference is static presence; it does not prove a call, intent, malware, or an ATT&CK technique. */
internal data class DexReferenceObservation(val kind: DexObservationKind, val count: Int)

internal data class DexCodeSummary(val definedMethods: Int, val codeItems: Int, val instructionUnits: Long, val nativeMethods: Int)

internal data class DexInspection(
    val status: InspectionStatus,
    val reason: InspectionReason?,
    val version: String?,
    val stringCount: Int,
    val typeCount: Int,
    val methodCount: Int,
    val code: DexCodeSummary?,
    val observations: List<DexReferenceObservation>,
    val completedCoverage: Set<StaticCoverage>,
)
