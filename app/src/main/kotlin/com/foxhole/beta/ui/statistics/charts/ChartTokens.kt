package com.foxhole.beta.ui.statistics.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors

@Immutable
data class ChartTokens(
    val gridColor: Color,
    val axisColor: Color,
    val trackColor: Color,
    val ringTrackColor: Color,
    val lineStrokeWidth: Dp,
    val ringStrokeWidth: Dp,
    val barCornerRadius: Dp,
    val donutGapDegrees: Float,
)

@Composable
fun chartVisualTokens(): ChartTokens {
    val colorScheme = MaterialTheme.colorScheme
    return ChartTokens(
        gridColor = colorScheme.outlineVariant.copy(alpha = 0.32f),
        axisColor = colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        trackColor = colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ringTrackColor = colorScheme.onSurface.copy(alpha = 0.16f),
        lineStrokeWidth = 2.dp,
        ringStrokeWidth = 14.dp,
        barCornerRadius = 5.dp,
        donutGapDegrees = 3f,
    )
}

@Composable
fun chartColor(token: ChartColorToken): Color {
    val semantic = LocalFoxholeSemanticColors.current
    return when (token) {
        ChartColorToken.TX -> Color(0xFF2F80ED)
        ChartColorToken.RX -> Color(0xFF22C55E)
        ChartColorToken.TOTAL -> MaterialTheme.colorScheme.tertiary
        ChartColorToken.SUCCESS -> semantic.success
        ChartColorToken.ERROR -> MaterialTheme.colorScheme.error
        ChartColorToken.WARNING -> semantic.warning
        ChartColorToken.TOR -> Color(0xFF7E57C2)
        ChartColorToken.VPN -> MaterialTheme.colorScheme.primary
        ChartColorToken.DIRECT -> MaterialTheme.colorScheme.secondary
        ChartColorToken.DNS_ALLOWED -> semantic.success
        ChartColorToken.DNS_BLOCKED -> MaterialTheme.colorScheme.error
        ChartColorToken.ADS -> Color(0xFF42A5F5)
        ChartColorToken.TRACKERS -> Color(0xFFFFD54F)
        ChartColorToken.TELEMETRY -> Color(0xFFFF9800)
        ChartColorToken.MALICIOUS -> MaterialTheme.colorScheme.error
        ChartColorToken.WIFI -> Color(0xFF26A69A)
        ChartColorToken.MOBILE -> Color(0xFF5C6BC0)
        ChartColorToken.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        ChartColorToken.COUNTRY_1 -> Color(0xFF4DB6AC)
        ChartColorToken.COUNTRY_2 -> Color(0xFFFFB74D)
        ChartColorToken.COUNTRY_3 -> Color(0xFF64B5F6)
        ChartColorToken.COUNTRY_4 -> Color(0xFFBA68C8)
        ChartColorToken.COUNTRY_5 -> Color(0xFFAED581)
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
