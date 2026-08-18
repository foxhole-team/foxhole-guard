package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors

@Composable
internal fun CliInfoSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_common_information),
        icon = R.drawable.pix_info,
        footer = {
            CliButton(
                label = stringResource(R.string.cli_common_close_action),
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = CliSpacing.md),
            )
        },
    ) {
        CliDashedInfoNote(
            text = text,
            outerVerticalPadding = 0.dp,
            centered = true,
            centeredIconLeading = true,
            centeredIconFirstLine = true,
            color = colors.accent,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}
