package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import java.util.Locale

private val ProtocolBadgeMono = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

@Composable
internal fun CliProtocolBadge(
    protocol: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = colors.panel,
        contentColor = if (textColor == Color.Unspecified) colors.dim else textColor,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Text(
            text = cliProtocolBadgeLabel(protocol),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = CliType.small.copy(
                fontFamily = ProtocolBadgeMono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
            maxLines = 1,
        )
    }
}

internal fun cliProtocolBadgeLabel(protocol: String): String = when (protocol.uppercase(Locale.ROOT)) {
    "HYSTERIA2" -> "Hysteria2"
    "SHADOWSOCKS" -> "Shadowsocks"
    "WIREGUARD" -> "WireGuard"
    "TROJAN" -> "Trojan"
    "NAIVE" -> "Naive"
    "ANYTLS" -> "AnyTLS"
    "OUTLINE" -> "Outline"
    "TOR" -> "Tor"
    "LOCAL_GUARD" -> "Local Guard"
    "CUSTOM_CONFIG" -> "Custom config"
    "UNKNOWN" -> "Unknown"
    else -> protocol.uppercase(Locale.ROOT)
}
