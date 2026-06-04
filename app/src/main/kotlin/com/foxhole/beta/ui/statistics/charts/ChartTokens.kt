package com.foxhole.beta.ui.statistics.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.ui.statistics.statisticsVisualTokens
import com.foxhole.beta.ui.theme.LocalFoxholeDarkTheme
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors

@Immutable
data class ChartTokens(
    val gridColor: Color,
    val axisColor: Color,
    val trackColor: Color,
    val ringTrackColor: Color,
    val lineStrokeWidth: Dp,
    val lineStrokeWidthCompact: Dp,
    val gridStrokeWidth: Dp,
    val axisStrokeWidth: Dp,
    val ringStrokeWidth: Dp,
    val ringStrokeWidthCompact: Dp,
    val barCornerRadius: Dp,
    val barMinWidth: Dp,
    val donutGapDegrees: Float,
)

@Composable
fun chartVisualTokens(): ChartTokens {
    val colorScheme = MaterialTheme.colorScheme
    val dark = LocalFoxholeDarkTheme.current
    return ChartTokens(
        gridColor = colorScheme.onSurfaceVariant.copy(alpha = if (dark) 0.22f else 0.30f),
        axisColor = colorScheme.onSurfaceVariant.copy(alpha = if (dark) 0.46f else 0.58f),
        trackColor = colorScheme.surfaceVariant.copy(alpha = if (dark) 0.34f else 0.50f),
        ringTrackColor = colorScheme.onSurface.copy(alpha = if (dark) 0.12f else 0.10f),
        lineStrokeWidth = 2.dp,
        lineStrokeWidthCompact = 1.4.dp,
        gridStrokeWidth = 0.75.dp,
        axisStrokeWidth = 1.dp,
        ringStrokeWidth = 12.dp,
        ringStrokeWidthCompact = 8.dp,
        barCornerRadius = 6.dp,
        barMinWidth = 2.dp,
        donutGapDegrees = 2.5f,
    )
}

@Composable
fun chartColor(token: ChartColorToken): Color {
    val semantic = LocalFoxholeSemanticColors.current
    val statistics = statisticsVisualTokens().colors
    return when (token) {
        ChartColorToken.TX -> statistics.tx
        ChartColorToken.RX -> statistics.rx
        ChartColorToken.TOTAL -> MaterialTheme.colorScheme.tertiary
        ChartColorToken.SUCCESS -> semantic.success
        ChartColorToken.ERROR -> MaterialTheme.colorScheme.error
        ChartColorToken.WARNING -> semantic.warning
        ChartColorToken.TOR -> statistics.tor
        ChartColorToken.VPN -> MaterialTheme.colorScheme.primary
        ChartColorToken.DIRECT -> statistics.direct
        ChartColorToken.DNS_ALLOWED -> semantic.success
        ChartColorToken.DNS_BLOCKED -> MaterialTheme.colorScheme.error
        ChartColorToken.ADS -> statistics.dnsAds
        ChartColorToken.TRACKERS -> statistics.dnsTrackers
        ChartColorToken.TELEMETRY -> statistics.dnsTelemetry
        ChartColorToken.MALICIOUS -> MaterialTheme.colorScheme.error
        ChartColorToken.WIFI -> statistics.rx
        ChartColorToken.MOBILE -> MaterialTheme.colorScheme.tertiary
        ChartColorToken.UNKNOWN -> statistics.unknown
        ChartColorToken.COUNTRY_1 -> statistics.countryPalette[0]
        ChartColorToken.COUNTRY_2 -> statistics.countryPalette[1]
        ChartColorToken.COUNTRY_3 -> statistics.countryPalette[2]
        ChartColorToken.COUNTRY_4 -> statistics.countryPalette[3]
        ChartColorToken.COUNTRY_5 -> statistics.countryPalette[4]
        ChartColorToken.OTHER -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun chartCountryColors(): List<Color> =
    listOf(
        chartColor(ChartColorToken.COUNTRY_1),
        chartColor(ChartColorToken.COUNTRY_2),
        chartColor(ChartColorToken.COUNTRY_3),
        chartColor(ChartColorToken.COUNTRY_4),
        chartColor(ChartColorToken.COUNTRY_5),
        chartColor(ChartColorToken.OTHER),
    )
