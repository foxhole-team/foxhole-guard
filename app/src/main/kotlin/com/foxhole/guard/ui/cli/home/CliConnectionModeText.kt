package com.foxhole.guard.ui.cli.home

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.R

internal fun cliHeaderModeIcon(settings: Settings): Int = when {
    settings.expert.localSurfaces.http.enabled -> R.drawable.lin_device
    settings.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL -> R.drawable.lin_shield
    else -> R.drawable.lin_apps
}

internal fun cliHeaderModeLabel(settings: Settings): Int = when {
    settings.expert.localSurfaces.http.enabled -> R.string.cli_route_vpn_proxy_server
    settings.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL -> R.string.cli_route_vpn_whole_device
    else -> R.string.cli_route_vpn_selected
}

@Composable
internal fun CliConnectionModeText(label: String, style: TextStyle, color: Color,
    icon: Int, modeDescription: String, maxLines: Int) {
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(style.fontFamily,
        style.fontWeight ?: FontWeight.Normal, style.fontStyle ?: FontStyle.Normal,
        style.fontSynthesis ?: FontSynthesis.All).value as? Typeface ?: Typeface.DEFAULT
    val cap = remember(typeface, style.fontSize, density.density, density.fontScale) {
        val bounds = Rect()
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = with(density) { style.fontSize.toPx() }
            getTextBounds("H", 0, 1, bounds)
        }
        with(density) { bounds.height().coerceAtLeast(1).toFloat().toSp() }
    }
    Text(buildAnnotatedString {
        appendInlineContent("connection-mode", modeDescription)
        append('\u00A0')
        append(label)
    }, style = style, color = color, maxLines = maxLines, softWrap = false,
        overflow = TextOverflow.Ellipsis,
        inlineContent = mapOf("connection-mode" to InlineTextContent(
            Placeholder(cap, cap, PlaceholderVerticalAlign.AboveBaseline)) {
            Icon(painterResource(icon), null, Modifier.fillMaxSize(), tint = color)
        }))
}
