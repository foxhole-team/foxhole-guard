package com.foxhole.guard.core.sentinel.apk

/** Ceilings constrain owned buffers and work, not RSS or the duration of a blocking caller stream. */
internal data class ScanBudget(
    val maxArtifactBytes: Int = 16 * 1024 * 1024,
    val maxEntryBytes: Int = 16 * 1024 * 1024,
    val maxTotalExpandedBytes: Long = 64L * 1024 * 1024,
    val maxArtifacts: Int = 32,
    val maxZipEntries: Int = 4096,
    val maxDexEntries: Int = 128,
    val maxCompressionRatio: Int = 200,
    val maxTableItems: Int = 100_000,
    val maxStringChars: Int = 4096,
    val maxTotalStringChars: Long = 2L * 1024 * 1024,
    val maxWorkUnits: Long = 2_000_000,
    val maxElapsedMillis: Long = 5000,
) {
    init {
        require(maxArtifactBytes in 1..64 * 1024 * 1024)
        require(maxEntryBytes in 1..16 * 1024 * 1024)
        require(maxTotalExpandedBytes in 1..128L * 1024 * 1024)
        require(maxArtifacts in 1..32 && maxZipEntries in 1..4096 && maxDexEntries in 1..128)
        require(maxCompressionRatio in 1..200)
        require(maxTableItems in 1..100_000 && maxStringChars in 1..4096)
        require(maxTotalStringChars in 1..2L * 1024 * 1024)
        require(maxWorkUnits in 1..10_000_000 && maxElapsedMillis in 1..60_000)
    }
}

internal class ScanControl(
    val budget: ScanBudget,
    private val cancellation: ScanCancellation,
    private val nanoTime: () -> Long,
) {
    private val started = nanoTime()
    var workUnits: Long = 0
        private set
    var expandedBytes: Long = 0
        private set
    private var stringChars: Long = 0

    fun checkpoint(units: Long = 1) {
        if (cancellation.isCancelled()) stopInspection(InspectionReason.CANCELLED)
        if (elapsedMillis() >= budget.maxElapsedMillis) stopInspection(InspectionReason.DEADLINE_REACHED)
        if (units < 0 || units > budget.maxWorkUnits - workUnits) stopInspection(InspectionReason.WORK_LIMIT)
        workUnits += units
    }

    fun reserveExpanded(bytes: Long) {
        checkpoint()
        if (bytes < 0 || bytes > budget.maxTotalExpandedBytes - expandedBytes) {
            stopInspection(
                InspectionReason.TOTAL_EXPANDED_BYTE_LIMIT
            )
        }
        expandedBytes += bytes
    }

    fun reserveStringChars(chars: Long) {
        checkpoint()
        if (chars > budget.maxStringChars || chars > budget.maxTotalStringChars - stringChars) {
            stopInspection(
                InspectionReason.DEX_STRING_LIMIT
            )
        }
        stringChars += chars
    }

    fun elapsedMillis(): Long = (nanoTime() - started).coerceAtLeast(0) / 1_000_000
}

internal class InspectionStopped(val reason: InspectionReason, val status: InspectionStatus) : RuntimeException()

internal fun stopInspection(reason: InspectionReason, status: InspectionStatus = InspectionStatus.PARTIAL): Nothing =
    throw InspectionStopped(reason, status)
