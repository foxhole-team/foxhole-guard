package com.foxhole.guard.ui.cli.components

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliLabelText

@Composable
internal fun CliContextHelpButton(
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    val colors = LocalCliColors.current
    val helpDescription = stringResource(R.string.cli_context_help_title)
    var open by rememberSaveable { mutableStateOf(false) }
    CliHeaderHelpButton(
        contentDescription = helpDescription,
        modifier = modifier,
        onClick = {
            onOpen()
            open = true
        },
    )
    if (open) {
        CliBottomSheet(
            onDismiss = { open = false },
            title = stringResource(R.string.cli_context_help_title),
            icon = R.drawable.pix_info,
        ) {
            CliDashedInfoNote(
                text = stringResource(bodyRes),
                outerVerticalPadding = 0.dp,
                centered = true,
                centeredIconLeading = true,
                centeredIconFirstLine = true,
                color = colors.accent,
            )
        }
    }
}

@Composable
internal fun CliTopBarHelpButton(
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    val helpDescription = stringResource(R.string.cli_context_help_title)
    var open by rememberSaveable { mutableStateOf(false) }
    CliHeaderHelpButton(
        contentDescription = helpDescription,
        modifier = modifier,
        topBar = true,
        onClick = {
            onOpen()
            open = true
        },
    )
    if (open) {
        CliBottomSheet(
            onDismiss = { open = false },
            title = helpDescription,
            icon = R.drawable.pix_info,
        ) {
            CliTopBarHelpNote(text = stringResource(bodyRes))
        }
    }
}

@Composable
private fun CliTopBarHelpNote(text: String) {
    val colors = LocalCliColors.current
    val frame = if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        Modifier.border(1.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
    } else {
        Modifier.cliDashedBorder(colors.accent)
    }
    val shown = buildAnnotatedString {
        appendInlineContent(TOP_BAR_HELP_ICON_ID, "i")
        append(" ")
        append(cliLabelText(cliTopBarHelpBody(text)))
    }
    val inlineIcon = mapOf(
        TOP_BAR_HELP_ICON_ID to InlineTextContent(
            placeholder = Placeholder(
                width = 1.em,
                height = 1.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextTop,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CliPixIcon(
                    id = R.drawable.pix_info,
                    contentDescription = null,
                    size = CliIconSize.note,
                    tint = colors.accent,
                )
            }
        },
    )
    Text(
        text = shown,
        inlineContent = inlineIcon,
        style = CliType.small,
        color = colors.fg,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .then(frame)
            .padding(horizontal = CliSpacing.md, vertical = CliSpacing.md),
    )
}

internal fun cliTopBarHelpBody(text: String): String =
    text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString("\n\n")

@Composable
internal fun CliHeaderHelpButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topBar: Boolean = false,
) {
    val colors = LocalCliColors.current
    val iconSize = if (topBar && LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        CliModernTopBarHelpIconSize
    } else {
        CliHeaderIconSize
    }
    Box(
        modifier = modifier
            .requiredSize(CliContextHelpButtonSize)
            .cliPressable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_info,
            contentDescription = null,
            size = iconSize,
            tint = colors.accent,
        )
    }
}

private val CliContextHelpButtonSize = CliHeaderControlSlotHeight
private val CliModernTopBarHelpIconSize = 20.dp
private const val TOP_BAR_HELP_ICON_ID = "topBarHelpIcon"
