package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle

private const val SPINNER_FRAMES = "|/-\\"

@Composable
internal fun CliSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    visible: Boolean = true,
) {
    val colors = LocalCliColors.current
    Box(
        modifier = modifier.size(cliSpinnerSlotSize),
        contentAlignment = Alignment.Center,
    ) {
        if (visible && LocalCliVisualStyle.current == VisualStyle.PLAIN) {
            CliShimmerText(
                text = "…",
                style = CliType.body,
                baseColor = if (color == Color.Unspecified) colors.accent else color,
            )
        } else if (visible) {
            val transition = rememberInfiniteTransition(label = "cliSpinner")
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
            )
        }
    }
}

internal val cliSpinnerSlotSize = 16.dp

@Composable
internal fun CliSectionPreloader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().testTag(CLI_SECTION_PRELOADER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CliLoadingRow(text = text)
    }
}

internal const val CLI_SECTION_PRELOADER_TAG = "cli_section_preloader"

@Composable
internal fun CliLoadingRow(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val colors = LocalCliColors.current
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            CliShimmerText(
                text = stringResource(R.string.cli_common_updating),
                style = if (small) CliType.small else CliType.body,
            )
        }
        return
    }
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
