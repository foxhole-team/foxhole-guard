package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartColorToken

data class DonutChartModel(
    val title: String,
    val segments: List<DonutSegment>,
)

data class DonutSegment(
    val id: String,
    val label: String,
    val value: Double,
    val colorToken: ChartColorToken,
)

@Composable
fun DonutChart(
    model: DonutChartModel,
    modifier: Modifier = Modifier,
) {
    val segments = model.segments.map { segment -> segment to chartColor(segment.colorToken) }
    Canvas(modifier = modifier.size(132.dp)) {
        val total = model.segments.sumOf { segment -> segment.value }.coerceAtLeast(1.0)
        val strokeWidth = 14.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        var startAngle = -90f
        segments.forEach { (segment, color) ->
            val sweep = (segment.value / total * 360.0).toFloat()
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2f, strokeWidth / 2f),
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            startAngle += sweep
        }
    }
}
