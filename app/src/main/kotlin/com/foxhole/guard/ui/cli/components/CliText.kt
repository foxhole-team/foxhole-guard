package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/** `key ......... value` row: dim key at the start, colored value at the end; [icon] —
 * an optional pixel glyph before the key; [valueLeading] an optional graphic before the value,
 * such as the geo flag, in the row's shared grammar. */
@Composable
internal fun CliKeyValue(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    valueLeading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                CliPixIcon(id = icon, contentDescription = null, size = 12.dp, tint = colors.dim)
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
            if (valueLeading != null) {
                valueLeading()
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            Text(
                text = value,
                style = CliType.body,
                color = if (valueColor == Color.Unspecified) colors.fg else valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `⎿ text` sub-row - the elbow connector for nested detail lines. */
@Composable
internal fun CliElbowLine(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    Text(
        text = "⎿ $text",
        style = CliType.small,
        color = if (color == Color.Unspecified) colors.dim else color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
