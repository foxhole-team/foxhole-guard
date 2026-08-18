package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import kotlin.math.roundToInt

@Composable
internal fun Modifier.cliMarchingBorder(color: Color): Modifier {
    val cyclePhase by cliMotionPhase()
    val phase = cyclePhase * MARCHING_ANT_STEPS
    return drawWithContent {
        drawContent()
        val cell = 2.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val step = phase.toInt().coerceIn(0, 4) * cell
        val inset = cell / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size((size.width - cell).coerceAtLeast(0f), (size.height - cell).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(CLI_DASHED_FRAME_RADIUS.toPx()),
            style = Stroke(
                width = cell,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(cell * 3, cell * 2),
                    -step,
                ),
            ),
        )
    }
}

/**
 * A quiet accent sweep used by modern selection/editor surfaces: the same travelling
 * highlight as [CliShimmerText], on the same cycle, drawn along an edge instead of a text run.
 */
@Composable
internal fun Modifier.cliAccentSweepBorder(
    color: Color,
    radius: androidx.compose.ui.unit.Dp = CLI_DASHED_FRAME_RADIUS,
): Modifier {
    val phase by cliMotionPhase()
    return drawWithContent {
        drawContent()
        val stroke = 1.5.dp.toPx()
        val sweep = (size.width + size.height).coerceAtLeast(1f)
        val x = phase * sweep - size.height
        val brush = Brush.linearGradient(
            colors = listOf(
                color.copy(alpha = ACCENT_SWEEP_EDGE_ALPHA),
                color.copy(alpha = ACCENT_SWEEP_PEAK_ALPHA),
                color.copy(alpha = ACCENT_SWEEP_EDGE_ALPHA),
            ),
            start = Offset(x, 0f),
            end = Offset(x + sweep * ACCENT_SWEEP_LENGTH_FRACTION, size.height),
        )
        drawRoundRect(
            brush = brush,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(
                (size.width - stroke).coerceAtLeast(0f),
                (size.height - stroke).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
internal fun Modifier.cliDashedBorder(color: Color): Modifier {
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        return border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(CLI_DASHED_FRAME_RADIUS))
    }
    return drawWithContent {
        drawContent()
        val cell = 2.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val inset = cell / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size((size.width - cell).coerceAtLeast(0f), (size.height - cell).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(CLI_DASHED_FRAME_RADIUS.toPx()),
            style = Stroke(
                width = cell,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(cell * 3, cell * 2), 0f),
            ),
        )
    }
}

private const val MARCHING_ANT_STEPS = 5f

private val CLI_DASHED_FRAME_RADIUS = 8.dp

private const val ACCENT_SWEEP_EDGE_ALPHA = 0.16f
private const val ACCENT_SWEEP_PEAK_ALPHA = 0.92f
private const val ACCENT_SWEEP_LENGTH_FRACTION = 0.42f
