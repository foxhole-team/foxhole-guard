package com.foxhole.guard.ui.cli.stats

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.TrafficWindow

@Immutable
internal data class CliStatsHero(
    val vpnBytes24h: Long = 0L,
    val torBytes24h: Long = 0L,
)

internal fun cliStatsHero(
    windows: List<AppTrafficWindow>,
    deviceWindows: List<TrafficWindow>,
    i2pBuckets: List<I2pTrafficBucket> = emptyList(),
    nowMs: Long,
): CliStatsHero {
    val cutoff = nowMs - DAY_MS
    val samples = windows.filter { it.startedAtMs >= cutoff }
    val routeBuckets = cliStatsTrafficBuckets(
        samples = samples,
        windowMs = DAY_MS,
        nowMs = nowMs,
        bucketCount = HERO_BUCKET_COUNT,
        deviceWindows = deviceWindows,
        i2pBuckets = i2pBuckets,
    )
    return CliStatsHero(
        vpnBytes24h = routeBuckets.sumOf { bucket -> bucket.vpnBytes },
        torBytes24h = routeBuckets.sumOf { bucket -> bucket.torBytes },
    )
}

private const val HERO_BUCKET_COUNT = 24
private const val DAY_MS = 24L * 60L * 60L * 1000L
