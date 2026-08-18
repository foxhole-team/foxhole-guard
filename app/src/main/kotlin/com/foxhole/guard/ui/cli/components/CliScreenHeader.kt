package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliScaledSp

@Composable
internal fun CliScreenHeader(
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconGlyph: String? = null,
    suffix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                CliPixIcon(
                    id = icon,
                    contentDescription = null,
                    size = CliHeaderIconSize,
                    tint = colors.accent,
                )
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            } else if (iconGlyph != null) {
                Box(modifier = Modifier.size(CliHeaderIconSize), contentAlignment = Alignment.Center) {
                    Text(
                        text = iconGlyph,
                        style = CliType.small.copy(fontSize = cliScaledSp(16f), lineHeight = cliScaledSp(16f)),
                        color = colors.accent,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            if (LocalCliVisualStyle.current == VisualStyle.PIXEL) {
                val brandText = stringResource(R.string.cli_screen_header)
                Text(
                    text = brandText,
                    style = cliDisplayStyle(brandText),
                    color = colors.accent,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            Text(
                text = cliLabelText(label),
                style = cliDisplayStyle(label),
                color = colors.accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = trailing != null),
            )
            if (suffix != null) {
                Text(
                    text = " · $suffix",
                    style = CliType.small,
                    color = colors.dim,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            Box(
                modifier = Modifier.height(CliHeaderControlSlotHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                trailing()
            }
        }
    }
}

internal val CliHeaderControlSlotHeight = 21.dp
internal val CliHeaderIconSize = 18.dp

@Composable
internal fun CliBackRow(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(onClick = onBack),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "← ", style = CliType.body, color = colors.accent)
        Text(
            text = label,
            style = CliType.body,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
