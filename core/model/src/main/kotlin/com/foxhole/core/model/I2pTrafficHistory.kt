package com.foxhole.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class I2pTrafficTotals(
    val ownBytes: Long = 0L,
    val transitBytes: Long = 0L,
) {
    operator fun plus(other: I2pTrafficTotals): I2pTrafficTotals =
        I2pTrafficTotals(
            ownBytes = ownBytes + other.ownBytes,
            transitBytes = transitBytes + other.transitBytes,
        )

    val isEmpty: Boolean get() = ownBytes <= 0L && transitBytes <= 0L
}

/** One stored hour of accounting; [hourStartMs] is the bucket's own start, never a sample time. */
@Immutable
data class I2pTrafficBucket(
    val hourStartMs: Long,
    val totals: I2pTrafficTotals,
)

@Immutable
data class I2pTrafficHistory(
    val buckets: List<I2pTrafficBucket> = emptyList(),
    val lifetime: I2pTrafficTotals = I2pTrafficTotals(),
)

@Immutable
data class I2pTrafficPeriods(
    val day: I2pTrafficTotals = I2pTrafficTotals(),
    val week: I2pTrafficTotals = I2pTrafficTotals(),
    val month: I2pTrafficTotals = I2pTrafficTotals(),
    val allTime: I2pTrafficTotals = I2pTrafficTotals(),
) {
    val isEmpty: Boolean get() = allTime.isEmpty
}

const val I2P_TRAFFIC_BUCKET_MS: Long = 60L * 60L * 1000L

const val I2P_TRAFFIC_DAY_BUCKETS: Int = 24
const val I2P_TRAFFIC_WEEK_BUCKETS: Int = 7 * I2P_TRAFFIC_DAY_BUCKETS
const val I2P_TRAFFIC_MONTH_BUCKETS: Int = 30 * I2P_TRAFFIC_DAY_BUCKETS

fun i2pTrafficBucketStart(nowMs: Long): Long {
    val remainder = nowMs % I2P_TRAFFIC_BUCKET_MS
    return if (remainder >= 0L) nowMs - remainder else nowMs - remainder - I2P_TRAFFIC_BUCKET_MS
}

fun i2pTrafficPeriodCutoffMs(
    nowMs: Long,
    buckets: Int,
): Long = i2pTrafficBucketStart(nowMs) - (buckets.coerceAtLeast(1) - 1) * I2P_TRAFFIC_BUCKET_MS

fun i2pTrafficRetentionCutoffMs(nowMs: Long): Long =
    i2pTrafficPeriodCutoffMs(nowMs, I2P_TRAFFIC_MONTH_BUCKETS)

fun i2pTrafficPeriods(
    buckets: List<I2pTrafficBucket>,
    lifetime: I2pTrafficTotals,
    nowMs: Long,
): I2pTrafficPeriods =
    I2pTrafficPeriods(
        day = sumSince(buckets, i2pTrafficPeriodCutoffMs(nowMs, I2P_TRAFFIC_DAY_BUCKETS)),
        week = sumSince(buckets, i2pTrafficPeriodCutoffMs(nowMs, I2P_TRAFFIC_WEEK_BUCKETS)),
        month = sumSince(buckets, i2pTrafficPeriodCutoffMs(nowMs, I2P_TRAFFIC_MONTH_BUCKETS)),
        allTime = lifetime,
    )

private fun sumSince(
    buckets: List<I2pTrafficBucket>,
    cutoffMs: Long,
): I2pTrafficTotals =
    buckets.fold(I2pTrafficTotals()) { accumulated, bucket ->
        if (bucket.hourStartMs >= cutoffMs) accumulated + bucket.totals else accumulated
    }

fun i2pCumulativeDelta(
    current: Long,
    previous: Long,
): Long = if (current < previous) current.coerceAtLeast(0L) else (current - previous).coerceAtLeast(0L)
