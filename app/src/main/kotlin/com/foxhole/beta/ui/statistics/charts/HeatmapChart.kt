package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun HeatmapChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
) {
    val firstSeries = model.series.firstOrNull()
    val heatColor = firstSeries?.let { series -> chartColor(series.colorToken) }
    val tokens = chartVisualTokens()
    ChartScaffold(model = model, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = model.accessibilitySummary() },
        ) {
            val points = firstSeries?.points.orEmpty()
            val columns = points.size.coerceAtLeast(1)
            val cellWidth = size.width / columns.toFloat()
            val max = model.yAxis.max.coerceAtLeast(1.0)
            val color = heatColor ?: return@Canvas
            val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
            drawRoundRect(color = tokens.trackColor, size = size, cornerRadius = radius)
            val cellGap = 1.dp.toPx().coerceAtMost(cellWidth * 0.35f)
            points.forEachIndexed { index, point ->
                val alpha = (point.y / max).toFloat().coerceIn(0.08f, 1f)
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(index * cellWidth, 0f),
                    size = Size((cellWidth - cellGap).coerceAtLeast(0f), size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}
