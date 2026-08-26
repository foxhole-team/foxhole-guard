package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliPixelProgressBar
import com.foxhole.guard.ui.cli.components.CliStageProgress
import com.foxhole.guard.ui.isRunning

@Composable
internal fun CliFoxholeUpdateProgress(
    phase: FoxholeUpdatePhase,
    modifier: Modifier = Modifier,
) {
    val stage = phase.foxholeUpdateStage() ?: return
    val colors = LocalCliColors.current
    CliStageProgress(
        stageLabel = stringResource(phase.verifiedUpdateProgressLabelRes()),
        completedStages = stage,
        totalStages = FOXHOLE_UPDATE_STAGE_COUNT,
        running = phase.isRunning,
        color = if (stage == FOXHOLE_UPDATE_STAGE_COUNT) colors.ok else colors.accent,
        modifier = modifier,
    )
}

@Composable
internal fun CliVerifiedUpdateProgress(
    phase: FoxholeUpdatePhase,
    downloadProgress: RemoteDownloadProgress?,
    verifiedSuccess: Boolean,
    modifier: Modifier = Modifier,
) {
    val completedStages = verifiedUpdateStageProgress(phase, verifiedSuccess) ?: return
    val colors = LocalCliColors.current
    val waitingForVerifiedState =
        !verifiedSuccess && (phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE)
    val visibleDownloadProgress =
        downloadProgress?.takeIf { phase == FoxholeUpdatePhase.DOWNLOADING && it.totalBytes > 0L }
    Column(modifier = modifier) {
        CliStageProgress(
            stageLabel = stringResource(
                if (waitingForVerifiedState) {
                    R.string.cli_wizard_phase_verifying
                } else {
                    phase.verifiedUpdateProgressLabelRes()
                },
            ),
            completedStages = completedStages,
            totalStages = VERIFIED_UPDATE_STAGE_COUNT,
            running = phase.isRunning || waitingForVerifiedState,
            color = if (verifiedSuccess) colors.ok else colors.accent,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliPixelProgressBar(
            fraction = verifiedUpdateProgressFraction(phase, downloadProgress, verifiedSuccess),
            color = if (verifiedSuccess) colors.ok else colors.accent,
        )
        if (visibleDownloadProgress != null) {
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            Text(
                text = stringResource(
                    R.string.cli_wizard_phase_downloading_percent,
                    (visibleDownloadProgress.fraction * 100f).toInt().coerceIn(0, 100),
                ),
                style = CliType.small,
                color = colors.dim,
            )
        }
    }
}

internal fun verifiedUpdateProgressFraction(
    phase: FoxholeUpdatePhase,
    downloadProgress: RemoteDownloadProgress?,
    verifiedSuccess: Boolean,
): Float = when {
    verifiedSuccess && (phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE) -> 1f
    phase == FoxholeUpdatePhase.CHECKING -> VERIFIED_CHECKING_FRACTION
    phase == FoxholeUpdatePhase.DOWNLOADING ->
        VERIFIED_CHECKING_FRACTION + VERIFIED_DOWNLOAD_WEIGHT * (downloadProgress?.fraction ?: 0f)
    phase == FoxholeUpdatePhase.VERIFYING -> VERIFIED_VERIFYING_FRACTION
    phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE -> VERIFIED_VERIFYING_FRACTION
    else -> 0f
}

internal fun FoxholeUpdatePhase.foxholeUpdateStage(): Int? = when (this) {
    FoxholeUpdatePhase.CHECKING -> 1
    FoxholeUpdatePhase.DOWNLOADING -> 2
    FoxholeUpdatePhase.VERIFYING -> 3
    FoxholeUpdatePhase.DONE, FoxholeUpdatePhase.NO_UPDATE -> 4
    FoxholeUpdatePhase.IDLE, FoxholeUpdatePhase.FAILED -> null
}

internal fun verifiedUpdateStageProgress(
    phase: FoxholeUpdatePhase,
    verifiedSuccess: Boolean,
): Int? = when {
    verifiedSuccess &&
        (phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE) -> {
        VERIFIED_UPDATE_STAGE_COUNT
    }
    phase == FoxholeUpdatePhase.CHECKING -> 1
    phase == FoxholeUpdatePhase.DOWNLOADING -> 2
    phase == FoxholeUpdatePhase.VERIFYING -> 3
    phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE -> 3
    else -> null
}

internal fun FoxholeUpdatePhase.verifiedUpdateProgressLabelRes(): Int = when (this) {
    FoxholeUpdatePhase.CHECKING -> R.string.cli_wizard_phase_checking
    FoxholeUpdatePhase.DOWNLOADING -> R.string.cli_wizard_phase_downloading
    FoxholeUpdatePhase.VERIFYING -> R.string.cli_wizard_phase_verifying
    FoxholeUpdatePhase.DONE, FoxholeUpdatePhase.NO_UPDATE -> R.string.cli_wizard_phase_done
    FoxholeUpdatePhase.IDLE, FoxholeUpdatePhase.FAILED -> R.string.cli_foxdb_status_failed
}

private const val FOXHOLE_UPDATE_STAGE_COUNT = 4
internal const val VERIFIED_UPDATE_STAGE_COUNT = 4
private const val VERIFIED_CHECKING_FRACTION = 0.25f
private const val VERIFIED_DOWNLOAD_WEIGHT = 0.5f
private const val VERIFIED_VERIFYING_FRACTION = 0.75f
