package com.foxhole.guard.ui

internal const val TRAFFIC_CHART_MIN_STEP_BYTES_PER_SEC = 1_000L

internal fun trafficChartNiceCeilStep(value: Long): Long {
    var step = TRAFFIC_CHART_MIN_STEP_BYTES_PER_SEC
    while (step < value) {
        step = trafficChartNextStep(step)
    }
    return step
}

private fun trafficChartNextStep(step: Long): Long {
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

internal fun trafficChartColumnRange(
    count: Int,
    columns: Int,
    column: Int,
): IntRange {
    val from = column.toLong() * count / columns
    val to = ((column + 1).toLong() * count / columns - 1).coerceAtLeast(from)
    return from.toInt()..to.toInt().coerceAtMost(count - 1)
}

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

internal fun trafficChartWindowColumns(
    capacitySeconds: Int,
    widthPx: Int,
): Int = minOf(capacitySeconds, widthPx.coerceAtLeast(1))

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

internal fun trafficChartWindowColumnEndX(
    widthPx: Float,
    capacitySeconds: Int,
    columns: Int,
    column: Int,
): Float {
    val slotEnd = trafficChartColumnRange(capacitySeconds, columns, column).last
    return widthPx * (slotEnd + 1).toFloat() / capacitySeconds
}

internal fun trafficChartColumnAnchorX(
    widthPx: Float,
    capacitySeconds: Int,
    columns: Int,
    column: Int,
): Float =
    if (column == 0) 0f else trafficChartWindowColumnEndX(widthPx, capacitySeconds, columns, column)

internal fun trafficChartColumnAgeSeconds(
    capacitySeconds: Int,
    columns: Int,
    column: Int,
): Int = capacitySeconds - 1 - trafficChartColumnRange(capacitySeconds, columns, column).last

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
