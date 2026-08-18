package com.foxhole.guard.ui.cli.stats

import com.foxhole.core.model.NetworkActivityEvent

internal data class CliBlockedAppRow(
    val packageName: String,
    val attempts: Int,
)

internal fun cliBlockedAppRows(
    events: List<NetworkActivityEvent>,
    blockedPackages: Collection<String>,
    nowMs: Long,
): List<CliBlockedAppRow> {
    val blocked = blockedPackages.filterTo(mutableSetOf(), String::isNotBlank)
    if (blocked.isEmpty()) return emptyList()
    val cutoff = nowMs - FIREWALL_WINDOW_MS
    return events
        .asSequence()
        .filter { event -> event.timestampMs >= cutoff }
        .mapNotNull { event -> event.packageNames.firstOrNull(blocked::contains) }
        .groupingBy(String::toString)
        .eachCount()
        .map { (packageName, attempts) -> CliBlockedAppRow(packageName, attempts) }
        .sortedByDescending(CliBlockedAppRow::attempts)
}

private const val FIREWALL_WINDOW_MS = 24L * 60L * 60L * 1_000L
