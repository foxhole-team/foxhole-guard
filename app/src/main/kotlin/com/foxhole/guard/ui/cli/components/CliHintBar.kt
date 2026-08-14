package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliScreen
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import kotlinx.coroutines.delay

/**
 * Bottom navigation styled as an icon dock: one glyph from the 16x16 1-bit pixel pack per
 * screen. Each cell is a >=48dp tap target; the active tab is accent-tinted, the rest dim.
 * The screen name lives in the contentDescription only (cli_dock_*).
 */
@Composable
internal fun CliHintBar(
    current: CliScreen,
    onSelect: (CliScreen) -> Unit,
    modifier: Modifier = Modifier,
    screens: List<CliScreen> = CliScreen.entries,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .testTag(CLI_DOCK_TAG)
            .padding(horizontal = CliSpacing.xs),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        screens.forEach { screen ->
            val active = screen == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag(cliDockItemTag(screen))
                    .cliPressable { onSelect(screen) },
                contentAlignment = Alignment.Center,
            ) {
                CliPixIcon(
                    id = dockIcon(screen),
                    contentDescription = stringResource(dockLabel(screen)),
                    tint = if (active) colors.accent else colors.dim,
                    size = 24.dp,
                )
                // The pixel underline marks the active tab; a two-step flash on arrival is the
                // press feedback — hard steps, no slide (see CliDockMarker).
                if (active) {
                    CliDockMarker(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

/**
 * The active tab's pixel underline. On appearing it flashes bright → accent in two hard steps —
 * the arrival feedback of a cursor, not a sliding indicator; there is nothing to animate between
 * tabs because each tab draws its own marker.
 */
@Composable
private fun CliDockMarker(modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DOCK_MARKER_FLASH_MS)
        arrived = true
    }
    Box(
        modifier = modifier
            .padding(bottom = 4.dp)
            .width(16.dp)
            .height(2.dp)
            .background(if (arrived) colors.accent else colors.accentBright),
    )
}

private const val DOCK_MARKER_FLASH_MS = 150L

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

/**
 * Thin `╌╌╌╌` divider used between the content and the hint bar: a pixel-dash of sharp
 * 2x1dp squares with 2dp gaps instead of a solid hairline - the same border color as
 * before, but with the 16-bit stitch texture.
 */
@Composable
internal fun CliDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    CliPixelStitch(alpha = 0.7f, color = color, modifier = modifier)
}

/**
 * Row divider for list panels: the same pixel hatch, quieter — it separates rows without
 * shouting over the text.
 */
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

/**
 * Divider *between* sections of a multi-section screen: the same stitch at section rhythm, its
 * padding preserving the previous total Spacer(sm) gap with the line laid in the middle.
 */
@Composable
internal fun CliSectionDivider(modifier: Modifier = Modifier) {
    CliPixelStitch(alpha = 0.5f, modifier = modifier.padding(vertical = CliSpacing.xs))
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
