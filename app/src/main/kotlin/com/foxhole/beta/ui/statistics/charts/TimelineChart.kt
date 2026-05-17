package com.foxhole.beta.ui.statistics.charts

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel
import com.foxhole.beta.core.statistics.ChartSeries
import com.foxhole.beta.core.statistics.ChartSeriesKind
import com.foxhole.beta.core.statistics.timestampToChartX

@Composable
fun TimelineChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
) {
    val tokens = chartVisualTokens()
    val colorsBySeries = model.series.associate { series -> series.id to chartColor(series.colorToken) }
    val visible = remember(model.id) { mutableStateOf(false) }
    LaunchedEffect(model.id) {
        visible.value = true
    }
    val entranceProgress by animateFloatAsState(
        targetValue = if (visible.value) 1f else 0f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "timeline_chart_entrance",
    )
    ChartScaffold(model = model, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = model.accessibilitySummary() },
        ) {
            val geometry =
                TimelineChartGeometry(
                    left = 2.dp.toPx(),
                    right = size.width - 2.dp.toPx(),
                    top = 10.dp.toPx(),
                    bottom = size.height - 18.dp.toPx(),
                    yMax = model.yAxis.max.coerceAtLeast(1.0),
                )
            drawTimelineGrid(tokens, geometry)
            model.series.forEach { series ->
                val color = colorsBySeries[series.id] ?: return@forEach
                drawTimelineSeries(
                    model = model,
                    series = series,
                    color = color,
                    tokens = tokens,
                    geometry = geometry,
                    entranceProgress = entranceProgress,
                )
            }
        }
    }
}

private data class TimelineChartGeometry(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val yMax: Double,
) {
    val chartHeight: Float = (bottom - top).coerceAtLeast(1f)
}

private fun DrawScope.drawTimelineGrid(
    tokens: ChartTokens,
    geometry: TimelineChartGeometry,
) {
    listOf(0f, 0.5f, 1f).forEach { ratio ->
        val y = geometry.bottom - geometry.chartHeight * ratio
        drawLine(
            color = tokens.gridColor,
            start = Offset(geometry.left, y),
            end = Offset(geometry.right, y),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
    }
    drawLine(
        color = tokens.axisColor,
        start = Offset(geometry.left, geometry.bottom),
        end = Offset(geometry.right, geometry.bottom),
        strokeWidth = 1.dp.toPx(),
    )
}

private fun DrawScope.drawTimelineSeries(
    model: ChartModel,
    series: ChartSeries,
    color: Color,
    tokens: ChartTokens,
    geometry: TimelineChartGeometry,
    entranceProgress: Float,
) {
    when (series.kind) {
        ChartSeriesKind.LINE,
        ChartSeriesKind.AREA,
        -> drawLineOrAreaSeries(model, series, color, tokens, geometry, entranceProgress)

        ChartSeriesKind.BAR,
        ChartSeriesKind.STACKED_BAR,
        -> drawBarSeries(model, series, color, tokens, geometry, entranceProgress)

        ChartSeriesKind.EVENT_DOT -> drawEventDots(model, series, color, geometry, entranceProgress)
        ChartSeriesKind.RANGE_BAR -> Unit
    }
}

private fun DrawScope.drawLineOrAreaSeries(
    model: ChartModel,
    series: ChartSeries,
    color: Color,
    tokens: ChartTokens,
    geometry: TimelineChartGeometry,
    entranceProgress: Float,
) {
    val offsets =
        series.points.map { point ->
            timelineOffset(
                model = model,
                timestampMs = point.x,
                value = point.y,
                geometry = geometry,
                entranceProgress = entranceProgress,
            )
        }
    if (series.kind == ChartSeriesKind.AREA && offsets.isNotEmpty()) {
        drawPath(
            path = areaPath(offsets, geometry.bottom),
            color = color.copy(alpha = 0.18f),
        )
    }
    drawPath(
        path = linePath(offsets),
        color = color,
        style =
        Stroke(
            width = tokens.lineStrokeWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun DrawScope.drawBarSeries(
    model: ChartModel,
    series: ChartSeries,
    color: Color,
    tokens: ChartTokens,
    geometry: TimelineChartGeometry,
    entranceProgress: Float,
) {
    val slotWidth =
        ((geometry.right - geometry.left) / series.points.size.coerceAtLeast(1).toFloat())
            .coerceAtLeast(1.dp.toPx())
    val barWidth = (slotWidth * 0.28f).coerceIn(1.dp.toPx(), 7.dp.toPx())
    val radius = tokens.barCornerRadius.toPx()
    series.points.forEach { point ->
        val x = chartX(model, point.x, geometry)
        val barHeight = geometry.chartHeight * chartYRatio(point.y, geometry) * entranceProgress
        drawRoundRect(
            color = color,
            topLeft = Offset(x - barWidth / 2f, geometry.bottom - barHeight),
            size = Size(barWidth, barHeight.coerceAtLeast(if (point.y > 0.0) 1.5f else 0f)),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

private fun DrawScope.drawEventDots(
    model: ChartModel,
    series: ChartSeries,
    color: Color,
    geometry: TimelineChartGeometry,
    entranceProgress: Float,
) {
    series.points.forEach { point ->
        drawCircle(
            color = color,
            radius = 4.dp.toPx(),
            center = timelineOffset(
                model = model,
                timestampMs = point.x,
                value = point.y,
                geometry = geometry,
                entranceProgress = entranceProgress,
            ),
        )
    }
}

private fun timelineOffset(
    model: ChartModel,
    timestampMs: Long,
    value: Double,
    geometry: TimelineChartGeometry,
    entranceProgress: Float,
): Offset =
    Offset(
        x = chartX(model, timestampMs, geometry),
        y = geometry.bottom - geometry.chartHeight * chartYRatio(value, geometry) * entranceProgress,
    )

private fun chartX(
    model: ChartModel,
    timestampMs: Long,
    geometry: TimelineChartGeometry,
): Float =
    timestampToChartX(
        timestampMs = timestampMs,
        rangeStartMs = model.xAxis.min.toLong(),
        rangeEndMs = model.xAxis.max.toLong(),
        left = geometry.left,
        right = geometry.right,
    )

private fun chartYRatio(
    value: Double,
    geometry: TimelineChartGeometry,
): Float =
    (value / geometry.yMax).toFloat().coerceIn(0f, 1f)

private fun areaPath(
    offsets: List<Offset>,
    bottom: Float,
): Path =
    Path().apply {
        moveTo(offsets.first().x, bottom)
        offsets.forEach { offset -> lineTo(offset.x, offset.y) }
        lineTo(offsets.last().x, bottom)
        close()
    }

private fun linePath(offsets: List<Offset>): Path =
    Path().apply {
        offsets.firstOrNull()?.let { first ->
            moveTo(first.x, first.y)
            offsets.drop(1).forEach { offset -> lineTo(offset.x, offset.y) }
        }
    }
