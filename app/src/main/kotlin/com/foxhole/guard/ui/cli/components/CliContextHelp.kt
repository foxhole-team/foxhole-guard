@file:Suppress("MatchingDeclarationName")

package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CLI_ICON_OPTICAL_OFFSET
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.cliHeadingGlyphOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliLabelText

@Composable
internal fun CliContextHelpButton(
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
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
            icon = R.drawable.lin_help,
            headerIconRole = CliSheetHeaderIconRole.INFORMATION,
        ) {
            CliHelpNote(text = stringResource(bodyRes))
        }
    }
}

@Composable
internal fun CliHelpNote(
    text: String,
    @DrawableRes icon: Int? = R.drawable.lin_help,
) {
    val colors = LocalCliColors.current
    val frame = Modifier.border(1.dp, colors.border, RoundedCornerShape(CliRadius.panel))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(frame)
            .padding(horizontal = CliSpacing.md, vertical = CliSpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        icon?.let { iconId ->
            CliFirstLineIcon(
                id = iconId,
                size = CLI_HELP_NOTE_ICON_SIZE,
                lineHeight = CliType.small.lineHeight,
                tint = colors.info,
                modifier = Modifier.offset(y = CLI_HELP_CONTENT_ICON_LIFT),
            )
            Spacer(modifier = Modifier.width(CLI_HELP_NOTE_ICON_GAP))
        }
        Text(
            text = cliLabelText(text),
            style = CliType.small,
            color = colors.fg,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun CliTopBarHelpButton(
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    presentation: CliTopBarHelpPresentation = CliTopBarHelpPresentation(),
    onOpen: () -> Unit = {},
    additionalContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCliColors.current
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
        val body = stringResource(bodyRes)
        CliBottomSheet(
            onDismiss = { open = false },
            title = stringResource(presentation.titleRes),
            icon = presentation.icon,
            headerIconRole = CliSheetHeaderIconRole.INFORMATION,
        ) {
            if (presentation.itemIcons.isEmpty()) {
                CliHelpNote(text = cliTopBarHelpBody(body))
            } else {
                val items = remember(body, presentation.itemIcons) {
                    cliIconTextItems(body = body, icons = presentation.itemIcons)
                }
                CliIconTextItems(items = items, framed = true, iconColor = colors.info)
            }
            if (additionalContent != null) {
                Spacer(modifier = Modifier.height(CliSpacing.md))
                additionalContent()
            }
        }
    }
}

internal data class CliTopBarHelpPresentation(
    @StringRes val titleRes: Int = R.string.cli_context_help_title,
    @DrawableRes val icon: Int = R.drawable.lin_help,
    val itemIcons: List<Int> = emptyList(),
)

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
    alignIconToFirstLine: Boolean = false,
    firstLineText: String? = null,
    iconSize: Dp? = null,
    iconOffsetY: Dp? = null,
) {
    val colors = LocalCliColors.current
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val topBarIconSize = CLI_TOP_BAR_ACTION_ICON_SIZE
    CliHeaderIconButton(
        icon = R.drawable.lin_help,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        controlSize = if (topBar) CliTopBarControlSize else CliSectionHeaderControlSize,
        iconSize = if (topBar) {
            topBarIconSize
        } else {
            iconSize ?: CLI_SECTION_HEADER_ICON_SIZE
        },
        tint = colors.info,
        contentAlignment = when {
            topBar && alignIconToFirstLine -> Alignment.TopEnd
            topBar -> Alignment.CenterEnd
            alignIconToFirstLine -> Alignment.TopCenter
            else -> Alignment.Center
        },
        iconOffsetY = iconOffsetY ?: cliHeaderHelpIconOffsetFor(
            topBar = topBar,
            alignIconToFirstLine = alignIconToFirstLine,
            firstLineText = firstLineText,
            pixelArtEnabled = pixelArtEnabled,
        ),
    )
}

@Composable
internal fun CliTopBarIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    CliHeaderIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        controlSize = CliTopBarControlSize,
        iconSize = CLI_TOP_BAR_ACTION_ICON_SIZE,
        tint = tint,
        enabled = enabled,
        contentAlignment = Alignment.CenterEnd,
        iconOffsetY = cliTopBarIconLiftFor(pixelArtEnabled),
    )
}

@Composable
internal fun CliTopBarSettingsButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    CliHeaderIconButton(
        icon = R.drawable.lin_settings,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        controlSize = CliTopBarControlSize,
        iconSize = CLI_TOP_BAR_ACTION_ICON_SIZE,
        tint = tint,
        enabled = enabled,
        contentAlignment = Alignment.CenterEnd,
        iconOffsetY = cliTopBarIconLiftFor(pixelArtEnabled),
    )
}

@Composable
private fun CliHeaderIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    controlSize: Dp,
    iconSize: Dp,
    tint: Color,
    enabled: Boolean = true,
    contentAlignment: Alignment,
    iconOffsetY: Dp = 0.dp,
) {
    CliHeaderIcon(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier
            .requiredSize(controlSize)
            .cliPressable(enabled = enabled, role = Role.Button, onClick = onClick),
        iconSize = iconSize,
        tint = tint,
        contentAlignment = contentAlignment,
        iconOffsetY = iconOffsetY,
    )
}

@Composable
internal fun CliSectionHeaderIcon(
    @DrawableRes icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    controlWidth: Dp = CliSectionHeaderControlSize,
    iconSize: Dp? = null,
) {
    CliHeaderIcon(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier.requiredSize(controlWidth, CliSectionHeaderControlSize),
        iconSize = iconSize ?: CLI_SECTION_HEADER_ICON_SIZE,
        tint = tint,
        contentAlignment = Alignment.TopCenter,
        iconOffsetY = CLI_SECTION_HEADER_ICON_OFFSET,
    )
}

@Composable
private fun CliHeaderIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier,
    iconSize: Dp,
    tint: Color,
    contentAlignment: Alignment,
    iconOffsetY: Dp = 0.dp,
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        CliIcon(
            id = icon,
            contentDescription = contentDescription,
            size = iconSize,
            tint = tint,
            modifier = Modifier.offset(y = iconOffsetY),
        )
    }
}

internal fun cliHeaderIconFirstLineOffsetFor(
    text: String,
    pixelArtEnabled: Boolean = true,
): Dp = cliHeadingGlyphOpticalOffsetFor(text, pixelArtEnabled) - CLI_ICON_OPTICAL_OFFSET

internal fun cliHeaderHelpIconOffsetFor(
    topBar: Boolean,
    alignIconToFirstLine: Boolean,
    firstLineText: String?,
    pixelArtEnabled: Boolean = true,
): Dp = when {
    alignIconToFirstLine && firstLineText != null ->
        cliHeaderIconFirstLineOffsetFor(firstLineText, pixelArtEnabled) +
            if (topBar) CLI_HEADER_ICON_LIFT else 0.dp
    !topBar && alignIconToFirstLine -> CLI_SECTION_HEADER_ICON_OFFSET
    topBar -> cliTopBarIconLiftFor(pixelArtEnabled)
    else -> 0.dp
}

internal fun cliTopBarIconLiftFor(pixelArtEnabled: Boolean = true): Dp =
    CLI_TOP_BAR_ICON_LIFT + cliTopBarModeIconLiftFor("", pixelArtEnabled)

internal val CliSectionHeaderControlSize = 21.dp
internal val CliTopBarControlSize = 48.dp
internal val CLI_TOP_BAR_ACTION_ICON_SIZE = 20.dp
internal val CliTopBarIconSize = CLI_TOP_BAR_ACTION_ICON_SIZE
internal val CLI_TOP_BAR_ICON_LIFT = (-7).dp
internal val CLI_SECTION_HEADER_ICON_SIZE = 18.dp
internal val CLI_SECTION_HEADER_ICON_OFFSET = 0.dp
private val CLI_HELP_NOTE_ICON_SIZE = 16.dp
private val CLI_HELP_NOTE_ICON_GAP = 4.dp
