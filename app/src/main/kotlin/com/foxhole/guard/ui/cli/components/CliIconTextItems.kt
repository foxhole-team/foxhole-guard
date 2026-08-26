@file:Suppress("MatchingDeclarationName")

package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

@Immutable
internal data class CliIconTextItem(
    val text: String,
    @DrawableRes val icon: Int,
)

internal fun cliIconTextItems(
    body: String,
    icons: List<Int>,
): List<CliIconTextItem> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, text ->
            CliIconTextItem(
                text = text,
                icon = icons.getOrElse(index) { R.drawable.lin_info },
            )
        }
        .toList()

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliIconTextItems(
    items: List<CliIconTextItem>,
    framed: Boolean,
    modifier: Modifier = Modifier,
    iconColor: Color = Color.Unspecified,
    trailingIcons: Map<Int, List<Int>> = emptyMap(),
    marquee: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        items.forEachIndexed { index, item ->
            if (framed) {
                CliPanel(modifier = Modifier.fillMaxWidth()) {
                    CliIconTextItemRow(item, iconColor, trailingIcons[index].orEmpty(), marquee)
                }
            } else {
                if (index > 0) CliRowDivider()
                CliIconTextItemRow(item, iconColor, trailingIcons[index].orEmpty(), marquee)
            }
        }
    }
}

@Composable
private fun CliIconTextItemRow(
    item: CliIconTextItem,
    iconColor: Color,
    trailingIcons: List<Int>,
    marquee: Boolean,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        CliFirstLineIcon(
            id = item.icon,
            size = 16.dp,
            lineHeight = ICON_TEXT_LINE_HEIGHT,
            tint = if (iconColor == Color.Unspecified) colors.info else iconColor,
            modifier = Modifier.offset(y = CLI_HELP_CONTENT_ICON_LIFT),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = item.text,
            style = CliType.body.copy(lineHeight = ICON_TEXT_LINE_HEIGHT),
            color = colors.fg,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (marquee) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = ICON_TEXT_MARQUEE_DELAY_MS,
                            repeatDelayMillis = ICON_TEXT_MARQUEE_REPEAT_MS,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        if (trailingIcons.isNotEmpty()) {
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            Row(
                modifier = Modifier.offset(y = CLI_HELP_CONTENT_ICON_LIFT),
                horizontalArrangement = Arrangement.spacedBy(CLI_ACTION_ICON_GAP),
            ) {
                trailingIcons.forEach { icon ->
                    CliIcon(
                        id = icon,
                        contentDescription = null,
                        size = CLI_ACTION_ICON_SIZE,
                        tint = if (iconColor == Color.Unspecified) colors.info else iconColor,
                    )
                }
            }
        }
    }
}

private val ICON_TEXT_LINE_HEIGHT = 22.sp
private val CLI_ACTION_ICON_SIZE = 14.dp
private val CLI_ACTION_ICON_GAP = 2.dp
internal val CLI_HELP_CONTENT_ICON_LIFT = (-1).dp
private const val ICON_TEXT_MARQUEE_DELAY_MS = 1_200
private const val ICON_TEXT_MARQUEE_REPEAT_MS = 1_000
