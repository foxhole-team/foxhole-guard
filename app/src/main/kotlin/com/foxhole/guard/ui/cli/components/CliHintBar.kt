package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliScreen
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliMetricScale
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliScaledSp

/**
 * Both styles share the rounded dock now; retro merely keeps its own palette underneath.
 *
 * The dock is the app's fixed furniture, so it renders at the reference scale in both styles —
 * [LocalCliMetricScale] is re-provided as 1f here and the Modern notch stops at its edge.
 */
@Composable
internal fun CliHintBar(
    current: CliScreen,
    onSelect: (CliScreen) -> Unit,
    modifier: Modifier = Modifier,
    screens: List<CliScreen> = CliScreen.entries,
) {
    CompositionLocalProvider(LocalCliMetricScale provides CLI_DOCK_METRIC_SCALE) {
        CliModernDock(current = current, onSelect = onSelect, screens = screens, modifier = modifier)
    }
}

private const val CLI_DOCK_METRIC_SCALE = 1f

@Composable
private fun CliModernDock(
    current: CliScreen,
    onSelect: (CliScreen) -> Unit,
    screens: List<CliScreen>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(MODERN_DOCK_CORNER)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = CliSpacing.sm,
                end = CliSpacing.sm,
                top = MODERN_DOCK_LIFT_TOP,
                bottom = MODERN_DOCK_LIFT_BOTTOM,
            )
            .testTag(CLI_DOCK_TAG),
    ) {
        val itemWidth = maxWidth / screens.size
        val compact = itemWidth < MODERN_DOCK_COMPACT_ITEM_WIDTH
        val dropWidth = if (compact) MODERN_DOCK_DROP_WIDTH_COMPACT else MODERN_DOCK_DROP_WIDTH
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
                .border(1.dp, colors.border, shape),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = dropX)
                    .padding(top = if (compact) MODERN_DOCK_DROP_TOP_COMPACT else MODERN_DOCK_DROP_TOP)
                    .width(dropWidth)
                    .height(if (compact) MODERN_DOCK_DROP_HEIGHT_COMPACT else MODERN_DOCK_DROP_HEIGHT)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.accent.copy(alpha = MODERN_DOCK_DROP_ALPHA)),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                screens.forEach { screen ->
                    CliModernDockItem(
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
private fun CliModernDockItem(
    screen: CliScreen,
    active: Boolean,
    onSelect: (CliScreen) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val tint = if (active) colors.accent else colors.dim
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) MODERN_DOCK_PRESS_SCALE else 1f,
        animationSpec = CliMotion.press(),
        label = "cliDockPressScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) MODERN_DOCK_PRESS_GLOW_ALPHA else 0f,
        animationSpec = CliMotion.press(),
        label = "cliDockPressGlow",
    )
    Column(
        modifier = modifier
            .defaultMinSize(
                minHeight = if (compact) MODERN_DOCK_MIN_HEIGHT_COMPACT else MODERN_DOCK_MIN_HEIGHT,
            )
            .testTag(cliDockItemTag(screen))
            .clip(RoundedCornerShape(MODERN_DOCK_CORNER))
            .background(colors.accent.copy(alpha = glowAlpha))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onSelect(screen) }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .padding(vertical = if (compact) MODERN_DOCK_ITEM_PADDING_COMPACT else MODERN_DOCK_ITEM_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CliPixIcon(
            id = dockIcon(screen),
            contentDescription = stringResource(dockLabel(screen)),
            tint = tint,
            size = if (compact) MODERN_DOCK_ICON_SIZE_COMPACT else MODERN_DOCK_ICON_SIZE,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = cliLabelText(stringResource(dockLabel(screen))),
            style = if (compact) {
                CliType.small.copy(fontSize = cliScaledSp(10f), lineHeight = cliScaledSp(12f))
            } else {
                CliType.small.copy(fontSize = cliScaledSp(11f), lineHeight = cliScaledSp(13f))
            },
            color = tint,
            maxLines = 1,
        )
    }
}

private val MODERN_DOCK_CORNER = 22.dp
private val MODERN_DOCK_DROP_WIDTH = 48.dp
private val MODERN_DOCK_DROP_WIDTH_COMPACT = 40.dp
private val MODERN_DOCK_DROP_HEIGHT = 32.dp
private val MODERN_DOCK_DROP_HEIGHT_COMPACT = 27.dp
private val MODERN_DOCK_ICON_SIZE = 24.dp
private val MODERN_DOCK_ICON_SIZE_COMPACT = 20.dp

private val MODERN_DOCK_COMPACT_ITEM_WIDTH = 52.dp

private val MODERN_DOCK_ITEM_PADDING = 11.dp
private val MODERN_DOCK_ITEM_PADDING_COMPACT = 8.dp
private val MODERN_DOCK_DROP_TOP =
    MODERN_DOCK_ITEM_PADDING - (MODERN_DOCK_DROP_HEIGHT - MODERN_DOCK_ICON_SIZE) / 2
private val MODERN_DOCK_DROP_TOP_COMPACT =
    MODERN_DOCK_ITEM_PADDING_COMPACT - (MODERN_DOCK_DROP_HEIGHT_COMPACT - MODERN_DOCK_ICON_SIZE_COMPACT) / 2
private val MODERN_DOCK_MIN_HEIGHT = 64.dp
private val MODERN_DOCK_MIN_HEIGHT_COMPACT = 54.dp
private val MODERN_DOCK_LIFT_TOP = 2.dp
private val MODERN_DOCK_LIFT_BOTTOM = 10.dp
private const val MODERN_DOCK_DROP_ALPHA = 0.16f
private const val MODERN_DOCK_PRESS_SCALE = 0.90f
private const val MODERN_DOCK_PRESS_GLOW_ALPHA = 0.10f

internal const val CLI_DOCK_TAG = "cli_dock"

internal fun cliDockItemTag(screen: CliScreen): String = "cli_dock_${screen.name.lowercase()}"

private fun dockIcon(screen: CliScreen): Int = when (screen) {
    CliScreen.HOME -> R.drawable.pix_home
    CliScreen.PROFILES -> R.drawable.pix_profiles
    CliScreen.APPS -> R.drawable.pix_apps
    CliScreen.MAP -> R.drawable.pix_map
    CliScreen.WEBAPPS -> R.drawable.pix_webapps
    CliScreen.STATS -> R.drawable.pix_stats
    CliScreen.SETTINGS -> R.drawable.pix_settings
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
    CliPixelStitch(alpha = 0.7f, color = color, modifier = modifier)
}

@Composable
internal fun CliRowDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    CliPixelStitch(
        alpha = 0.35f,
        color = color,
        modifier = modifier.padding(vertical = 3.dp),
    )
}

@Composable
internal fun CliSectionDivider(modifier: Modifier = Modifier) {
    CliPixelStitch(alpha = 0.5f, modifier = modifier.padding(vertical = CliSpacing.xs))
}

@Composable
internal fun CliHomeSectionGap(modifier: Modifier = Modifier) {
    if (LocalCliVisualStyle.current == VisualStyle.PIXEL) {
        CliSectionDivider(modifier)
    } else {
        Spacer(modifier = modifier.height(CliSpacing.sm))
    }
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
