package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.cliHeadingGlyphOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliScaledSp
import com.foxhole.guard.ui.cli.cliScreenHeadingOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliScreenTitleStyle

@Composable
internal fun CliScreenHeader(
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconGlyph: String? = null,
    suffix: String? = null,
    titleColor: Color = Color.Unspecified,
    iconColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val iconSize = cliScreenHeaderGlyphSize()
    val shownLabel = cliHeadingText(label)
    val resolvedTitleColor = if (titleColor == Color.Unspecified) colors.accent else titleColor
    val resolvedIconColor = if (iconColor == Color.Unspecified) colors.accent else iconColor
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CliScreenHeaderContentHeight)
                .offset(y = cliScreenHeaderRowOffsetFor(pixelArtEnabled)),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .width(CLI_SCREEN_HEADER_LEADING_SLOT_WIDTH)
                        .height(CliScreenHeaderContentHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    CliIcon(
                        id = icon,
                        contentDescription = null,
                        size = iconSize,
                        tint = resolvedIconColor,
                        modifier = Modifier.offset(
                            y = cliScreenHeaderIconOffsetFor(shownLabel, pixelArtEnabled),
                        ),
                    )
                }
            } else if (iconGlyph != null) {
                Box(
                    modifier = Modifier
                        .width(CLI_SCREEN_HEADER_LEADING_SLOT_WIDTH)
                        .height(CliScreenHeaderContentHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = iconGlyph,
                        style = CliType.small.copy(
                            fontSize = cliTopBarGlyphTextSizeFor(pixelArtEnabled),
                            lineHeight = cliTopBarGlyphTextSizeFor(pixelArtEnabled),
                        ),
                        color = resolvedIconColor,
                        maxLines = 1,
                        modifier = Modifier.offset(
                            y = cliScreenHeaderIconOffsetFor(shownLabel, pixelArtEnabled),
                        ),
                    )
                }
            }
            Text(
                text = shownLabel,
                style = cliScreenTitleStyle(shownLabel),
                color = resolvedTitleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = CliScreenHeaderTitleTopPadding)
                    .offset(y = cliScreenHeadingOpticalOffsetFor(shownLabel, pixelArtEnabled)),
            )
            if (suffix != null) {
                Box(
                    modifier = Modifier.height(CliScreenHeaderContentHeight),
                    contentAlignment = Alignment.Center,
                ) {
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
                    modifier = Modifier.height(CliTopBarControlSize),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    trailing()
                }
            }
        }
        Spacer(modifier = Modifier.height(CliScreenHeaderBottomGap))
    }
}

internal val CliHeaderControlSlotHeight = CliSectionHeaderControlSize
internal val CLI_SCREEN_HEADER_ICON_SIZE = 18.dp

private val CLI_SCREEN_HEADER_LEADING_SLOT_WIDTH = 24.dp

@Composable
internal fun cliScreenHeaderGlyphSize(): Dp {
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    return LocalCliIconMetricOverrides.current.screenHeaderIconSize
        ?: cliTopBarGlyphSizeFor(pixelArtEnabled)
}

internal fun cliTopBarGlyphSizeFor(
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean,
): Dp = CLI_SCREEN_HEADER_ICON_SIZE

private fun cliTopBarGlyphTextSizeFor(
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean,
) = cliScaledSp(15f)

internal fun cliScreenHeaderIconOffsetFor(
    text: String,
    pixelArtEnabled: Boolean = true,
): Dp = cliTopBarModeIconLiftFor(text, pixelArtEnabled) + CLI_SCREEN_HEADER_ICON_LIFT

internal fun cliTopBarModeIconLiftFor(
    text: String,
    pixelArtEnabled: Boolean = true,
): Dp = cliHeadingGlyphOpticalOffsetFor(text, pixelArtEnabled) + CLI_HEADER_ICON_LIFT

internal fun cliScreenHeaderRowOffsetFor(
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean = true,
): Dp = 0.dp

internal val CliScreenHeaderContentHeight = CliTopBarControlSize
internal val CliScreenHeaderTitleTopPadding = 10.dp
internal val CliScreenHeaderBottomGap = 2.dp
internal val CLI_SCREEN_HEADER_ICON_LIFT = (-6).dp

@Composable
internal fun CliBackRow(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val shownLabel = cliHeadingText(label)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .cliPressable(onClick = onBack),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CliScreenHeaderContentHeight)
                .offset(y = cliScreenHeaderRowOffsetFor(pixelArtEnabled)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "← ",
                style = CliType.body,
                color = colors.accent,
                modifier = Modifier.offset(y = CLI_HEADER_ICON_LIFT),
            )
            Text(
                text = shownLabel,
                style = cliScreenTitleStyle(shownLabel),
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(
                    y = cliScreenHeadingOpticalOffsetFor(shownLabel, pixelArtEnabled),
                ),
            )
        }
        Spacer(modifier = Modifier.height(CliScreenHeaderBottomGap))
    }
}
