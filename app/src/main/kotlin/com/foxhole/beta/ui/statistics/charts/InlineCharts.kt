package com.foxhole.beta.ui.statistics.charts

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartColorToken

private const val INLINE_CHART_ANIMATION_DURATION_MS = 700

@Immutable
data class SegmentedBarSegment(
    val color: Color,
    val ratio: Float,
    val minVisibleWidth: Dp = 0.dp,
)

@Composable
fun AnimatedSplitDonutChart(
    successRate: Float,
    errorRate: Float,
    visible: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    strokeWidth: Dp? = null,
    successColor: Color = chartColor(ChartColorToken.SUCCESS),
    errorColor: Color = chartColor(ChartColorToken.ERROR),
    animationLabel: String = "split-donut-progress",
) {
    val tokens = chartVisualTokens()
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = INLINE_CHART_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = animationLabel,
    )
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val stroke = Stroke(width = (strokeWidth ?: tokens.ringStrokeWidth).toPx(), cap = StrokeCap.Round)
        val successSweep = 360f * successRate.coerceIn(0f, 1f) * progress
        val errorSweep = 360f * errorRate.coerceIn(0f, 1f) * progress
        drawArc(
            color = tokens.ringTrackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = successColor,
            startAngle = -90f,
            sweepAngle = successSweep,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = errorColor,
            startAngle = -90f + successSweep,
            sweepAngle = errorSweep,
            useCenter = false,
            style = stroke,
        )
    }
}

@Composable
fun AnimatedSegmentDonutChart(
    values: List<Float>,
    colors: List<Color>,
    visible: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    strokeWidth: Dp? = null,
    animationLabel: String = "segment-donut-progress",
) {
    val tokens = chartVisualTokens()
    val total = values.sum().coerceAtLeast(1f)
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = INLINE_CHART_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = animationLabel,
    )
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val stroke = Stroke(width = (strokeWidth ?: tokens.ringStrokeWidth).toPx(), cap = StrokeCap.Round)
        var start = -90f
        drawArc(
            color = tokens.ringTrackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        if (colors.isEmpty()) {
            return@Canvas
        }
        values.forEachIndexed { index, value ->
            val sweep = 360f * (value.coerceAtLeast(0f) / total) * progress
            drawArc(
                color = colors[index % colors.size],
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                style = stroke,
            )
            start += sweep
        }
    }
}

@Composable
fun AnimatedProgressRing(
    value: Float,
    color: Color,
    visible: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 8.dp,
    animationLabel: String = "progress-ring",
) {
    val tokens = chartVisualTokens()
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = INLINE_CHART_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = animationLabel,
    )
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = tokens.ringTrackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * value.coerceIn(0f, 1f) * progress,
            useCenter = false,
            style = stroke,
        )
    }
}

@Composable
fun VerticalValueBarChart(
    values: List<Long>,
    colors: List<Color>,
    maxValue: Long,
    visible: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    animationLabel: String = "vertical-bars-progress",
) {
    val tokens = chartVisualTokens()
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = INLINE_CHART_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = animationLabel,
    )
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val chartTop = 8.dp.toPx()
        val chartBottom = size.height - 4.dp.toPx()
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 9f))
        val gridStrokeWidth = tokens.gridStrokeWidth.toPx()
        listOf(0f, 0.5f, 1f).forEach { ratio ->
            val y = chartBottom - chartHeight * ratio
            drawLine(
                color = tokens.gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = gridStrokeWidth,
                pathEffect = dash,
            )
        }
        val slotWidth = size.width / values.size.coerceAtLeast(1).toFloat()
        val barWidth = (slotWidth * 0.48f).coerceIn(tokens.barMinWidth.toPx(), 20.dp.toPx())
        val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
        values.forEachIndexed { index, value ->
            val normalized = value.toFloat() / maxValue.coerceAtLeast(1L).toFloat()
            val barHeight = (chartHeight * normalized * progress).coerceAtLeast(if (value > 0L) 2f else 0f)
            val center = slotWidth * index + slotWidth / 2f
            drawRoundRect(
                color = tokens.trackColor,
                topLeft = Offset(center - barWidth / 2f, chartTop),
                size = Size(barWidth, chartHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = colors[index % colors.size],
                topLeft = Offset(center - barWidth / 2f, chartBottom - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun SplitOutcomeBar(
    successRate: Float,
    errorRate: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
    successColor: Color = chartColor(ChartColorToken.SUCCESS),
    errorColor: Color = chartColor(ChartColorToken.ERROR),
) {
    val tokens = chartVisualTokens()
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
        drawRoundRect(
            color = tokens.trackColor,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = successColor,
            size = Size(size.width * successRate.coerceIn(0f, 1f), size.height),
            cornerRadius = radius,
        )
        if (errorRate > 0f) {
            val errorWidth = size.width * errorRate.coerceIn(0f, 1f)
            drawRoundRect(
                color = errorColor,
                topLeft = Offset(size.width - errorWidth, 0f),
                size = Size(errorWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun SegmentedLinearBar(
    segments: List<SegmentedBarSegment>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val tokens = chartVisualTokens()
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
        drawRoundRect(color = tokens.trackColor, size = size, cornerRadius = radius)
        val scaledWidth = size.width * scale.coerceIn(0f, 1f)
        if (scaledWidth <= 0f) {
            return@Canvas
        }
        var left = 0f
        segments.forEach { segment ->
            val rawWidth = scaledWidth * segment.ratio.coerceIn(0f, 1f)
            val width =
                rawWidth
                    .coerceAtLeast(if (segment.ratio > 0f) segment.minVisibleWidth.toPx() else 0f)
                    .coerceAtMost((scaledWidth - left).coerceAtLeast(0f))
            if (width > 0f) {
                drawRoundRect(
                    color = segment.color,
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                    cornerRadius = radius,
                )
                left += width
            }
        }
    }
}
