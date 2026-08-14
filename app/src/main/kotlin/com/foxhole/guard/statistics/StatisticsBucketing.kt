package com.foxhole.guard.statistics

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
