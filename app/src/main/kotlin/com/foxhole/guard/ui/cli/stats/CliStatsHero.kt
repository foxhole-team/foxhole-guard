package com.foxhole.guard.ui.cli.stats

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.TrafficWindow

/**
 * Summary metrics over a fixed 24 hours. VPN/TOR totals come from the same recorded-route buckets
 * as the overview graph, so switching a module off cannot relabel yesterday's traffic.
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
    deviceWindows: List<TrafficWindow>,
    i2pBuckets: List<I2pTrafficBucket> = emptyList(),
    anomalyEvents: List<AnomalyEvent>,
    nowMs: Long,
): CliStatsHero {
    val cutoff = nowMs - DAY_MS
    val samples = windows.filter { it.startedAtMs >= cutoff }
    val totalBytes = samples.sumOf { it.totalBytes.coerceAtLeast(0L) }
    val routeBuckets = cliStatsTrafficBuckets(
        samples = samples,
        windowMs = DAY_MS,
        nowMs = nowMs,
        bucketCount = HERO_BUCKET_COUNT,
        deviceWindows = deviceWindows,
        i2pBuckets = i2pBuckets,
    )
    val anomalies = anomalyEvents.filter { it.createdAtMs >= cutoff }
    val anomalyPackages = anomalies.mapNotNullTo(mutableSetOf()) { event ->
        event.packageName?.takeIf(String::isNotBlank)
    }
    val anomalyBytes = samples
        .asSequence()
        .filter { it.packageName in anomalyPackages }
        .sumOf { it.totalBytes.coerceAtLeast(0L) }
    return CliStatsHero(
        vpnBytes24h = routeBuckets.sumOf { bucket -> bucket.vpnBytes },
        torBytes24h = routeBuckets.sumOf { bucket -> bucket.torBytes },
        anomalyCount24h = anomalies.size,
        anomalyTrafficRatio24h =
        if (totalBytes <= 0L) {
            0f
        } else {
            (anomalyBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        },
    )
}

private const val HERO_BUCKET_COUNT = 24
private const val DAY_MS = 24L * 60L * 60L * 1000L
