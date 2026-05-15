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
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun GroupedBarChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val colorsBySeries = model.series.associate { series -> series.id to chartColor(series.colorToken) }
    ChartScaffold(model = model, modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(170.dp),
        ) {
            val top = 10.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val chartHeight = (bottom - top).coerceAtLeast(1f)
            val yMax = model.yAxis.max.coerceAtLeast(1.0)
            drawLine(
                color = gridColor,
                start = Offset(0f, top),
                end = Offset(size.width, top),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val pointsCount = model.series.maxOfOrNull { series -> series.points.size }?.coerceAtLeast(1) ?: 1
            val slotWidth = size.width / pointsCount.toFloat()
            val seriesWidth = slotWidth * 0.68f
            val barWidth = (seriesWidth / model.series.size.coerceAtLeast(1)).coerceIn(2.dp.toPx(), 14.dp.toPx())
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
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
            }
        }
    }
}
