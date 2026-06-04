package com.foxhole.beta.ui.statistics

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.beta.ui.theme.LocalFoxholeDarkTheme
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette

@Immutable
data class StatisticsColorTokens(
    val screenGlowTop: Color,
    val cardContainer: Color,
    val cardContainerElevated: Color,
    val cardBorder: Color,
    val cardBorderStrong: Color,
    val headerIconContainer: Color,
    val headerIconTint: Color,
    val metricTileContainer: Color,
    val metricTileBorder: Color,
    val rowContainer: Color,
    val rowContainerSelected: Color,
    val divider: Color,
    val mutedText: Color,
    val positive: Color,
    val warning: Color,
    val danger: Color,
    val tx: Color,
    val rx: Color,
    val dnsAds: Color,
    val dnsTrackers: Color,
    val dnsTelemetry: Color,
    val dnsMalicious: Color,
    val tor: Color,
    val direct: Color,
    val unknown: Color,
    val countryPalette: List<Color>,
)

@Immutable
data class StatisticsDimens(
    val cardRadius: Dp,
    val innerRadius: Dp,
    val tileRadius: Dp,
    val cardBorderWidth: Dp,
    val hairlineWidth: Dp,
    val cardPadding: Dp,
    val cardVerticalGap: Dp,
    val sectionGap: Dp,
    val rowVerticalPadding: Dp,
    val rowHorizontalPadding: Dp,
    val iconBoxSize: Dp,
    val iconSize: Dp,
    val smallBarHeight: Dp,
    val mediumBarHeight: Dp,
    val timelineHeight: Dp,
    val donutSmallSize: Dp,
    val donutLargeSize: Dp,
)

@Immutable
data class StatisticsVisualTokens(
    val colors: StatisticsColorTokens,
    val dimens: StatisticsDimens,
)

@Composable
fun statisticsVisualTokens(): StatisticsVisualTokens {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalFoxholeDarkTheme.current
    val semantic = LocalFoxholeSemanticColors.current
    val palette = LocalFoxholeUiPalette.current

    val colors =
        StatisticsColorTokens(
            screenGlowTop = scheme.primary.copy(alpha = if (dark) 0.035f else 0.055f),
            cardContainer =
            if (dark) {
                scheme.surfaceContainerHigh.copy(alpha = 0.92f)
            } else {
                scheme.surfaceContainerLowest
            },
            cardContainerElevated = if (dark) scheme.surfaceContainerHighest.copy(alpha = 0.96f) else scheme.surface,
            cardBorder = palette.cardBorderColor.copy(alpha = if (dark) 0.52f else 0.72f),
            cardBorderStrong = scheme.outline.copy(alpha = if (dark) 0.50f else 0.62f),
            headerIconContainer = palette.leadingIconContainerColor.copy(alpha = if (dark) 0.92f else 0.72f),
            headerIconTint = scheme.primary,
            metricTileContainer = scheme.surfaceVariant.copy(alpha = if (dark) 0.30f else 0.48f),
            metricTileBorder = scheme.outlineVariant.copy(alpha = if (dark) 0.36f else 0.54f),
            rowContainer = scheme.surfaceVariant.copy(alpha = if (dark) 0.22f else 0.36f),
            rowContainerSelected = scheme.primaryContainer.copy(alpha = if (dark) 0.72f else 0.78f),
            divider = scheme.outlineVariant.copy(alpha = if (dark) 0.34f else 0.48f),
            mutedText = scheme.onSurfaceVariant.copy(alpha = if (dark) 0.82f else 0.88f),
            positive = semantic.success,
            warning = semantic.warning,
            danger = scheme.error,
            tx = Color(0xFF58A6FF),
            rx = Color(0xFF5EE4A1),
            dnsAds = Color(0xFF60A5FA),
            dnsTrackers = Color(0xFFF7C948),
            dnsTelemetry = Color(0xFFFF9F43),
            dnsMalicious = scheme.error,
            tor = Color(0xFFA78BFA),
            direct = Color(0xFF94A3B8),
            unknown = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            countryPalette =
            listOf(
                Color(0xFF5EEAD4),
                Color(0xFFFBBF24),
                Color(0xFF93C5FD),
                Color(0xFFC084FC),
                Color(0xFFA3E635),
                scheme.outline,
            ),
        )

    val dimens =
        StatisticsDimens(
            cardRadius = 26.dp,
            innerRadius = 18.dp,
            tileRadius = 16.dp,
            cardBorderWidth = 1.dp,
            hairlineWidth = 0.75.dp,
            cardPadding = 16.dp,
            cardVerticalGap = 14.dp,
            sectionGap = 12.dp,
            rowVerticalPadding = 9.dp,
            rowHorizontalPadding = 10.dp,
            iconBoxSize = 34.dp,
            iconSize = 18.dp,
            smallBarHeight = 8.dp,
            mediumBarHeight = 12.dp,
            timelineHeight = 184.dp,
            donutSmallSize = 84.dp,
            donutLargeSize = 124.dp,
        )

    return StatisticsVisualTokens(colors = colors, dimens = dimens)
}
