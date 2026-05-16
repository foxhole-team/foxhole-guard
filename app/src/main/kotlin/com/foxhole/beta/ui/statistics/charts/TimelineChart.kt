package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel
import com.foxhole.beta.core.statistics.ChartSeriesKind
import com.foxhole.beta.core.statistics.timestampToChartX

data class ChartInteractions(
    val tooltipEnabled: Boolean = true,
) {
    companion object {
        val Default = ChartInteractions()
    }
}

@Composable
fun TimelineChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
    interactions: ChartInteractions = ChartInteractions.Default,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val colorsBySeries = model.series.associate { series -> series.id to chartColor(series.colorToken) }
    ChartScaffold(model = model, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .semantics { contentDescription = model.accessibilitySummary() },
        ) {
            val left = 2.dp.toPx()
            val right = size.width - 2.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val chartHeight = (bottom - top).coerceAtLeast(1f)
            val yMax = model.yAxis.max.coerceAtLeast(1.0)
            listOf(0f, 0.5f, 1f).forEach { ratio ->
                val y = bottom - chartHeight * ratio
                drawLine(
                    color = gridColor,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            drawLine(
                color = axisColor,
                start = Offset(left, bottom),
                end = Offset(right, bottom),
                strokeWidth = 1.dp.toPx(),
            )
            model.series.forEach { series ->
                val color = colorsBySeries[series.id] ?: return@forEach
                when (series.kind) {
                    ChartSeriesKind.LINE,
                    ChartSeriesKind.AREA,
                    -> {
                        val offsets =
                            series.points.map { point ->
                                Offset(
                                    x =
                                    timestampToChartX(
                                        timestampMs = point.x,
                                        rangeStartMs = model.xAxis.min.toLong(),
                                        rangeEndMs = model.xAxis.max.toLong(),
                                        left = left,
                                        right = right,
                                    ),
                                    y = bottom - chartHeight * (point.y / yMax).toFloat().coerceIn(0f, 1f),
                                )
                            }
                        offsets.zipWithNext().forEach { (start, end) ->
                            drawLine(
                                color = color,
                                start = start,
                                end = end,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }

                    ChartSeriesKind.BAR,
                    ChartSeriesKind.STACKED_BAR,
                    -> {
                        val slotWidth =
                            ((right - left) / series.points.size.coerceAtLeast(1).toFloat())
                                .coerceAtLeast(1.dp.toPx())
                        val barWidth = (slotWidth * 0.28f).coerceIn(1.dp.toPx(), 7.dp.toPx())
                        series.points.forEach { point ->
                            val x =
                                timestampToChartX(
                                    timestampMs = point.x,
                                    rangeStartMs = model.xAxis.min.toLong(),
                                    rangeEndMs = model.xAxis.max.toLong(),
                                    left = left,
                                    right = right,
                                )
                            val barHeight = chartHeight * (point.y / yMax).toFloat().coerceIn(0f, 1f)
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(x - barWidth / 2f, bottom - barHeight),
                                size = Size(barWidth, barHeight.coerceAtLeast(if (point.y > 0.0) 1.5f else 0f)),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                            )
                        }
                    }

                    ChartSeriesKind.EVENT_DOT -> {
                        series.points.forEach { point ->
                            drawCircle(
                                color = color,
                                radius = 4.dp.toPx(),
                                center =
                                Offset(
                                    x =
                                    timestampToChartX(
                                        timestampMs = point.x,
                                        rangeStartMs = model.xAxis.min.toLong(),
                                        rangeEndMs = model.xAxis.max.toLong(),
                                        left = left,
                                        right = right,
                                    ),
                                    y = bottom - chartHeight * (point.y / yMax).toFloat().coerceIn(0f, 1f),
                                ),
                            )
                        }
                    }

                    ChartSeriesKind.RANGE_BAR -> Unit
                }
            }
            if (!interactions.tooltipEnabled) {
                return@Canvas
            }
        }
    }
}
