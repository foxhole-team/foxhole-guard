package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing

internal val LocalCliInfoSheetBodyIconVisible = staticCompositionLocalOf { true }

@Composable
internal fun CliInfoSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_common_information),
        icon = R.drawable.lin_info,
        headerIconRole = CliSheetHeaderIconRole.INFORMATION,
    ) {
        CliHelpNote(
            text = text,
            icon = R.drawable.lin_info.takeIf { LocalCliInfoSheetBodyIconVisible.current },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}
