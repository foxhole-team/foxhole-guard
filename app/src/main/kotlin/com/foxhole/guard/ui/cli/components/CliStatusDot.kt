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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * Blinking square attention pixel: "something here needs an action" (e.g. a pending update on
 * the settings root). Hard on/off steps at cursor cadence — no fade, and read in the draw phase
 * for the same reason as [CliStatusDot].
 */
@Composable
internal fun CliAttentionPixel(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "cliAttention")
    val value = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cliAttentionAlpha",
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { alpha = if (value.value < 0.5f) 1f else 0f }
            .background(color),
    )
}

/**
 * The disc on a 7x7 grid. Symmetric on both axes and on both diagonals, which is what makes it
 * read as a circle at this size — a mathematically rounded one gains a pixel on one side and
 * looks chipped next to a pixel font.
 */
private val STATUS_DOT_GRID = listOf(
    "..XXX..",
    ".XXXXX.",
    "XXXXXXX",
    "XXXXXXX",
    "XXXXXXX",
    ".XXXXX.",
    "..XXX..",
)

/**
 * Cap height of a pixel face as a fraction of its nominal size. LanaPixel draws its capitals over
 * roughly two thirds of the em box, so this is what "the same height as the letter beside it"
 * means in a number.
 *
 * Internal rather than private: a caller that has to place the dot ON the text's baseline (the home
 * header) needs the same number the dot is sized from, and computing it twice is how the two drift.
 */
internal const val PIXEL_CAP_HEIGHT_RATIO = 0.64f

/**
 * Status dot, drawn as a pixel disc on the same grid the rest of this UI is drawn on.
 *
 * It used to be a real circle (`CircleShape`), which is the one round thing on a screen made of
 * squares: smooth-edged, antialiased, and — because it was a fixed 10dp — a different size from
 * the word beside it at every font scale. Now it is built from whole pixels and sized from the
 * status word's own font size, so it stands exactly as tall as the capital letters it sits next to
 * and grows with them.
 *
 * Pulses in a hard step — terminal cursor, not a fade.
 */
@Composable
internal fun CliStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 17.sp,
    pulsing: Boolean = false,
) {
    // The pulse is read in the draw phase: reading animateFloat in composition recomposed the dot on
    // every display frame for the sake of two states a second.
    val pulseAlpha: () -> Float = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "cliDot")
        val value = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "cliDotAlpha",
        )
        val stepped = { if (value.value < 0.5f) 1f else 0.3f }
        stepped
    } else {
        { 1f }
    }
    // Through sp, so a device with larger text gets a larger dot: the two are one line of type.
    val side = with(LocalDensity.current) { (fontSize.toPx() * PIXEL_CAP_HEIGHT_RATIO).toDp() }
    Canvas(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = pulseAlpha() },
    ) {
        // The disc fills the box EXACTLY: cells are fractional, and each edge is computed from the
        // rounded grid positions rather than a shared rounded cell — flooring the cell used to
        // shrink the disc below the letter height and shift it off-centre by up to a cell.
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
