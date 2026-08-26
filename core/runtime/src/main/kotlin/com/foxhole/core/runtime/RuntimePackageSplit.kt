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
