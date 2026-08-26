package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.ui.cli.CliTypography
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.LocalCliType
import com.foxhole.guard.ui.cli.cliPixelFontSizeForMonoSp
import com.foxhole.guard.ui.cli.components.CliIconMetricOverrides
import com.foxhole.guard.ui.cli.components.LocalCliIconMetricOverrides

@Composable
internal fun Modifier.cliHomeSectionHeaderPlacement(): Modifier =
    offset(
        x = (-4).dp,
        y = CLI_HOME_SECTION_HEADING_OFFSET,
    )

@Composable
internal fun Modifier.cliHomeStatusHeaderPlacement(): Modifier =
    cliHomeSectionHeaderPlacement().offset(y = CLI_HOME_STATUS_HEADER_DROP)

internal val CLI_HOME_SECTION_HEADING_OFFSET = (-1).dp

internal val CLI_HOME_STATUS_HEADER_DROP = 1.dp

@Composable
internal fun CliHomeSectionTypography(content: @Composable () -> Unit) {
    val typography = LocalCliType.current
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val homeTypography = remember(typography) {
        cliHomeSectionTypographyFor(typography)
    }
    val homeHeaderMetrics = remember(pixelArtEnabled) {
        cliHomeHeaderMetrics(pixelArtEnabled)
    }
    CompositionLocalProvider(
        LocalCliType provides homeTypography,
        LocalCliIconMetricOverrides provides homeHeaderMetrics,
        content = content,
    )
}

internal fun cliHomeHeaderMetrics(pixelArtEnabled: Boolean): CliIconMetricOverrides =
    CliIconMetricOverrides(
        panelHeaderIconSize = 16.dp,
        panelHeaderLeadingIconLiftAdjustment = cliHomeHeaderIconLiftFor(pixelArtEnabled),
        panelHeaderFontSize = if (pixelArtEnabled) cliPixelFontSizeForMonoSp(14f) else 14.sp,
        panelHeaderLineHeight = 19.sp,
    )

@Suppress("UNUSED_PARAMETER")
internal fun cliHomeHeaderIconLiftFor(pixelArtEnabled: Boolean): Dp = 0.dp

internal fun cliHomeSectionTypographyFor(
    typography: CliTypography,
): CliTypography = typography.copy(
    body = typography.body.copy(
        fontSize = CLI_HOME_BODY_FONT_SIZE,
        platformStyle = CLI_HOME_PLATFORM_STYLE,
    ),
    small = cliHomeConsoleTextStyleFor(typography),
    title = typography.title.copy(
        platformStyle = CLI_HOME_PLATFORM_STYLE,
    ),
)

internal fun cliHomeConsoleTextStyleFor(typography: CliTypography): TextStyle =
    typography.small.copy(
        fontSize = CLI_HOME_SMALL_FONT_SIZE,
        platformStyle = CLI_HOME_PLATFORM_STYLE,
    )

internal val CLI_HOME_BODY_FONT_SIZE = 14.sp
internal val CLI_HOME_SMALL_FONT_SIZE = 12.sp
internal val CLI_HOME_PLATFORM_STYLE = PlatformTextStyle(includeFontPadding = false)
