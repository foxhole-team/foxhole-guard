package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Marching ants around the perimeter — the classic 16-bit selection frame. The dash marches in
 * hard one-cell steps, never sliding, and marks a surfaced panel as modal so the eye catches the
 * live edge. Drawn over the content, leaving the panel's own static border beneath.
 */
@Composable
internal fun Modifier.cliMarchingBorder(color: Color): Modifier {
    val phase by rememberInfiniteTransition(label = "modalAnts").animateFloat(
        initialValue = 0f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "modalAntsPhase",
    )
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
 * The same dash canon without the march: marks a passive area, such as the profile import zone,
 * without pulling the eye like a modal.
 */
internal fun Modifier.cliDashedBorder(color: Color): Modifier =
    drawWithContent {
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

private val CLI_DASHED_FRAME_RADIUS = 8.dp
