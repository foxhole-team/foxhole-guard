package com.foxhole.core.runtime

internal fun Iterable<String>.stablePackageHash(): Int =
    map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .joinToString(separator = "|")
        .hashCode()

internal data class VpnPackageSplitApplyCounts(
    val appliedCount: Int,
    val skippedCount: Int,
)

/**
 * Missing packages cannot emit traffic. Include mode fails closed only when none of the requested
 * applications can be applied; exclude mode stays safe when an application was uninstalled.
 */
internal fun shouldFailClosedVpnPackageSplit(
    includeMode: Boolean,
    requestedCount: Int,
    counts: VpnPackageSplitApplyCounts,
): Boolean {
    if (requestedCount <= 0) {
        return false
    }
    if (counts.appliedCount + counts.skippedCount < requestedCount) {
        return true
    }
    return includeMode && counts.appliedCount == 0
}
