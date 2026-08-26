package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliScreen
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.cliFontSizeForMode
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliScaledSp

@Composable
internal fun CliHintBar(
    current: CliScreen,
    onSelect: (CliScreen) -> Unit,
    modifier: Modifier = Modifier,
    screens: List<CliScreen> = CliScreen.entries,
) {
    CliDock(current = current, onSelect = onSelect, screens = screens, modifier = modifier)
}

@Composable
private fun CliDock(
    current: CliScreen,
    onSelect: (CliScreen) -> Unit,
    screens: List<CliScreen>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(CLI_DOCK_CORNER)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = CliSpacing.sm,
                end = CliSpacing.sm,
                top = CLI_DOCK_LIFT_TOP,
                bottom = CLI_DOCK_LIFT_BOTTOM,
            )
            .testTag(CLI_DOCK_TAG),
    ) {
        val itemWidth = maxWidth / screens.size
        val compact = itemWidth < CLI_DOCK_COMPACT_ITEM_WIDTH
        val dropWidth = if (compact) CLI_DOCK_DROP_WIDTH_COMPACT else CLI_DOCK_DROP_WIDTH
        val dropHeight = if (compact) CLI_DOCK_DROP_HEIGHT_COMPACT else CLI_DOCK_DROP_HEIGHT
        val dropTop = if (compact) CLI_DOCK_DROP_TOP_COMPACT else CLI_DOCK_DROP_TOP
        val activeIndex = screens.indexOf(current).coerceAtLeast(0)
        val dropX by animateDpAsState(
            targetValue = itemWidth * activeIndex + (itemWidth - dropWidth) / 2,
            animationSpec = CliMotion.emphasis(),
            label = "cliDockDrop",
        )
        CliGlassSurface(
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .cliDockVisualDrop()
                .border(1.dp, colors.border, shape),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = dropX)
                    .padding(top = dropTop)
                    .width(dropWidth)
                    .height(dropHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.accent.copy(alpha = CLI_DOCK_DROP_ALPHA)),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                screens.forEach { screen ->
                    CliDockItem(
                        screen = screen,
                        active = screen == current,
                        onSelect = onSelect,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CliDockItem(
    screen: CliScreen,
    active: Boolean,
    onSelect: (CliScreen) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val semanticLabel = cliLabelText(stringResource(dockLabel(screen)))
    val label = semanticLabel.uppercase()
    val tint = if (active) colors.accent else colors.dim
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) CLI_DOCK_PRESS_SCALE else 1f,
        animationSpec = CliMotion.press(),
        label = "cliDockPressScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) CLI_DOCK_PRESS_GLOW_ALPHA else 0f,
        animationSpec = CliMotion.press(),
        label = "cliDockPressGlow",
    )
    Column(
        modifier = modifier
            .height(if (compact) CLI_DOCK_MIN_HEIGHT_COMPACT else CLI_DOCK_MIN_HEIGHT)
            .testTag(cliDockItemTag(screen))
            .clip(RoundedCornerShape(CLI_DOCK_CORNER))
            .background(colors.accent.copy(alpha = glowAlpha))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onSelect(screen) }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .padding(
                top = if (compact) {
                    CLI_DOCK_ITEM_PADDING_COMPACT + CLI_DOCK_CONTENT_SHIFT
                } else {
                    CLI_DOCK_ITEM_PADDING + CLI_DOCK_CONTENT_SHIFT
                },
                bottom = if (compact) {
                    CLI_DOCK_ITEM_PADDING_COMPACT - CLI_DOCK_CONTENT_SHIFT
                } else {
                    CLI_DOCK_ITEM_PADDING - CLI_DOCK_CONTENT_SHIFT
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        CliIcon(
            id = dockIcon(screen),
            contentDescription = semanticLabel,
            tint = tint,
            size = if (compact) {
                CLI_DOCK_ICON_SIZE_COMPACT / CLI_ICON_DRAW_SCALE
            } else {
                CLI_DOCK_ICON_SIZE / CLI_ICON_DRAW_SCALE
            },
        )
        Spacer(modifier = Modifier.height(2.dp))
        val labelStyle = cliDockLabelStyle(compact)
        BasicText(
            text = label,
            style = labelStyle.copy(color = tint, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = cliScaledSp(CLI_DOCK_LABEL_MIN_FONT_SIZE),
                maxFontSize = labelStyle.fontSize,
                stepSize = cliScaledSp(CLI_DOCK_LABEL_FONT_STEP),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CLI_DOCK_LABEL_SIDE_GAP),
        )
    }
}

@Composable
private fun cliDockLabelStyle(compact: Boolean) = CliType.button.copy(
    fontSize = cliFontSizeForMode(
        cliScaledSp(if (compact) 10f else 11f),
        LocalCliPixelArtEnabled.current,
    ),
    lineHeight = cliScaledSp(if (compact) 12f else 13f),
)

private val CLI_DOCK_CORNER = 22.dp
private val CLI_DOCK_DROP_WIDTH = 48.dp
private val CLI_DOCK_DROP_WIDTH_COMPACT = 40.dp
private val CLI_DOCK_DROP_HEIGHT = 32.dp
private val CLI_DOCK_DROP_HEIGHT_COMPACT = 27.dp
private val CLI_DOCK_ICON_SIZE = 24.dp
private val CLI_DOCK_ICON_SIZE_COMPACT = 20.dp

private val CLI_DOCK_COMPACT_ITEM_WIDTH = 52.dp
private val CLI_DOCK_LABEL_SIDE_GAP = 2.dp
private const val CLI_DOCK_LABEL_MIN_FONT_SIZE = 7f
private const val CLI_DOCK_LABEL_FONT_STEP = 0.5f

private val CLI_DOCK_ITEM_PADDING = 9.dp
private val CLI_DOCK_ITEM_PADDING_COMPACT = 7.dp
internal val CLI_DOCK_CONTENT_SHIFT = 2.dp
private val CLI_DOCK_DROP_TOP =
    CLI_DOCK_ITEM_PADDING + CLI_DOCK_CONTENT_SHIFT -
        (CLI_DOCK_DROP_HEIGHT - CLI_DOCK_ICON_SIZE) / 2
private val CLI_DOCK_DROP_TOP_COMPACT =
    CLI_DOCK_ITEM_PADDING_COMPACT + CLI_DOCK_CONTENT_SHIFT -
        (CLI_DOCK_DROP_HEIGHT_COMPACT - CLI_DOCK_ICON_SIZE_COMPACT) / 2
private val CLI_DOCK_MIN_HEIGHT = 61.dp
private val CLI_DOCK_MIN_HEIGHT_COMPACT = 51.dp
private val CLI_DOCK_LIFT_TOP = 0.dp
private val CLI_DOCK_LIFT_BOTTOM = 0.dp
private val CLI_DOCK_VISUAL_DROP = 2.dp
private const val CLI_DOCK_DROP_ALPHA = 0.16f
private const val CLI_DOCK_PRESS_SCALE = 0.90f
private const val CLI_DOCK_PRESS_GLOW_ALPHA = 0.10f

internal const val CLI_DOCK_TAG = "cli_dock"

internal fun cliDockItemTag(screen: CliScreen): String = "cli_dock_${screen.name.lowercase()}"

private fun Modifier.cliDockVisualDrop(): Modifier = drawWithContent {
    translate(top = CLI_DOCK_VISUAL_DROP.toPx()) {
        this@drawWithContent.drawContent()
    }
}

private fun dockIcon(screen: CliScreen): Int = when (screen) {
    CliScreen.HOME -> R.drawable.lin_home
    CliScreen.PROFILES -> R.drawable.lin_profiles
    CliScreen.APPS -> R.drawable.lin_apps
    CliScreen.MAP -> R.drawable.lin_map
    CliScreen.WEBAPPS -> R.drawable.lin_webapps
    CliScreen.STATS -> R.drawable.lin_stats
    CliScreen.SETTINGS -> R.drawable.lin_settings
}

private fun dockLabel(screen: CliScreen): Int = when (screen) {
    CliScreen.HOME -> R.string.cli_dock_home
    CliScreen.PROFILES -> R.string.cli_dock_profiles
    CliScreen.APPS -> R.string.cli_dock_apps
    CliScreen.MAP -> R.string.cli_dock_map
    CliScreen.WEBAPPS -> R.string.cli_dock_webapps
    CliScreen.STATS -> R.string.cli_dock_stats
    CliScreen.SETTINGS -> R.string.cli_dock_settings
}

@Composable
internal fun CliDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    CliPixelStitch(
        alpha = 0.7f,
        color = color,
        modifier = modifier.cliPanelRowContentPadding(),
    )
}

@Composable
internal fun CliRowDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    CliPixelStitch(
        alpha = 0.35f,
        color = color,
        modifier = modifier
            .cliPanelRowContentPadding()
            .padding(vertical = 3.dp),
    )
}

@Composable
internal fun CliSectionDivider(modifier: Modifier = Modifier) {
    CliPixelStitch(alpha = 0.5f, modifier = modifier.padding(vertical = CliSpacing.xs))
}

@Composable
internal fun CliHomeSectionGap(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.height(CliSpacing.sm))
}

@Composable
private fun CliPixelStitch(
    alpha: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val stitchColor = (if (color == Color.Unspecified) colors.border else color).copy(alpha = alpha)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        val dash = 2.dp.toPx()
        val gap = 2.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawRect(
                color = stitchColor,
                topLeft = Offset(x, 0f),
                size = Size(minOf(dash, size.width - x), size.height),
            )
            x += dash + gap
        }
    }
}
