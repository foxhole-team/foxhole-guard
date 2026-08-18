package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import kotlin.math.ceil

@Composable
internal fun CliStageProgress(
    stageLabel: String,
    completedStages: Int,
    totalStages: Int,
    running: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val safeTotal = totalStages.coerceAtLeast(1)
    val safeCompleted = completedStages.coerceIn(0, safeTotal)
    val progressColor = if (color == Color.Unspecified) colors.accent else color
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        val progressText = "$stageLabel · $safeCompleted/$safeTotal"
        if (running) {
            CliShimmerText(
                text = progressText,
                style = CliType.small,
                baseColor = progressColor,
                modifier = modifier.fillMaxWidth().testTag(CLI_STAGE_PROGRESS_TAG),
            )
        } else {
            Text(
                text = progressText,
                style = CliType.small,
                color = progressColor,
                modifier = modifier.fillMaxWidth().testTag(CLI_STAGE_PROGRESS_TAG),
            )
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CLI_STAGE_PROGRESS_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CliSpinner(color = progressColor, visible = running)
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            Text(
                text = stageLabel,
                style = CliType.small,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$safeCompleted/$safeTotal",
                style = CliType.small,
                color = progressColor,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliPixelProgressSegments(
            completedSegments = safeCompleted,
            totalSegments = safeTotal,
            color = progressColor,
        )
    }
}

@Composable
internal fun CliPixelProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    segmentCount: Int = CLI_PIXEL_PROGRESS_SEGMENTS,
) {
    val colors = LocalCliColors.current
    val safeTotal = segmentCount.coerceAtLeast(1)
    val safeFraction = fraction.coerceIn(0f, 1f)
    val completed =
        if (safeFraction <= 0f) 0 else ceil(safeFraction * safeTotal).toInt().coerceAtMost(safeTotal)
    CliPixelProgressSegments(
        completedSegments = completed,
        totalSegments = safeTotal,
        color = if (color == Color.Unspecified) colors.accent else color,
        modifier = modifier.testTag(CLI_PIXEL_PROGRESS_TAG),
    )
}

@Composable
private fun CliPixelProgressSegments(
    completedSegments: Int,
    totalSegments: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val safeTotal = totalSegments.coerceAtLeast(1)
    val safeCompleted = completedSegments.coerceIn(0, safeTotal)
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(shape)
                .background(colors.panel)
                .border(1.dp, colors.border, shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safeCompleted.toFloat() / safeTotal)
                    .height(8.dp)
                    .clip(shape)
                    .background(color),
            )
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth().height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(safeTotal) { index ->
            val complete = index < safeCompleted
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(if (complete) color else colors.panel)
                    .border(1.dp, if (complete) color else colors.border),
            )
        }
    }
}

internal const val CLI_STAGE_PROGRESS_TAG = "cli_stage_progress"
internal const val CLI_PIXEL_PROGRESS_TAG = "cli_pixel_progress"
private const val CLI_PIXEL_PROGRESS_SEGMENTS = 20
