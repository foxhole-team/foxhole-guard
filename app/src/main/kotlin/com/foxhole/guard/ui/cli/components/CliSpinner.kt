package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

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
        if (visible) {
            CliShimmerText(
                text = "…",
                style = CliType.body,
                baseColor = if (color == Color.Unspecified) colors.accent else color,
            )
        }
    }
}

internal val cliSpinnerSlotSize = 16.dp

@Composable
internal fun CliMetricSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalCliColors.current.accent else color
    Box(
        modifier = modifier.size(cliSpinnerSlotSize),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(METRIC_SPINNER_SIZE)
                .testTag(CLI_METRIC_SPINNER_TAG),
            color = resolvedColor,
            strokeWidth = METRIC_SPINNER_STROKE_WIDTH,
        )
    }
}

internal const val CLI_METRIC_SPINNER_TAG = "cli_metric_spinner"

private val METRIC_SPINNER_SIZE = 12.dp
private val METRIC_SPINNER_STROKE_WIDTH = 1.5.dp

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
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CliShimmerText(
            text = text,
            style = if (small) CliType.small else CliType.body,
            maxLines = 1,
        )
    }
}
