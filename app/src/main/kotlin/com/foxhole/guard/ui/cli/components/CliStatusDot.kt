package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliScaledSp
import kotlin.math.floor

internal const val CLI_ATTENTION_PULSE_MS = 900

private const val ATTENTION_PULSE_MIN_ALPHA = 0.25f

/**
 * The "something wants attention" marker beside a label — the indicator Settings → Updates shows
 * when an update is waiting.
 *
 * Retro blinks it on and off on the grid, which is the terminal grammar. Modern breathes a round
 * dot between full and [ATTENTION_PULSE_MIN_ALPHA] instead: same cadence, no hard edge.
 */
@Composable
internal fun CliAttentionPixel(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val round = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val transition = rememberInfiniteTransition(label = "cliAttention")
    val value = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CLI_ATTENTION_PULSE_MS, easing = LinearEasing),
            repeatMode = if (round) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "cliAttentionAlpha",
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer {
                alpha = if (round) {
                    ATTENTION_PULSE_MIN_ALPHA + (1f - ATTENTION_PULSE_MIN_ALPHA) * value.value
                } else {
                    if (value.value < 0.5f) 1f else 0f
                }
            }
            .then(if (round) Modifier.clip(CircleShape) else Modifier)
            .background(color),
    )
}

private val STATUS_DOT_GRID = listOf(
    "..XXX..",
    ".XXXXX.",
    "XXXXXXX",
    "XXXXXXX",
    "XXXXXXX",
    ".XXXXX.",
    "..XXX..",
)

internal const val PIXEL_CAP_HEIGHT_RATIO = 0.64f

/**
 * The status marker beside the terminal's state word.
 *
 * Retro draws the 7x7 pixel disc. Modern draws a smooth circle. Transition states pulse in either
 * style; stable connected and disconnected states stay static.
 */
@Composable
internal fun CliStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = cliScaledSp(17f),
    pulsing: Boolean = false,
) {
    val round = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val alphaAt: () -> Float = cliStatusDotAlpha(round = round, pulsing = pulsing)
    val side = with(LocalDensity.current) { (fontSize.toPx() * PIXEL_CAP_HEIGHT_RATIO).toDp() }
    if (round) {
        Box(
            modifier = modifier
                .size(side)
                .graphicsLayer { alpha = alphaAt() }
                .clip(CircleShape)
                .background(color),
        )
        return
    }
    Canvas(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = alphaAt() },
    ) {
        val grid = STATUS_DOT_GRID.size
        val cell = size.minDimension / grid
        val originX = (size.width - size.minDimension) / 2f
        val originY = (size.height - size.minDimension) / 2f
        STATUS_DOT_GRID.forEachIndexed { row, cells ->
            val top = originY + floor(row * cell)
            val bottom = originY + floor((row + 1) * cell)
            cells.forEachIndexed { column, pixel ->
                if (pixel != '.') {
                    val left = originX + floor(column * cell)
                    val right = originX + floor((column + 1) * cell)
                    drawRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                    )
                }
            }
        }
    }
}

@Composable
private fun cliStatusDotAlpha(round: Boolean, pulsing: Boolean): () -> Float {
    if (!cliStatusDotAnimates(pulsing)) {
        return { 1f }
    }
    val transition = rememberInfiniteTransition(label = "cliDot")
    val value = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CLI_ATTENTION_PULSE_MS, easing = LinearEasing),
            repeatMode = if (round) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "cliDotAlpha",
    )
    if (round) {
        return { ATTENTION_PULSE_MIN_ALPHA + (1f - ATTENTION_PULSE_MIN_ALPHA) * value.value }
    }
    return { if (value.value < 0.5f) 1f else 0.3f }
}

internal fun cliStatusDotAnimates(pulsing: Boolean): Boolean = pulsing
