package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppTrafficWindow
import java.util.Locale

const val OTHER_PACKAGE = "__other__"
const val MAX_TOP_APPS_PER_BUCKET = 10

data class AppTrafficBucket(
    val bucketStartMs: Long,
    val packageName: String,
    val rxBytes: Long,
    val txBytes: Long,
    val foregroundBytes: Long?,
    val backgroundBytes: Long?,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

fun appTrafficRows(
    windows: List<AppTrafficWindow>,
    labelsByPackage: Map<String, String>,
    anomalyEvents: List<AnomalyEvent>,
    maxRows: Int = MAX_TOP_APPS_PER_BUCKET,
    includeOther: Boolean = true,
): List<AppTrafficRow> {
    val badgesByPackage =
        anomalyEvents
            .groupBy(AnomalyEvent::packageName)
            .mapNotNull { (packageName, events) -> packageName?.let { it to appTrafficBadgesFor(events) } }
            .toMap()
    val rows =
        windows
            .groupBy(AppTrafficWindow::packageName)
            .map { (packageName, packageWindows) ->
                AppTrafficRow(
                    packageName = packageName,
                    label = labelsByPackage[packageName] ?: packageName,
                    txBytes = packageWindows.sumOf { window -> window.txBytes.coerceAtLeast(0L) },
                    rxBytes = packageWindows.sumOf { window -> window.rxBytes.coerceAtLeast(0L) },
                    badges = badgesByPackage[packageName] ?: setOf(AppAnomalyBadge.NORMAL),
                )
            }
            .filter { row -> row.totalBytes > 0L }
            .sortedWith(
                compareByDescending<AppTrafficRow> { row -> row.totalBytes }
                    .thenBy { row -> row.label.lowercase(Locale.getDefault()) },
            )
    if (!includeOther || rows.size <= maxRows) {
        return rows
    }
    val visible = rows.take(maxRows)
    val overflow = rows.drop(maxRows)
    return visible +
        AppTrafficRow(
            packageName = OTHER_PACKAGE,
            label = "Other",
            txBytes = overflow.sumOf(AppTrafficRow::txBytes),
            rxBytes = overflow.sumOf(AppTrafficRow::rxBytes),
            badges = setOf(AppAnomalyBadge.NORMAL),
            quality = ChartDataQuality.PARTIAL,
        )
}

fun appTrafficBuckets(
    windows: List<AppTrafficWindow>,
    startMs: Long,
    endMs: Long,
    bucketSizeMs: Long,
    topPackages: Set<String>,
    fillEmpty: Boolean = true,
): List<AppTrafficBucket> {
    val packages = topPackages.ifEmpty { windows.map(AppTrafficWindow::packageName).toSet() }
    val bucketStarts = if (fillEmpty) bucketStarts(startMs, endMs, bucketSizeMs) else emptyList()
    val buckets = linkedMapOf<Pair<Long, String>, AppTrafficBucket>()
    if (fillEmpty) {
        bucketStarts.forEach { bucketStart ->
            packages.forEach { packageName ->
                buckets[bucketStart to packageName] =
                    AppTrafficBucket(
                        bucketStartMs = bucketStart,
                        packageName = packageName,
                        rxBytes = 0L,
                        txBytes = 0L,
                        foregroundBytes = null,
                        backgroundBytes = null,
                    )
            }
        }
    }
    windows
        .asSequence()
        .filter { window -> window.startedAtMs >= startMs && window.startedAtMs < endMs }
        .forEach { window ->
            val packageName = if (window.packageName in packages) window.packageName else OTHER_PACKAGE
            val bucketStart = alignToBucketStart(window.startedAtMs, bucketSizeMs, startMs)
            val key = bucketStart to packageName
            val current =
                buckets[key]
                    ?: AppTrafficBucket(
                        bucketStartMs = bucketStart,
                        packageName = packageName,
                        rxBytes = 0L,
                        txBytes = 0L,
                        foregroundBytes = null,
                        backgroundBytes = null,
                    )
            val bytes = window.totalBytes.coerceAtLeast(0L)
            buckets[key] =
                current.copy(
                    rxBytes = current.rxBytes + window.rxBytes.coerceAtLeast(0L),
                    txBytes = current.txBytes + window.txBytes.coerceAtLeast(0L),
                    foregroundBytes =
                        if (window.foreground == true) {
                            (current.foregroundBytes ?: 0L) + bytes
                        } else {
                            current.foregroundBytes
                        },
                    backgroundBytes =
                        if (window.foreground == false) {
                            (current.backgroundBytes ?: 0L) + bytes
                        } else {
                            current.backgroundBytes
                        },
                )
        }
    return buckets.values.sortedWith(compareBy(AppTrafficBucket::bucketStartMs, AppTrafficBucket::packageName))
}

private fun appTrafficBadgesFor(events: List<AnomalyEvent>): Set<AppAnomalyBadge> {
    val badges =
        events.flatMap { event ->
            when (event.type) {
                AnomalyType.APP_UPLOAD_SPIKE -> listOf(AppAnomalyBadge.HIGH_UPLOAD)
                AnomalyType.APP_BACKGROUND_TRAFFIC -> listOf(AppAnomalyBadge.BACKGROUND)
                AnomalyType.NEW_DESTINATION_COUNTRY,
                AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH,
                -> listOf(AppAnomalyBadge.NEW_ROUTE)

                else -> listOf(AppAnomalyBadge.UNUSUAL)
            } +
                if (event.severity == AnomalySeverity.HIGH) {
                    listOf(AppAnomalyBadge.UNUSUAL)
                } else {
                    emptyList()
                }
        }.toSet()
    return badges.ifEmpty { setOf(AppAnomalyBadge.NORMAL) }
}
