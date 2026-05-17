package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val tokens = chartVisualTokens()
    Canvas(
        modifier = modifier
            .size(132.dp)
            .semantics { contentDescription = model.accessibilitySummary() },
    ) {
        val total = model.segments.sumOf { segment -> segment.value }.coerceAtLeast(1.0)
        val strokeWidth = tokens.ringStrokeWidth.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val gapDegrees = if (model.segments.count { segment -> segment.value > 0.0 } > 1) {
            tokens.donutGapDegrees
        } else {
            0f
        }
        var startAngle = -90f
        drawArc(
            color = tokens.ringTrackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        segments.forEach { (segment, color) ->
            val rawSweep = (segment.value / total * 360.0).toFloat()
            val sweep = (rawSweep - gapDegrees).coerceAtLeast(0f)
            drawArc(
                color = color,
                startAngle = startAngle + gapDegrees / 2f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            startAngle += rawSweep
        }
    }
}
