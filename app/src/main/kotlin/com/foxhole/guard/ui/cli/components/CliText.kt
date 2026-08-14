package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/** `key ......... value` row: dim key at the start, colored value at the end; [icon] —
 * an optional pixel glyph before the key; [valueLeading] an optional graphic before the value,
 * such as the geo flag, and [valueTrailing] an optional disclosure glyph after it, in the row's
 * shared grammar. Values swap with no flash or motion:
 * live rows (traffic, uptime) tick every second, and any change highlight became a
 * permanent blink there. */
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
) {
    val colors = LocalCliColors.current
    Row(
        // The trailing value can swap between body text and the canonical 16dp spinner. Reserve
        // that slot height on every frame so connect/refresh cannot move neighbouring rows.
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = cliSpinnerSlotSize),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                CliPixIcon(
                    id = icon,
                    contentDescription = null,
                    size = 12.dp,
                    tint = if (iconColor == Color.Unspecified) colors.dim else iconColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = key,
                style = CliType.body,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Flag and value are a single child of the SpaceBetween row, or the distribution would
        // drag the graphic into the middle, away from its value.
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    text = value,
                    style = CliType.body,
                    color = resolvedValueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (valueTrailing != null) {
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                    valueTrailing()
                }
            }
        }
    }
}

/** Informational sub-row. Every note carries the same `pix_info`; its tone can still warn/fail. */
@Composable
internal fun CliElbowLine(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    CliInfoLine(
        text = text,
        color = if (color == Color.Unspecified) colors.note else color,
        maxLines = 2,
        modifier = modifier,
    )
}

/** Blue info note separated by a horizontal pixel stitch, never a boxed/dashed outline. */
@Composable
internal fun CliInfoNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        CliRowDivider(color = colors.note)
        CliInfoLine(text = text, color = colors.note, maxLines = 3)
    }
}

/**
 * Centred passive notice used where the note itself is a distinct, non-interactive section.
 * [outerVerticalPadding] belongs outside the contour so dense screens may opt out explicitly;
 * loader and progress geometry never inherits it because those components do not use this note.
 */
@Composable
internal fun CliDashedInfoNote(
    text: String,
    modifier: Modifier = Modifier,
    outerVerticalPadding: Dp = CliSpacing.xs,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Keep passive info contours visually separate from the rows above and below. This
            // lives here so every info block gets the same air without touching loader geometry.
            .padding(vertical = outerVerticalPadding)
            .cliDashedBorder(colors.note)
            .padding(horizontal = CliSpacing.md, vertical = CliSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CliPixIcon(
            id = R.drawable.pix_info,
            contentDescription = null,
            size = 18.dp,
            tint = colors.note,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Text(
            text = text,
            style = CliType.small,
            color = colors.note,
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
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        CliPixIcon(
            id = R.drawable.pix_info,
            contentDescription = null,
            size = 12.dp,
            tint = color,
            modifier = Modifier.offset(y = (-1).dp),
        )
        Spacer(modifier = Modifier.width(CliSpacing.xs))
        Text(
            text = text,
            style = CliType.small,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
