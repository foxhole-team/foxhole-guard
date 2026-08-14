package com.foxhole.core.model

import androidx.compose.runtime.Immutable

/**
 * Persisted I2P byte accounting — the long-horizon counterpart of the session-scoped
 * [I2pTrafficStats].
 *
 * Two independent totals are kept apart on purpose and must never be added together in the UI:
 * [I2pTrafficTotals.ownBytes] keeps its historical database name but now means the router's full
 * received + sent network total, including bootstrap and tunnel maintenance.
 * [I2pTrafficTotals.transitBytes] is the subset the router reports as forwarded for other people
 * while relay mode is on. The two rows describe different views and must never be added together.
 *
 * The store is bucketed by wall-clock hour: a byte counter carries no per-connection detail, so an
 * hour is fine-grained enough for every period the screen offers and coarse enough that thirty days
 * of history is a few hundred tiny rows.
 */
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

/**
 * Everything the store holds: the retained hourly buckets plus the lifetime aggregate. The screen
 * folds it into [I2pTrafficPeriods] against its own clock, exactly like the other statistics panels
 * slice their raw rows — so every row on the screen moves on the same tick.
 */
@Immutable
data class I2pTrafficHistory(
    val buckets: List<I2pTrafficBucket> = emptyList(),
    val lifetime: I2pTrafficTotals = I2pTrafficTotals(),
)

/**
 * The four periods the statistics section prints, all derived from the same buckets plus the
 * lifetime aggregate. [allTime] is NOT a sum of the buckets: buckets are pruned at thirty days, so
 * the lifetime figure is accumulated separately and survives that pruning.
 */
@Immutable
data class I2pTrafficPeriods(
    val day: I2pTrafficTotals = I2pTrafficTotals(),
    val week: I2pTrafficTotals = I2pTrafficTotals(),
    val month: I2pTrafficTotals = I2pTrafficTotals(),
    val allTime: I2pTrafficTotals = I2pTrafficTotals(),
) {
    /** Nothing was ever recorded — the section renders its empty state instead of four zeros. */
    val isEmpty: Boolean get() = allTime.isEmpty
}

const val I2P_TRAFFIC_BUCKET_MS: Long = 60L * 60L * 1000L

/** 24 h / 7 days / 30 days expressed in whole buckets, which is how the store is queried. */
const val I2P_TRAFFIC_DAY_BUCKETS: Int = 24
const val I2P_TRAFFIC_WEEK_BUCKETS: Int = 7 * I2P_TRAFFIC_DAY_BUCKETS
const val I2P_TRAFFIC_MONTH_BUCKETS: Int = 30 * I2P_TRAFFIC_DAY_BUCKETS

/** Start of the wall-clock hour [nowMs] falls into. Pre-epoch clocks floor towards the epoch. */
fun i2pTrafficBucketStart(nowMs: Long): Long {
    val remainder = nowMs % I2P_TRAFFIC_BUCKET_MS
    return if (remainder >= 0L) nowMs - remainder else nowMs - remainder - I2P_TRAFFIC_BUCKET_MS
}

/**
 * Oldest bucket a period of [buckets] hours still covers: the current (partial) hour plus the
 * [buckets] - 1 whole hours before it. A "last 24 h" row therefore always sums exactly 24 buckets,
 * and the window rolls forward the moment the wall clock crosses an hour boundary.
 */
fun i2pTrafficPeriodCutoffMs(
    nowMs: Long,
    buckets: Int,
): Long = i2pTrafficBucketStart(nowMs) - (buckets.coerceAtLeast(1) - 1) * I2P_TRAFFIC_BUCKET_MS

/**
 * Retention bound for the bucket table: anything older than the longest period on screen can never
 * be shown again, so it is deleted rather than kept "just in case". The lifetime totals are a single
 * row of two counters with no timestamps at all, so they are not history and are not pruned.
 */
fun i2pTrafficRetentionCutoffMs(nowMs: Long): Long =
    i2pTrafficPeriodCutoffMs(nowMs, I2P_TRAFFIC_MONTH_BUCKETS)

/**
 * Folds stored buckets and the lifetime aggregate into the four printed periods.
 *
 * Buckets in the future (a clock that jumped backwards between two samples) are counted into every
 * period rather than dropped: the bytes were really moved, and hiding them would make the periods
 * disagree with the lifetime total for as long as the clock stays behind.
 */
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

/**
 * Delta of a cumulative counter that restarts from zero with its producer (an i2pd router restart,
 * a new tunnel session). A counter that went backwards is treated as a fresh start, so the whole
 * current reading is the delta instead of a negative one — the same rule the runtime's own
 * generation-aware counters use.
 */
fun i2pCumulativeDelta(
    current: Long,
    previous: Long,
): Long = if (current < previous) current.coerceAtLeast(0L) else (current - previous).coerceAtLeast(0L)
