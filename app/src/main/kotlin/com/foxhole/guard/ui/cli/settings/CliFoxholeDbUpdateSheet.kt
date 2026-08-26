package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.isRunning

@Composable
internal fun CliFoxholeDbUpdateSheet(
    groupLabel: String,
    autoUpdateEnabled: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    phase: FoxholeUpdatePhase = FoxholeUpdatePhase.IDLE,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.cli_foxdb_sheet_title),
        icon = R.drawable.lin_update,
        autoDismissAfterMillis = FOXHOLE_UPDATE_SUCCESS_HOLD_MS.takeIf {
            phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE
        },
    ) {
        Text(
            text = stringResource(R.string.cli_foxdb_sheet_body, groupLabel),
            style = CliType.body,
            color = colors.fg,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliToggleRow(
            label = stringResource(R.string.cli_foxdb_sheet_auto),
            icon = R.drawable.lin_restart,
            checked = autoUpdateEnabled,
            onToggle = onAutoUpdateChange,
            note = stringResource(R.string.cli_foxdb_sheet_auto_note),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        if (phase.foxholeUpdateStage() != null) {
            CliFoxholeUpdateProgress(phase = phase)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
        CliSheetActionsRow(
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_foxdb_sheet_download),
                    onClick = onDownload,
                    enabled = !phase.isRunning,
                ),
            ),
        )
    }
}

private const val FOXHOLE_UPDATE_SUCCESS_HOLD_MS = 900L
