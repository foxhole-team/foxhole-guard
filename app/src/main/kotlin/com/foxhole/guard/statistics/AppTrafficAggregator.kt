package com.foxhole.guard.statistics

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppTrafficWindow
import java.util.Locale

const val OTHER_PACKAGE = "__other__"
const val MAX_TOP_APPS_PER_BUCKET = 10

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
