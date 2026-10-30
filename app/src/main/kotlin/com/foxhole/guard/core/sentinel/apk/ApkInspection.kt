package com.foxhole.guard.core.sentinel.apk

import com.foxhole.guard.core.sentinel.dex.DexInspection
import java.io.File
import java.io.InputStream

internal enum class ApkSourceKind { BASE, SPLIT }

/** The opener transfers stream ownership to this invocation; package identity remains host-owned. */
internal data class ApkScanInput(val sourceId: String, val kind: ApkSourceKind, val open: () -> InputStream) {
    companion object {
        fun fromFile(sourceId: String, kind: ApkSourceKind, file: File): ApkScanInput =
            ApkScanInput(sourceId, kind) { file.inputStream() }
    }
}

internal fun interface ScanCancellation {
    fun isCancelled(): Boolean
}

internal enum class InspectionStatus { COMPLETE, PARTIAL, UNSUPPORTED, UNAVAILABLE, INCONCLUSIVE }

internal enum class InspectionReason {
    INVALID_INPUT_SET, CANCELLED, DEADLINE_REACHED, WORK_LIMIT, INPUT_BYTE_LIMIT,
    INPUT_UNAVAILABLE, ENTRY_COUNT_LIMIT, ENTRY_BYTE_LIMIT, TOTAL_EXPANDED_BYTE_LIMIT,
    COMPRESSION_RATIO_LIMIT, DEX_ENTRY_LIMIT, ZIP_TRUNCATED_OR_INVALID, ZIP_UNSAFE_PATH,
    ZIP_DUPLICATE_ENTRY, ZIP_HEADER_MISMATCH, ZIP_OVERLAPPING_ENTRY, ZIP_CRC_MISMATCH,
    ZIP_ENCRYPTED, ZIP64_UNSUPPORTED, ZIP_FEATURE_UNSUPPORTED, ZIP_COMPRESSION_UNSUPPORTED,
    ZIP_DEFLATE_INVALID, NO_DEX_PRESENT, NONSTANDARD_DEX_LOCATION,
    DEX_BAD_MAGIC, DEX_VERSION_UNSUPPORTED, DEX_ENDIAN_UNSUPPORTED,
    DEX_DECLARED_SIZE_MISMATCH, DEX_INVALID_HEADER, DEX_CHECKSUM_MISMATCH,
    DEX_SIGNATURE_MISMATCH, DEX_INVALID_RANGE, DEX_TABLE_LIMIT, DEX_INVALID_MAP,
    DEX_INVALID_INDEX, DEX_INVALID_STRING, DEX_INVALID_CLASS_DATA, DEX_INVALID_CODE,
    DEX_FEATURE_UNSUPPORTED, DEX_STRING_LIMIT,
}

internal enum class StaticCoverage { ZIP_ENTRY_INTEGRITY, DEX_REFERENCE_TABLES, DEX_CODE_LAYOUT }

internal enum class StaticLimitation {
    APK_SIGNER_NOT_VERIFIED, BYTECODE_SEMANTICS_NOT_ANALYZED, NATIVE_BEHAVIOR_NOT_ANALYZED,
    DYNAMIC_CODE_NOT_VISIBLE, ANNOTATIONS_AND_DEBUG_INFO_NOT_INTERPRETED,
    CALL_SITES_AND_METHOD_HANDLES_NOT_INTERPRETED, NO_YARA_ENGINE,
    UNREFERENCED_CONTAINER_BYTES_NOT_ANALYZED,
}

internal data class InspectionIssue(val reason: InspectionReason, val sourceId: String? = null, val dexName: String? = null)

internal data class ArtifactInspection(
    val sourceId: String,
    val kind: ApkSourceKind,
    val inputBytes: Long,
    val inputComplete: Boolean,
    val sha256: String?,
    val entriesDeclared: Int,
    val entriesVerified: Int,
    val containerValidated: Boolean,
)

internal data class ApkDexInspection(val sourceId: String, val dexName: String, val sha256: String, val inspection: DexInspection)

internal data class InspectionCoverage(
    val requested: Set<StaticCoverage>,
    val completed: Set<StaticCoverage>,
    val requestedArtifacts: Int,
    val openedArtifacts: Int,
    val declaredDexEntries: Int,
    val completedDexEntries: Int,
    val complete: Boolean,
)

internal data class ApkInspection(
    val engineVersion: String,
    val status: InspectionStatus,
    val artifacts: List<ArtifactInspection>,
    val dexEntries: List<ApkDexInspection>,
    val issues: List<InspectionIssue>,
    val coverage: InspectionCoverage,
    val limitations: Set<StaticLimitation>,
    val sourceFingerprint: String?,
    val elapsedMillis: Long,
    val expandedBytes: Long,
    val workUnits: Long,
) {
    val cacheEligible: Boolean get() = status == InspectionStatus.COMPLETE && coverage.complete && sourceFingerprint != null
}
