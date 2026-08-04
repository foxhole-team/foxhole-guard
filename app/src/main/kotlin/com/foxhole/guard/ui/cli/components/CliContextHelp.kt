package com.foxhole.guard.ui.cli.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * Contextual help for a complex screen: a `pix_info` icon button in the header's trailing slot,
 * whose tap opens the shared [CliBottomSheet] with the matching help section.
 */
@Composable
internal fun CliContextHelpButton(
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        // requiredSize rather than size: in the header's fixed slot the button keeps its 48dp
        // target by overlapping instead of inflating the title row.
        modifier = modifier
            .requiredSize(CliContextHelpButtonSize)
            .cliPressable(onClick = { open = true }),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_info,
            contentDescription = stringResource(R.string.cli_context_help_title),
            size = 16.dp,
            tint = colors.accent,
        )
    }
    if (open) {
        CliBottomSheet(
            onDismiss = { open = false },
            title = stringResource(R.string.cli_context_help_title),
            icon = R.drawable.pix_info,
        ) {
            Text(
                text = stringResource(bodyRes),
                style = CliType.body,
                color = colors.fg,
            )
        }
    }
}

private val CliContextHelpButtonSize = 48.dp
