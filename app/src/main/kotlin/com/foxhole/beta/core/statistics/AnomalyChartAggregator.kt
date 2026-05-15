package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity

private const val ANOMALY_SCORE_MAX = 100

data class AnomalyChartPoint(
    val bucketStartAt: Long,
    val score: Int,
    val high: Boolean,
    val notification: Boolean,
    val eventCount: Int,
)

fun anomalyTimelineBuckets(
    events: List<AnomalyEvent>,
    startMs: Long,
    endMs: Long,
    bucketSizeMs: Long,
    fillEmpty: Boolean = true,
): List<AnomalyChartPoint> {
    if (endMs <= startMs) {
        return emptyList()
    }
    val buckets =
        if (fillEmpty) {
            bucketStarts(startMs, endMs, bucketSizeMs).associateWith {
                AnomalyChartPoint(
                    bucketStartAt = it,
                    score = 0,
                    high = false,
                    notification = false,
                    eventCount = 0,
                )
            }.toMutableMap()
        } else {
            linkedMapOf()
        }
    events
        .asSequence()
        .filter { event -> event.createdAtMs >= startMs && event.createdAtMs < endMs }
        .groupBy { event -> alignToBucketStart(event.createdAtMs, bucketSizeMs, startMs) }
        .forEach { (bucketStart, bucketEvents) ->
            buckets[bucketStart] =
                AnomalyChartPoint(
                    bucketStartAt = bucketStart,
                    score = bucketEvents.maxOf(AnomalyEvent::score).coerceIn(0, ANOMALY_SCORE_MAX),
                    high = bucketEvents.any { event -> event.severity == AnomalySeverity.HIGH },
                    notification = bucketEvents.any(AnomalyEvent::notificationShown),
                    eventCount = bucketEvents.size,
                )
        }
    return buckets.values.sortedBy(AnomalyChartPoint::bucketStartAt)
}

fun anomalyScoreChartModel(
    events: List<AnomalyEvent>,
    range: StatsRange,
    startMs: Long,
    endMs: Long,
    bucketSizeMs: Long,
    updatedAtMs: Long,
): ChartModel {
    val points = anomalyTimelineBuckets(events, startMs, endMs, bucketSizeMs)
    return ChartModel(
        id = "anomaly-score",
        title = "Anomaly timeline",
        subtitle = "Score uses real event timestamps; empty buckets are zero",
        range = range,
        xAxis =
            ChartAxis(
                label = "Time",
                min = startMs.toDouble(),
                max = endMs.toDouble(),
                ticks = emptyList(),
                formatter = ChartValueFormatter.TIME,
            ),
        yAxis =
            ChartAxis(
                label = "Score",
                min = 0.0,
                max = ANOMALY_SCORE_MAX.toDouble(),
                ticks =
                    listOf(
                        ChartTick(0.0, "0"),
                        ChartTick(40.0, "40"),
                        ChartTick(70.0, "70"),
                        ChartTick(100.0, "100"),
                    ),
                formatter = ChartValueFormatter.SCORE,
            ),
        series =
            listOf(
                ChartSeries(
                    id = "score",
                    label = "Score",
                    kind = ChartSeriesKind.LINE,
                    colorToken = ChartColorToken.WARNING,
                    points = points.map { point -> ChartPoint(point.bucketStartAt, point.score.toDouble()) },
                ),
                ChartSeries(
                    id = "high",
                    label = "High severity",
                    kind = ChartSeriesKind.EVENT_DOT,
                    colorToken = ChartColorToken.ERROR,
                    points =
                        points
                            .filter(AnomalyChartPoint::high)
                            .map { point -> ChartPoint(point.bucketStartAt, point.score.toDouble()) },
                ),
                ChartSeries(
                    id = "notification",
                    label = "Notification shown",
                    kind = ChartSeriesKind.EVENT_DOT,
                    colorToken = ChartColorToken.SUCCESS,
                    points =
                        points
                            .filter(AnomalyChartPoint::notification)
                            .map { point -> ChartPoint(point.bucketStartAt, point.score.toDouble()) },
                ),
            ),
        legend =
            ChartLegendModel(
                items =
                    listOf(
                        ChartLegendItem("score", "Score", ChartColorToken.WARNING),
                        ChartLegendItem("high", "High", ChartColorToken.ERROR),
                        ChartLegendItem("notification", "Notification", ChartColorToken.SUCCESS),
                    ),
            ),
        emptyState = ChartEmptyState("No anomaly events"),
        updatedAtMs = updatedAtMs,
        quality = ChartDataQuality.REAL,
    )
}

fun timestampToChartX(
    timestampMs: Long,
    rangeStartMs: Long,
    rangeEndMs: Long,
    left: Float,
    right: Float,
): Float {
    val duration = (rangeEndMs - rangeStartMs).coerceAtLeast(1L).toFloat()
    val ratio = ((timestampMs - rangeStartMs).toFloat() / duration).coerceIn(0f, 1f)
    return left + (right - left) * ratio
}
