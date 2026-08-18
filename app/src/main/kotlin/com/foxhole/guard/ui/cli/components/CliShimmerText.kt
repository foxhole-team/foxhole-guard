package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import kotlinx.coroutines.delay

internal const val CLI_SHIMMER_CYCLE_MS = 1400

private const val SHIMMER_SPAN_PX = 340f
private const val DARK_SWEEP_FRACTION = 0.55f
private const val LIGHT_SWEEP_FRACTION = 0.7f
private const val LUMINANCE_MIDPOINT = 0.4f

@Composable
internal fun CliShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CliType.small,
    baseColor: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val colors = LocalCliColors.current
    val base = if (baseColor == Color.Unspecified) colors.dim else baseColor
    val phase = cliMotionPhase()
    var shown by remember { mutableStateOf(text) }
    var visibleChars by remember { mutableIntStateOf(text.length) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        while (visibleChars > 0) {
            delay(CLI_ERASE_STEP_MS)
            visibleChars = (visibleChars - CLI_CHARS_PER_STEP).coerceAtLeast(0)
        }
        shown = text
        while (visibleChars < shown.length) {
            delay(CLI_TYPE_STEP_MS)
            visibleChars = (visibleChars + CLI_CHARS_PER_STEP).coerceAtMost(shown.length)
        }
    }
    val sweepColors = remember(base) { listOf(base, cliShimmerHighlight(base), base) }
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val sweepStart = phase.value * (SHIMMER_SPAN_PX * 2f) - SHIMMER_SPAN_PX
                drawRect(
                    brush = Brush.linearGradient(
                        colors = sweepColors,
                        start = Offset(sweepStart, 0f),
                        end = Offset(sweepStart + SHIMMER_SPAN_PX, 0f),
                    ),
                    blendMode = BlendMode.SrcIn,
                )
            },
    ) {
        Text(
            text = shown.take(visibleChars.coerceAtMost(shown.length)),
            style = style,
            color = base,
            maxLines = maxLines,
        )
    }
}

internal fun cliShimmerHighlight(base: Color): Color =
    if (base.luminance() > LUMINANCE_MIDPOINT) {
        lerp(base, Color.Black, DARK_SWEEP_FRACTION)
    } else {
        lerp(base, Color.White, LIGHT_SWEEP_FRACTION)
    }

@Composable
internal fun CliUpdatingText(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    CliShimmerText(
        text = stringResource(R.string.cli_common_updating),
        modifier = modifier,
        style = CliType.small,
        baseColor = color,
    )
}
