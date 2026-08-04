package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

// An ASCII propeller rather than braille: all four frames exist in the pixel fonts, with no system
// symbol fallback, so the spinner stays 16-bit in both face modes.
private const val SPINNER_FRAMES = "|/-\\"

/** ASCII spinner glyph, ~200ms per frame. */
@Composable
internal fun CliSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val transition = rememberInfiniteTransition(label = "cliSpinner")
    // An Int animation rather than float: repeated frame values do not invalidate the composition,
    // so the text recomposes on glyph changes rather than every display frame.
    val frameIndex by transition.animateValue(
        initialValue = 0,
        targetValue = SPINNER_FRAMES.length,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cliSpinnerFrame",
    )
    val frame = SPINNER_FRAMES[frameIndex % SPINNER_FRAMES.length]
    Text(
        text = frame.toString(),
        style = CliType.body,
        color = if (color == Color.Unspecified) colors.accent else color,
        modifier = modifier,
    )
}

/**
 * The canonical spinner-with-caption waiting row, instead of `Row { CliSpinner(); Text(…) }`
 * scattered across screens. [small] switches the caption size for dense journal blocks.
 */
@Composable
internal fun CliLoadingRow(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val colors = LocalCliColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CliSpinner()
        Text(
            text = " $text",
            style = if (small) CliType.small else CliType.body,
            color = colors.dim,
            maxLines = 1,
        )
    }
}
