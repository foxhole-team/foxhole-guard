package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.TrafficWindow

data class TrafficBucket(
    val bucketStartMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val blockedDns: Int,
    val allowedDns: Int,
    val reconnects: Int,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

fun trafficBuckets(
    windows: List<TrafficWindow>,
    startMs: Long,
    endMs: Long,
    bucketSizeMs: Long,
    fillEmpty: Boolean = true,
): List<TrafficBucket> {
    val buckets =
        if (fillEmpty) {
            bucketStarts(startMs, endMs, bucketSizeMs).associateWith {
                TrafficBucket(
                    bucketStartMs = it,
                    rxBytes = 0L,
                    txBytes = 0L,
                    blockedDns = 0,
                    allowedDns = 0,
                    reconnects = 0,
                )
            }.toMutableMap()
        } else {
            linkedMapOf()
        }
    windows
        .asSequence()
        .filter { window -> window.startedAtMs >= startMs && window.startedAtMs < endMs }
        .forEach { window ->
            val bucketStart = alignToBucketStart(window.startedAtMs, bucketSizeMs, startMs)
            val current =
                buckets[bucketStart]
                    ?: TrafficBucket(
                        bucketStartMs = bucketStart,
                        rxBytes = 0L,
                        txBytes = 0L,
                        blockedDns = 0,
                        allowedDns = 0,
                        reconnects = 0,
                    )
            buckets[bucketStart] =
                current.copy(
                    rxBytes = current.rxBytes + window.rxBytes.coerceAtLeast(0L),
                    txBytes = current.txBytes + window.txBytes.coerceAtLeast(0L),
                    blockedDns = current.blockedDns + window.blockedDns.coerceAtLeast(0),
                    allowedDns = current.allowedDns + window.allowedDns.coerceAtLeast(0),
                    reconnects = current.reconnects + window.reconnects.coerceAtLeast(0),
                )
        }
    return buckets.values.sortedBy(TrafficBucket::bucketStartMs)
}

fun trafficChartModel(
    id: String,
    title: String,
    range: StatsRange,
    buckets: List<TrafficBucket>,
    startMs: Long,
    endMs: Long,
    updatedAtMs: Long,
): ChartModel {
    val yMax = niceBytesScale(buckets.maxOfOrNull(TrafficBucket::totalBytes) ?: 1L)
    return ChartModel(
        id = id,
        title = title,
        subtitle = "Volume per bucket",
        range = range,
        xAxis =
            ChartAxis(
                label = "Time",
                min = startMs.toDouble(),
                max = endMs.toDouble(),
                ticks = timeTicks(startMs, endMs, 5),
                formatter = ChartValueFormatter.TIME,
            ),
        yAxis =
            ChartAxis(
                label = "Bytes",
                min = 0.0,
                max = yMax.toDouble(),
                ticks =
                    listOf(
                        ChartTick(0.0, "0"),
                        ChartTick(yMax / 2.0, "50%"),
                        ChartTick(yMax.toDouble(), "Max"),
                    ),
                formatter = ChartValueFormatter.BYTES,
            ),
        series =
            listOf(
                ChartSeries(
                    id = "rx",
                    label = "Received",
                    kind = ChartSeriesKind.STACKED_BAR,
                    colorToken = ChartColorToken.RX,
                    points = buckets.map { bucket -> ChartPoint(x = bucket.bucketStartMs, y = bucket.rxBytes.toDouble()) },
                ),
                ChartSeries(
                    id = "tx",
                    label = "Sent",
                    kind = ChartSeriesKind.STACKED_BAR,
                    colorToken = ChartColorToken.TX,
                    points = buckets.map { bucket -> ChartPoint(x = bucket.bucketStartMs, y = bucket.txBytes.toDouble()) },
                ),
            ),
        legend =
            ChartLegendModel(
                items =
                    listOf(
                        ChartLegendItem("rx", "Received", ChartColorToken.RX),
                        ChartLegendItem("tx", "Sent", ChartColorToken.TX),
                    ),
            ),
        emptyState =
            ChartEmptyState(
                title = "No traffic yet",
                message = "Traffic windows will appear after sampling is collected.",
            ),
        updatedAtMs = updatedAtMs,
    )
}

private fun timeTicks(
    startMs: Long,
    endMs: Long,
    count: Int,
): List<ChartTick> {
    val boundedCount = count.coerceAtLeast(2)
    val duration = (endMs - startMs).coerceAtLeast(1L)
    return List(boundedCount) { index ->
        val ratio = index.toDouble() / (boundedCount - 1).toDouble()
        val value = startMs + (duration * ratio).toLong()
        ChartTick(
            value = value.toDouble(),
            label = if (index == boundedCount - 1) "Now" else "",
            major = true,
        )
    }
}
