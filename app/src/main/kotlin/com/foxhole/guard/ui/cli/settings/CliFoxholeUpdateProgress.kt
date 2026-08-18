package com.foxhole.guard.ui.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.cli.LocalCliColors
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
        stageLabel = stringResource(phase.foxholeUpdateProgressLabelRes()),
        completedStages = stage,
        totalStages = FOXHOLE_UPDATE_STAGE_COUNT,
        running = phase.isRunning,
        color = if (stage == FOXHOLE_UPDATE_STAGE_COUNT) colors.ok else colors.accent,
        modifier = modifier,
    )
}

internal fun FoxholeUpdatePhase.foxholeUpdateStage(): Int? = when (this) {
    FoxholeUpdatePhase.CHECKING -> 1
    FoxholeUpdatePhase.DOWNLOADING -> 2
    FoxholeUpdatePhase.VERIFYING -> 3
    FoxholeUpdatePhase.DONE, FoxholeUpdatePhase.NO_UPDATE -> 4
    FoxholeUpdatePhase.IDLE, FoxholeUpdatePhase.FAILED -> null
}

private fun FoxholeUpdatePhase.foxholeUpdateProgressLabelRes(): Int = when (this) {
    FoxholeUpdatePhase.CHECKING -> R.string.cli_wizard_phase_checking
    FoxholeUpdatePhase.DOWNLOADING -> R.string.cli_wizard_phase_downloading
    FoxholeUpdatePhase.VERIFYING -> R.string.cli_wizard_phase_verifying
    FoxholeUpdatePhase.DONE, FoxholeUpdatePhase.NO_UPDATE -> R.string.cli_wizard_phase_done
    FoxholeUpdatePhase.IDLE, FoxholeUpdatePhase.FAILED -> R.string.cli_foxdb_status_failed
}

private const val FOXHOLE_UPDATE_STAGE_COUNT = 4
