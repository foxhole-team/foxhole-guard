package com.foxhole.beta.ui.statistics.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors

@Composable
fun chartColor(token: ChartColorToken): Color {
    val semantic = LocalFoxholeSemanticColors.current
    return when (token) {
        ChartColorToken.TX -> MaterialTheme.colorScheme.primary
        ChartColorToken.RX -> semantic.success
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
