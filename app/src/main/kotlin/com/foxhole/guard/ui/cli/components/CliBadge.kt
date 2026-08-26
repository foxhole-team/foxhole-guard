package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.cliMetricSp

@Composable
internal fun CliBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(BADGE_CORNER)
    Text(
        text = text,
        style = CliType.small.copy(
            fontSize = cliMetricSp(BADGE_TEXT_BASE_SP),
            lineHeight = cliMetricSp(BADGE_LINE_BASE_SP),
        ),
        color = color,
        maxLines = 1,
        modifier = modifier
            .offset(y = CLI_BADGE_VERTICAL_OFFSET)
            .clip(shape)
            .background(color.copy(alpha = BADGE_FILL_ALPHA))
            .border(1.dp, color.copy(alpha = BADGE_EDGE_ALPHA), shape)
            .padding(horizontal = BADGE_PADDING_H, vertical = BADGE_PADDING_V),
    )
}

@Composable
internal fun CliBadgedText(
    text: String,
    style: TextStyle,
    color: Color,
    badge: String,
    badgeColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(text = text, style = style, color = color, maxLines = 1)
        Spacer(modifier = Modifier.width(BADGE_GAP))
        CliBadge(text = badge, color = badgeColor)
    }
}

private const val BADGE_TEXT_BASE_SP = 8f
private const val BADGE_LINE_BASE_SP = 9f
private const val BADGE_FILL_ALPHA = 0.16f
private const val BADGE_EDGE_ALPHA = 0.55f
private val BADGE_CORNER = 4.dp
private val BADGE_PADDING_H = 4.dp
private val BADGE_PADDING_V = 1.dp
private val BADGE_GAP = 3.dp

internal val CLI_BADGE_VERTICAL_OFFSET = (-1).dp
