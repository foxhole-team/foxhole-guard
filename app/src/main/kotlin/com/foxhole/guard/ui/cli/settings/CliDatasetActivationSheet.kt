package com.foxhole.guard.ui.cli.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.ui.DatasetActivationEvent
import com.foxhole.guard.ui.DatasetActivationFeature
import com.foxhole.guard.ui.DatasetActivationSource
import com.foxhole.guard.ui.DatasetActivationState
import com.foxhole.guard.ui.DatasetActivationStep
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.availableDatasetSources
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPixelProgressBar
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.reduce
import com.foxhole.guard.ui.selectSource
import kotlinx.coroutines.delay

/** One canonical source -> required data -> verified activation sheet for Tor and Sentinel. */
@Composable
internal fun CliDatasetActivationSheet(
    state: DatasetActivationState,
    phase: FoxholeUpdatePhase,
    progress: RemoteDownloadProgress?,
    verifiedDownload: Boolean,
    onStateChange: (DatasetActivationState) -> Unit,
    onSkip: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onActivate: (DatasetActivationSource) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(state.step, phase, verifiedDownload) {
        if (state.step != DatasetActivationStep.DOWNLOADING) return@LaunchedEffect
        when {
            verifiedDownload -> onStateChange(state.reduce(DatasetActivationEvent.VERIFY))
            phase == FoxholeUpdatePhase.FAILED -> onStateChange(state.reduce(DatasetActivationEvent.FAIL))
        }
    }
    LaunchedEffect(state.step, state.source) {
        if (state.step == DatasetActivationStep.VERIFIED) {
            onActivate(state.source)
            delay(DATASET_SUCCESS_HOLD_MS)
            onDismiss()
        }
    }

    val dismiss = {
        if (state.step == DatasetActivationStep.DOWNLOADING) onCancelDownload()
        onDismiss()
    }
    CliBottomSheet(
        title = stringResource(state.feature.activationTitleRes()),
        icon = state.feature.activationIconRes(),
        onDismiss = dismiss,
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                slideInHorizontally { width -> width } togetherWith
                    slideOutHorizontally { width -> -width }
            },
            label = "dataset-activation-step",
        ) { step ->
            Column(modifier = Modifier.fillMaxWidth()) {
                when (step) {
                    DatasetActivationStep.SOURCE -> DatasetSourceStep(state, onStateChange, onSkip, onDismiss)
                    DatasetActivationStep.DATASET_REQUIRED ->
                        DatasetRequiredStep(state, onStateChange, onSkip, onStartDownload, onDismiss)
                    DatasetActivationStep.DOWNLOADING -> DatasetDownloadStep(phase, progress, dismiss)
                    DatasetActivationStep.VERIFIED -> CliFoxholeUpdateProgress(phase = FoxholeUpdatePhase.DONE)
                    DatasetActivationStep.FAILED ->
                        DatasetFailedStep(state, onStateChange, onSkip, onStartDownload, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun DatasetSourceStep(
    state: DatasetActivationState,
    onStateChange: (DatasetActivationState) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    Text(text = stringResource(state.feature.activationIntroRes()), style = CliType.body, color = colors.fg)
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliDropdownRow(
        label = stringResource(R.string.cli_dataset_source),
        icon = R.drawable.pix_globe,
        value = stringResource(state.source.labelRes()),
        options = state.feature.availableDatasetSources().map { source ->
            CliDropdownOption(source.name, stringResource(source.labelRes()))
        },
        selectedId = state.source.name,
        onSelect = { id ->
            DatasetActivationSource.entries.firstOrNull { it.name == id }
                ?.let { source -> onStateChange(state.selectSource(source)) }
        },
    )
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliSheetActionsRow(
        cancelLabel = stringResource(R.string.cli_dataset_do_not_use),
        onCancel = { onSkipAndDismiss(onSkip, onDismiss) },
        actions = listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_dataset_use),
                onClick = { onStateChange(state.reduce(DatasetActivationEvent.USE)) },
            ),
        ),
    )
}

@Composable
private fun DatasetRequiredStep(
    state: DatasetActivationState,
    onStateChange: (DatasetActivationState) -> Unit,
    onSkip: () -> Unit,
    onStartDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    Text(text = stringResource(state.feature.datasetDescriptionRes()), style = CliType.body, color = colors.fg)
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    Text(text = stringResource(R.string.cli_dataset_signature_note), style = CliType.small, color = colors.info)
    Spacer(modifier = Modifier.height(CliSpacing.md))
    CliSheetActionsRow(
        onCancel = { onSkipAndDismiss(onSkip, onDismiss) },
        actions = listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_foxdb_sheet_download),
                onClick = {
                    onStateChange(state.reduce(DatasetActivationEvent.START_DOWNLOAD))
                    onStartDownload()
                },
            ),
        ),
    )
}

@Composable
private fun DatasetDownloadStep(
    phase: FoxholeUpdatePhase,
    progress: RemoteDownloadProgress?,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliFoxholeUpdateProgress(phase = phase)
    if (phase == FoxholeUpdatePhase.DOWNLOADING && progress != null) {
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliPixelProgressBar(fraction = progress.fraction)
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Text(
            text = stringResource(
                R.string.cli_wizard_phase_downloading_percent,
                (progress.fraction * 100f).toInt().coerceIn(0, 100),
            ),
            style = CliType.small,
            color = colors.dim,
        )
    }
    Spacer(modifier = Modifier.height(CliSpacing.md))
    CliSheetActionsRow(onCancel = onDismiss, actions = emptyList())
}

@Composable
private fun DatasetFailedStep(
    state: DatasetActivationState,
    onStateChange: (DatasetActivationState) -> Unit,
    onSkip: () -> Unit,
    onStartDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    Text(text = stringResource(R.string.cli_dataset_verification_failed), style = CliType.body, color = colors.err)
    Spacer(modifier = Modifier.height(CliSpacing.md))
    CliSheetActionsRow(
        onCancel = { onSkipAndDismiss(onSkip, onDismiss) },
        actions = listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_tor_bridges_update_retry),
                onClick = {
                    val required = state.reduce(DatasetActivationEvent.RETRY)
                    onStateChange(required.reduce(DatasetActivationEvent.START_DOWNLOAD))
                    onStartDownload()
                },
            ),
        ),
    )
}

private fun onSkipAndDismiss(onSkip: () -> Unit, onDismiss: () -> Unit) {
    onSkip()
    onDismiss()
}

private fun DatasetActivationFeature.activationTitleRes(): Int =
    when (this) {
        DatasetActivationFeature.TOR_BRIDGES -> R.string.cli_tor_first_enable_title
        DatasetActivationFeature.FOXHOLE_SENTINEL -> R.string.cli_sentinel_first_enable_title
    }

private fun DatasetActivationFeature.activationIntroRes(): Int =
    when (this) {
        DatasetActivationFeature.TOR_BRIDGES -> R.string.cli_tor_first_enable_intro
        DatasetActivationFeature.FOXHOLE_SENTINEL -> R.string.cli_sentinel_first_enable_intro
    }

private fun DatasetActivationFeature.datasetDescriptionRes(): Int =
    when (this) {
        DatasetActivationFeature.TOR_BRIDGES -> R.string.cli_tor_dataset_required
        DatasetActivationFeature.FOXHOLE_SENTINEL -> R.string.cli_sentinel_dataset_required
    }

private fun DatasetActivationFeature.activationIconRes(): Int =
    when (this) {
        DatasetActivationFeature.TOR_BRIDGES -> R.drawable.pix_tor
        DatasetActivationFeature.FOXHOLE_SENTINEL -> R.drawable.pix_shield
    }

private fun DatasetActivationSource.labelRes(): Int =
    when (this) {
        DatasetActivationSource.TOR_PROJECT -> R.string.cli_dataset_source_tor_project
        DatasetActivationSource.FOXHOLE_DB -> R.string.cli_dataset_source_foxhole_db
    }

private const val DATASET_SUCCESS_HOLD_MS = 900L
