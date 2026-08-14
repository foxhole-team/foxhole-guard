package com.foxhole.guard.statistics

data class StatsRangePolicy(
    val range: StatsRange,
    val bucketSizeMs: Long,
    val maxBuckets: Int,
    val majorTickCount: Int,
    val minorTickCount: Int,
    val labelMode: TimeLabelMode,
)
