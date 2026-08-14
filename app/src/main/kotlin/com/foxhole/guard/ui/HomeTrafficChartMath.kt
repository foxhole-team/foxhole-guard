package com.foxhole.guard.ui

// Pure math for the traffic widget's live chart: the nice-step scale with downward hysteresis
// and the per-pixel column bucketing. Kept free of Compose so it unit-tests directly.

internal const val TRAFFIC_CHART_MIN_STEP_BYTES_PER_SEC = 1_000L

/** The smallest 1/2/5×10ⁿ step (≥ 1 KB/s) that covers [value] — the chart's full-scale value. */
internal fun trafficChartNiceCeilStep(value: Long): Long {
    var step = TRAFFIC_CHART_MIN_STEP_BYTES_PER_SEC
    while (step < value) {
        step = trafficChartNextStep(step)
    }
    return step
}

private fun trafficChartNextStep(step: Long): Long {
    // 1 → 2 → 5 → 10 …: the leading digit cycles, the magnitude carries.
    var magnitude = 1L
    var lead = step
    while (lead >= 10L) {
        lead /= 10L
        magnitude *= 10L
    }
    return when (lead) {
        1L -> 2L * magnitude
        2L -> 5L * magnitude
        else -> 10L * magnitude
    }
}

/**
 * The chart's full-scale step with hysteresis: growing snaps immediately (clipping is worse than
 * a jump), shrinking waits for [TRAFFIC_CHART_SCALE_SHRINK_STREAK] consecutive updates below the
 * smaller step — one quiet second after a burst must not make the whole chart leap.
 */
internal class TrafficChartScale {
    var step: Long = TRAFFIC_CHART_MIN_STEP_BYTES_PER_SEC
        private set
    private var shrinkStreak = 0

    fun update(windowMax: Long): Long {
        val target = trafficChartNiceCeilStep(windowMax)
        when {
            target > step -> {
                step = target
                shrinkStreak = 0
            }
            target < step -> {
                shrinkStreak++
                if (shrinkStreak >= TRAFFIC_CHART_SCALE_SHRINK_STREAK) {
                    step = target
                    shrinkStreak = 0
                }
            }
            else -> shrinkStreak = 0
        }
        return step
    }
}

internal const val TRAFFIC_CHART_SCALE_SHRINK_STREAK = 3

/**
 * The sample range one pixel column covers, oldest-first: [count] samples split across [columns]
 * columns, column [column] owning a non-empty contiguous slice (bucket-max absorbs windows wider
 * than the pixel raster).
 */
internal fun trafficChartColumnRange(
    count: Int,
    columns: Int,
    column: Int,
): IntRange {
    val from = column.toLong() * count / columns
    val to = ((column + 1).toLong() * count / columns - 1).coerceAtLeast(from)
    return from.toInt()..to.toInt().coerceAtMost(count - 1)
}

/** Bucket-max of one lane over [range] (oldest-first sample indices). */
internal fun TrafficChartHistory.laneMaxIn(
    lane: Int,
    range: IntRange,
): Long {
    var max = 0L
    for (index in range) {
        val value = valueAt(lane, index)
        if (value > max) max = value
    }
    return max
}

/**
 * Bucket-max of the VPN lane over [range]: the device total minus the TOR and I2P lanes, clamped
 * at zero per sample (the lanes are booked by different trackers and can momentarily disagree).
 */
internal fun TrafficChartHistory.vpnLaneMaxIn(
    rx: Boolean,
    range: IntRange,
): Long {
    val totalLane = if (rx) TRAFFIC_CHART_LANE_TOTAL_RX else TRAFFIC_CHART_LANE_TOTAL_TX
    val torLane = if (rx) TRAFFIC_CHART_LANE_TOR_RX else TRAFFIC_CHART_LANE_TOR_TX
    val i2pLane = if (rx) TRAFFIC_CHART_LANE_I2P_RX else TRAFFIC_CHART_LANE_I2P_TX
    var max = 0L
    for (index in range) {
        val value =
            (valueAt(totalLane, index) - valueAt(torLane, index) - valueAt(i2pLane, index))
                .coerceAtLeast(0L)
        if (value > max) max = value
    }
    return max
}

// ── Fixed time window (the X axis always spans the full capacity; data hugs the right edge) ──

/** Columns the fixed window renders: one per pixel, capped by capacity — independent of fill. */
internal fun trafficChartWindowColumns(
    capacitySeconds: Int,
    widthPx: Int,
): Int = minOf(capacitySeconds, widthPx.coerceAtLeast(1))

/**
 * The oldest-first SAMPLE range column [column] covers, or null while the column still precedes
 * the data: the window's slots span the full capacity and the samples occupy its newest
 * [sampleCount] slots, so the trace rides the right edge from the very first second.
 */
internal fun trafficChartWindowSampleRange(
    capacitySeconds: Int,
    sampleCount: Int,
    columns: Int,
    column: Int,
): IntRange? {
    if (sampleCount == 0) return null
    val slots = trafficChartColumnRange(capacitySeconds, columns, column)
    val dataStartSlot = capacitySeconds - sampleCount
    val from = maxOf(slots.first, dataStartSlot)
    if (from > slots.last) return null
    return (from - dataStartSlot)..(slots.last - dataStartSlot)
}

/** X of a column's right edge: the newest column ends exactly at the window's right edge. */
internal fun trafficChartWindowColumnEndX(
    widthPx: Float,
    capacitySeconds: Int,
    columns: Int,
    column: Int,
): Float {
    val slotEnd = trafficChartColumnRange(capacitySeconds, columns, column).last
    return widthPx * (slotEnd + 1).toFloat() / capacitySeconds
}

/**
 * X where a column's VERTEX is plotted: the trace anchors column 0 at the left edge (its path
 * starts at x=0), every later column at its right edge — the crosshair and the tooltip must
 * anchor to the same spot or they float one column-width off the plotted line.
 */
internal fun trafficChartColumnAnchorX(
    widthPx: Float,
    capacitySeconds: Int,
    columns: Int,
    column: Int,
): Float =
    if (column == 0) 0f else trafficChartWindowColumnEndX(widthPx, capacitySeconds, columns, column)

/** Seconds back from "now" that [column] shows (0 = the newest slot at the right edge). */
internal fun trafficChartColumnAgeSeconds(
    capacitySeconds: Int,
    columns: Int,
    column: Int,
): Int = capacitySeconds - 1 - trafficChartColumnRange(capacitySeconds, columns, column).last

/**
 * The column under pointer [x], clamped into the data region; null while the ring is empty.
 * Integer division drifts the first guess by at most one column either way — the walk settles it.
 */
internal fun trafficChartColumnAt(
    capacitySeconds: Int,
    sampleCount: Int,
    columns: Int,
    widthPx: Float,
    x: Float,
): Int? {
    if (sampleCount == 0 || widthPx <= 0f) return null
    val slot =
        ((x / widthPx) * capacitySeconds).toInt()
            .coerceIn(capacitySeconds - sampleCount, capacitySeconds - 1)
    return trafficChartColumnForSlot(capacitySeconds, columns, slot)
}

/** The column showing [ageSeconds] (clamped into the data region); null while the ring is empty. */
internal fun trafficChartColumnForAge(
    capacitySeconds: Int,
    sampleCount: Int,
    columns: Int,
    ageSeconds: Int,
): Int? {
    if (sampleCount == 0) return null
    val slot = capacitySeconds - 1 - ageSeconds.coerceIn(0, sampleCount - 1)
    return trafficChartColumnForSlot(capacitySeconds, columns, slot)
}

private fun trafficChartColumnForSlot(
    capacitySeconds: Int,
    columns: Int,
    slot: Int,
): Int {
    var column = (slot.toLong() * columns / capacitySeconds).toInt().coerceIn(0, columns - 1)
    while (column > 0 && slot < trafficChartColumnRange(capacitySeconds, columns, column).first) {
        column--
    }
    while (column < columns - 1 && slot > trafficChartColumnRange(capacitySeconds, columns, column).last) {
        column++
    }
    return column
}

/**
 * «−m:ss» (or «−h:mm:ss» once the window reaches hours) label for [ageSeconds]; null at zero —
 * the caller names the right edge "now" in the UI language.
 */
internal fun trafficChartAgeLabel(ageSeconds: Int): String? {
    if (ageSeconds <= 0) return null
    val minutes = ageSeconds / SECONDS_PER_MINUTE_INT
    val seconds = ageSeconds % SECONDS_PER_MINUTE_INT
    return if (minutes < MINUTES_PER_HOUR_INT) {
        "−%d:%02d".format(java.util.Locale.US, minutes, seconds)
    } else {
        "−%d:%02d:%02d".format(
            java.util.Locale.US,
            minutes / MINUTES_PER_HOUR_INT,
            minutes % MINUTES_PER_HOUR_INT,
            seconds,
        )
    }
}

private const val SECONDS_PER_MINUTE_INT = 60
private const val MINUTES_PER_HOUR_INT = 60
