package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow

@Composable
internal fun CliProtocolSwitchConfirmation(onContinue: () -> Unit, onDismiss: () -> Unit) {
    CliProfileActionConfirmation(
        title = stringResource(R.string.cli_protocol_switch_confirmation_title),
        body = stringResource(R.string.cli_protocol_switch_confirmation_body),
        onContinue = onContinue,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun CliProfileDeleteConfirmation(profileName: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    CliProfileActionConfirmation(
        title = stringResource(R.string.cli_profile_delete_confirmation_title),
        body = stringResource(R.string.cli_prof_delete_confirm, profileName),
        onContinue = onContinue,
        onDismiss = onDismiss,
    )
}

@Composable
private fun CliProfileActionConfirmation(
    title: String,
    body: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    CliBottomSheet(
        title = title,
        icon = R.drawable.lin_profiles,
        closeLabel = stringResource(R.string.cli_profile_confirmation_cancel),
        onDismiss = onDismiss,
    ) {
        Text(body, style = CliType.body, color = LocalCliColors.current.fg)
        Spacer(Modifier.height(CliSpacing.sm))
        CliSheetActionsRow(
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_profile_confirmation_continue),
                    onClick = onContinue,
                    dismissAfterClick = true,
                ),
            ),
        )
    }
}
