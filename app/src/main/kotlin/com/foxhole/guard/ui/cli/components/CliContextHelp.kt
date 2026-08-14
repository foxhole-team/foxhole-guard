package com.foxhole.guard.ui.cli.components

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartItems

/**
 * Contextual help for a complex screen: a pixel `?` button in the header's trailing slot,
 * whose tap opens the shared [CliBottomSheet] with the matching help section.
 */
@Composable
internal fun CliContextHelpButton(
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    val colors = LocalCliColors.current
    val helpDescription = stringResource(R.string.cli_context_help_title)
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        // requiredSize rather than size: in the header's fixed slot the button keeps its 48dp
        // target by overlapping instead of inflating the title row.
        modifier = modifier
            .requiredSize(CliContextHelpButtonSize)
            .cliPressable(
                onClick = {
                    onOpen()
                    open = true
                },
            )
            .semantics { contentDescription = helpDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(HELP_CIRCLE_SIZE)
                .border(HELP_CIRCLE_STROKE, colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "?",
                style = CliType.display.copy(
                    fontSize = HELP_GLYPH_SIZE,
                    lineHeight = HELP_GLYPH_LINE_HEIGHT,
                ),
                color = colors.accent,
            )
        }
    }
    if (open) {
        CliBottomSheet(
            onDismiss = { open = false },
            title = stringResource(R.string.cli_context_help_title),
            icon = R.drawable.pix_info,
        ) {
            CliQuickStartItems(body = stringResource(bodyRes), framed = false)
        }
    }
}

private val CliContextHelpButtonSize = 48.dp
private val HELP_CIRCLE_SIZE = 16.dp
private val HELP_CIRCLE_STROKE = 1.dp
private val HELP_GLYPH_SIZE = 10.sp
private val HELP_GLYPH_LINE_HEIGHT = 12.sp
