package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun HeatmapChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
) {
    val firstSeries = model.series.firstOrNull()
    val heatColor = firstSeries?.let { series -> chartColor(series.colorToken) }
    ChartScaffold(model = model, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val points = firstSeries?.points.orEmpty()
            val columns = points.size.coerceAtLeast(1)
            val cellWidth = size.width / columns.toFloat()
            val max = model.yAxis.max.coerceAtLeast(1.0)
            val color = heatColor ?: return@Canvas
            points.forEachIndexed { index, point ->
                val alpha = (point.y / max).toFloat().coerceIn(0.08f, 1f)
                drawRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(index * cellWidth, 0f),
                    size = Size(cellWidth - 1.dp.toPx(), size.height),
                )
            }
        }
    }
}
