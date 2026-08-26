package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliRowTextStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliKeyValue(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    iconColor: Color = Color.Unspecified,
    valueLeading: (@Composable () -> Unit)? = null,
    valueTrailing: (@Composable () -> Unit)? = null,
    valueContent: (@Composable () -> Unit)? = null,
    animateValue: Boolean = false,
    valueMaxLines: Int = 1,
    keyColumnWeight: Float = 1f,
    valueColumnWeight: Float = 1f,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = cliSpinnerSlotSize),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(keyColumnWeight, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                CliIcon(
                    id = icon,
                    contentDescription = null,
                    size = CLI_KEY_VALUE_ICON_SIZE,
                    tint = if (iconColor == Color.Unspecified) colors.dim else iconColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = cliKeyLabelText(key),
                style = cliRowTextStyle(),
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.weight(valueColumnWeight, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (valueContent != null) {
                valueContent()
            } else {
                if (valueLeading != null) {
                    valueLeading()
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                }
                val resolvedValueColor =
                    if (valueColor == Color.Unspecified) colors.fg else valueColor
                Text(
                    text = rememberCliTypedText(cliLabelText(value), enabled = animateValue),
                    style = cliRowTextStyle(),
                    color = resolvedValueColor,
                    textAlign = TextAlign.End,
                    maxLines = valueMaxLines,
                    overflow = if (valueMaxLines == 1) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (valueMaxLines == 1) {
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = VALUE_MARQUEE_DELAY_MS,
                                    repeatDelayMillis = VALUE_MARQUEE_REPEAT_MS,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
                if (valueTrailing != null) {
                    valueTrailing()
                }
            }
        }
    }
}

internal val CLI_KEY_VALUE_ICON_SIZE = 16.dp

@Composable
@ReadOnlyComposable
private fun cliKeyLabelText(key: String): String {
    val capital = key.replaceFirstChar { char -> char.uppercaseChar() }
    return if (capital.isEmpty() || capital.endsWith(":")) capital else "$capital:"
}

@Composable
internal fun CliElbowLine(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    CliInfoLine(
        text = text,
        color = if (color == Color.Unspecified) colors.info else color,
        maxLines = 2,
        modifier = modifier,
    )
}

@Composable
internal fun CliInfoNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        CliRowDivider(color = colors.info)
        CliInfoLine(text = text, color = colors.info, maxLines = 3)
    }
}

@Composable
internal fun CliDashedInfoNote(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.lin_info,
    outerVerticalPadding: Dp = CliSpacing.xs,
    centered: Boolean = false,
    centeredIconLeading: Boolean = false,
    centeredIconFirstLine: Boolean = false,
    centeredIconGap: Dp = CliSpacing.sm,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val noteColor = if (color == Color.Unspecified) colors.info else color
    val frame = Modifier.border(1.dp, noteColor.copy(alpha = 0.6f), RoundedCornerShape(CliRadius.panel))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = outerVerticalPadding)
            .then(frame)
            .padding(horizontal = CliSpacing.md, vertical = CliSpacing.md),
    ) {
        if (centered) {
            CliCenteredInfoBlock(
                text = text,
                color = noteColor,
                icon = icon,
                iconLeading = centeredIconLeading,
                iconFirstLine = centeredIconFirstLine,
                iconGap = centeredIconGap,
            )
        } else {
            Row(verticalAlignment = Alignment.Top) {
                CliFirstLineIcon(
                    id = icon,
                    size = CliIconSize.note,
                    lineHeight = CliType.small.lineHeight,
                    tint = noteColor,
                )
                Spacer(modifier = Modifier.width(CliSpacing.sm))
                Text(
                    text = cliLabelText(text),
                    style = CliType.small,
                    color = noteColor,
                )
            }
        }
    }
}

@Composable
internal fun CliCenteredEmptyNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = CLI_INFO_LINE_VERTICAL_PADDING),
    ) {
        CliCenteredInfoBlock(
            text = text,
            color = if (color == Color.Unspecified) colors.info else color,
        )
    }
}

@Composable
private fun CliCenteredInfoBlock(
    text: String,
    color: Color,
    @DrawableRes icon: Int = R.drawable.lin_info,
    iconLeading: Boolean = false,
    iconFirstLine: Boolean = false,
    iconGap: Dp = CliSpacing.sm,
) {
    if (iconLeading) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = if (iconFirstLine) Alignment.Top else Alignment.CenterVertically,
        ) {
            if (iconFirstLine) {
                CliFirstLineIcon(
                    id = icon,
                    size = CliIconSize.note,
                    lineHeight = CliType.small.lineHeight,
                    tint = color,
                )
            } else {
                CliIcon(
                    id = icon,
                    contentDescription = null,
                    size = CliIconSize.note,
                    tint = color,
                )
            }
            Spacer(modifier = Modifier.width(iconGap))
            Text(
                text = cliLabelText(text),
                style = CliType.small,
                color = color,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CliIcon(
            id = icon,
            contentDescription = null,
            size = CliIconSize.note,
            tint = color,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Text(
            text = cliLabelText(text),
            style = CliType.small,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CliInfoLine(
    text: String,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = CLI_INFO_LINE_VERTICAL_PADDING),
        verticalAlignment = Alignment.Top,
    ) {
        CliFirstLineIcon(
            id = R.drawable.lin_info,
            size = CliIconSize.glyph,
            lineHeight = CliType.small.lineHeight,
            tint = color,
        )
        Spacer(modifier = Modifier.width(CliSpacing.xs))
        Text(
            text = cliLabelText(text),
            style = CliType.small,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val CLI_INFO_LINE_VERTICAL_PADDING = 3.dp

private const val VALUE_MARQUEE_DELAY_MS = 1200
private const val VALUE_MARQUEE_REPEAT_MS = 2400
