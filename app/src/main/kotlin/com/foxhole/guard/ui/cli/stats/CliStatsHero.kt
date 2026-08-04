package com.foxhole.guard.ui.cli.stats

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.packages

/**
 * Summary metrics over a fixed 24 hours, ported from the original hero card onto the unified lane
 * model: tor traffic is counted by route scope, either the whole device or the TOR lane's packages.
 * Everything derives from the same per-app windows as the rest of the dashboard.
 */
@Immutable
internal data class CliStatsHero(
    val vpnBytes24h: Long = 0L,
    val torBytes24h: Long = 0L,
    val anomalyCount24h: Int = 0,
    val anomalyTrafficRatio24h: Float = 0f,
)

internal fun cliStatsHero(
    windows: List<AppTrafficWindow>,
    anomalyEvents: List<AnomalyEvent>,
    settings: Settings,
    nowMs: Long,
): CliStatsHero {
    val cutoff = nowMs - DAY_MS
    val samples = windows.filter { it.startedAtMs >= cutoff }
    val totalBytes = samples.sumOf { it.totalBytes.coerceAtLeast(0L) }
    val torBytes = torRoutedBytes(samples, settings)
    val anomalies = anomalyEvents.filter { it.createdAtMs >= cutoff }
    val anomalyPackages = anomalies.mapNotNullTo(mutableSetOf()) { event ->
        event.packageName?.takeIf(String::isNotBlank)
    }
    val anomalyBytes = samples
        .asSequence()
        .filter { it.packageName in anomalyPackages }
        .sumOf { it.totalBytes.coerceAtLeast(0L) }
    return CliStatsHero(
        vpnBytes24h = (totalBytes - torBytes).coerceAtLeast(0L),
        torBytes24h = torBytes,
        anomalyCount24h = anomalies.size,
        anomalyTrafficRatio24h =
        if (totalBytes <= 0L) {
            0f
        } else {
            (anomalyBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        },
    )
}

/**
 * How many of [samples]' bytes the Tor lane carried. The caller decides the window by what it puts
 * in [samples], so the hero's fixed 24 h and the overview's day/week selection share one copy of the
 * lane rule — they were duplicated byte-for-byte and would have drifted on the next routing change.
 */
internal fun torRoutedBytes(
    samples: List<AppTrafficWindow>,
    settings: Settings,
): Long {
    if (!settings.privacyRoute.enabled) {
        return 0L
    }
    if (settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
        return samples.sumOf { it.totalBytes.coerceAtLeast(0L) }
    }
    val torPackages = settings.expert.packages(AppTunnelLane.TOR).toSet()
    if (torPackages.isEmpty()) {
        return 0L
    }
    return samples
        .asSequence()
        .filter { it.packageName in torPackages }
        .sumOf { it.totalBytes.coerceAtLeast(0L) }
}

private const val DAY_MS = 24L * 60L * 60L * 1000L
