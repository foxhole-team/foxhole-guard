package com.foxhole.beta.core.statistics

import kotlin.math.ceil

data class StatsRangePolicy(
    val range: StatsRange,
    val bucketSizeMs: Long,
    val maxBuckets: Int,
    val majorTickCount: Int,
    val minorTickCount: Int,
    val labelMode: TimeLabelMode,
)

fun statsRangePolicy(
    range: StatsRange,
    firstAtMs: Long? = null,
    nowMs: Long,
): StatsRangePolicy =
    when (range) {
        StatsRange.LIVE_15M ->
            StatsRangePolicy(
                range = range,
                bucketSizeMs = 10_000L,
                maxBuckets = 90,
                majorTickCount = 4,
                minorTickCount = 9,
                labelMode = TimeLabelMode.CLOCK,
            )

        StatsRange.HOUR_1 ->
            StatsRangePolicy(
                range = range,
                bucketSizeMs = 60_000L,
                maxBuckets = 60,
                majorTickCount = 5,
                minorTickCount = 10,
                labelMode = TimeLabelMode.CLOCK,
            )

        StatsRange.HOURS_24 ->
            StatsRangePolicy(
                range = range,
                bucketSizeMs = 10L * 60L * 1000L,
                maxBuckets = 144,
                majorTickCount = 5,
                minorTickCount = 12,
                labelMode = TimeLabelMode.CLOCK,
            )

        StatsRange.DAYS_7 ->
            StatsRangePolicy(
                range = range,
                bucketSizeMs = 60L * 60L * 1000L,
                maxBuckets = 168,
                majorTickCount = 7,
                minorTickCount = 24,
                labelMode = TimeLabelMode.DAY_AND_TIME,
            )

        StatsRange.DAYS_31 ->
            StatsRangePolicy(
                range = range,
                bucketSizeMs = 6L * 60L * 60L * 1000L,
                maxBuckets = 124,
                majorTickCount = 6,
                minorTickCount = 4,
                labelMode = TimeLabelMode.DATE,
            )

        StatsRange.MONTHS_3 ->
            StatsRangePolicy(
                range = range,
                bucketSizeMs = 12L * 60L * 60L * 1000L,
                maxBuckets = 186,
                majorTickCount = 6,
                minorTickCount = 2,
                labelMode = TimeLabelMode.DATE,
            )

        StatsRange.ALL -> allRangeBucketPolicy(firstAtMs ?: nowMs, nowMs)
    }

fun allRangeBucketPolicy(
    firstAtMs: Long,
    nowMs: Long,
): StatsRangePolicy {
    val durationMs = (nowMs - firstAtMs).coerceAtLeast(1L)
    val dayMs = 24L * 60L * 60L * 1000L
    val bucketMs =
        when {
            durationMs <= 3L * dayMs -> 60L * 60L * 1000L
            durationMs <= 31L * dayMs -> 6L * 60L * 60L * 1000L
            durationMs <= 93L * dayMs -> 12L * 60L * 60L * 1000L
            durationMs <= 365L * dayMs -> dayMs
            durationMs <= 2L * 365L * dayMs -> 7L * dayMs
            else -> 31L * dayMs
        }
    return StatsRangePolicy(
        range = StatsRange.ALL,
        bucketSizeMs = bucketMs,
        maxBuckets = 180,
        majorTickCount = 6,
        minorTickCount = 4,
        labelMode = if (bucketMs >= 31L * dayMs) TimeLabelMode.MONTH else TimeLabelMode.DATE,
    )
}

fun alignToBucketStart(
    timestampMs: Long,
    bucketSizeMs: Long,
    anchorMs: Long = 0L,
): Long {
    require(bucketSizeMs > 0L) { "bucketSizeMs must be positive" }
    val offset = timestampMs - anchorMs
    return anchorMs + Math.floorDiv(offset, bucketSizeMs) * bucketSizeMs
}

fun bucketStarts(
    startMs: Long,
    endMs: Long,
    bucketSizeMs: Long,
    maxBuckets: Int = Int.MAX_VALUE,
): List<Long> {
    require(bucketSizeMs > 0L) { "bucketSizeMs must be positive" }
    if (endMs <= startMs) {
        return emptyList()
    }
    val alignedStart = alignToBucketStart(startMs, bucketSizeMs)
    val count = ceil((endMs - alignedStart).toDouble() / bucketSizeMs.toDouble()).toInt().coerceAtLeast(1)
    val boundedCount = count.coerceAtMost(maxBuckets.coerceAtLeast(1))
    val first = alignedStart + (count - boundedCount).coerceAtLeast(0) * bucketSizeMs
    return List(boundedCount) { index -> first + index * bucketSizeMs }
}
