package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun GroupedBarChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
) {
    val tokens = chartVisualTokens()
    val colorsBySeries = model.series.associate { series -> series.id to chartColor(series.colorToken) }
    ChartScaffold(model = model, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .semantics { contentDescription = model.accessibilitySummary() },
        ) {
            val top = 10.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val chartHeight = (bottom - top).coerceAtLeast(1f)
            val yMax = model.yAxis.max.coerceAtLeast(1.0)
            val pointsCount = model.series.maxOfOrNull { series -> series.points.size }?.coerceAtLeast(1) ?: 1
            val slotWidth = size.width / pointsCount.toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { ratio ->
                val y = bottom - chartHeight * ratio
                drawLine(
                    color = tokens.gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    pathEffect = dash,
                )
            }
            for (index in 0..pointsCount) {
                val x = (slotWidth * index).coerceIn(0f, size.width)
                drawLine(
                    color = tokens.gridColor,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    pathEffect = dash,
                )
            }
            drawLine(
                color = tokens.axisColor,
                start = Offset(0f, top),
                end = Offset(0f, bottom),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = tokens.axisColor,
                start = Offset(0f, bottom),
                end = Offset(size.width, bottom),
                strokeWidth = 1.dp.toPx(),
            )
            val seriesWidth = slotWidth * 0.68f
            val barWidth = (seriesWidth / model.series.size.coerceAtLeast(1)).coerceIn(2.dp.toPx(), 14.dp.toPx())
            val radius = tokens.barCornerRadius.toPx()
            model.series.forEachIndexed { seriesIndex, series ->
                val color = colorsBySeries[series.id] ?: return@forEachIndexed
                series.points.forEachIndexed { pointIndex, point ->
                    val center = slotWidth * pointIndex + slotWidth / 2f
                    val x = center - seriesWidth / 2f + seriesIndex * barWidth
                    val height = chartHeight * (point.y / yMax).toFloat().coerceIn(0f, 1f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, bottom - height),
                        size = Size(barWidth, height.coerceAtLeast(if (point.y > 0.0) 2f else 0f)),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
            }
        }
    }
}
