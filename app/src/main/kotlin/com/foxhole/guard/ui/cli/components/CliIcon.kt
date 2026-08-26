package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.foxhole.guard.ui.cli.CLI_UNIFIED_METRIC_SCALE
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.LocalCliColors

@Composable
internal fun CliIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = CliIconSize.row,
    tint: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val drawnSize = size * CLI_ICON_DRAW_SCALE
    val filter = ColorFilter.tint(if (tint == Color.Unspecified) colors.fg else tint)
    val a11y = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier.size(size).then(a11y),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id),
            contentDescription = null,
            modifier = Modifier.size(drawnSize),
            colorFilter = filter,
        )
    }
}

internal const val CLI_ICON_DRAW_SCALE = CLI_UNIFIED_METRIC_SCALE

@Composable
internal fun CliFirstLineIcon(
    @DrawableRes id: Int,
    size: Dp,
    lineHeight: TextUnit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val lineHeightDp = with(LocalDensity.current) { lineHeight.toDp() }
    Box(
        modifier = modifier
            .width(size)
            .height(maxOf(size, lineHeightDp)),
        contentAlignment = Alignment.Center,
    ) {
        CliIcon(
            id = id,
            contentDescription = null,
            size = size,
            tint = tint,
        )
    }
}
