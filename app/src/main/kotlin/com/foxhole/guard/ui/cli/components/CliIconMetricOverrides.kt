package com.foxhole.guard.ui.cli.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

@Immutable
internal data class CliIconMetricOverrides(
    val screenHeaderIconSize: Dp? = null,
    val panelHeaderIconSize: Dp? = null,
    val panelHeaderGlyphLift: Dp? = null,
    val panelHeaderLeadingIconLiftAdjustment: Dp? = null,
    val panelHeaderContentDrop: Dp? = null,
    val panelHeaderInfoAtEnd: Boolean = false,
    val panelHeaderFontSize: TextUnit = TextUnit.Unspecified,
    val panelHeaderLineHeight: TextUnit = TextUnit.Unspecified,
    val rowLeadingIconSize: Dp? = null,
)

internal val CLI_HEADER_ICON_LIFT = (-1).dp

internal val LocalCliIconMetricOverrides = staticCompositionLocalOf { CliIconMetricOverrides() }
