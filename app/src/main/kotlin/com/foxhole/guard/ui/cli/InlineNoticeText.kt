package com.foxhole.guard.ui.cli

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

private const val NOTICE_ICON = "notice-icon"

internal fun inlineNoticeMessage(message: String): AnnotatedString = buildAnnotatedString {
    appendInlineContent(NOTICE_ICON)
    append("\u00a0")
    append(message)
}

@Composable
internal fun InlineNoticeText(message: String, @DrawableRes icon: Int, color: Color, style: TextStyle,
    modifier: Modifier = Modifier, iconColor: Color = color, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val resolved = LocalTextStyle.current.merge(style)
    val typeface = LocalFontFamilyResolver.current.resolve(resolved.fontFamily,
        resolved.fontWeight ?: FontWeight.Normal, resolved.fontStyle ?: FontStyle.Normal,
        resolved.fontSynthesis ?: FontSynthesis.All).value as Typeface
    val density = LocalDensity.current
    val fontPx = with(density) { resolved.fontSize.toPx() }
    val heightPx = remember(typeface, fontPx, resolved.fontFeatureSettings) {
        val bounds = Rect()
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface; textSize = fontPx; fontFeatureSettings = resolved.fontFeatureSettings
        }.getTextBounds("H", 0, 1, bounds)
        bounds.height().toFloat().takeIf { it > 0f } ?: fontPx * .7f
    }
    val height = with(density) { heightPx.toSp() }
    val annotated = remember(message) { inlineNoticeMessage(message) }
    Text(annotated, modifier.clearAndSetSemantics { text = AnnotatedString(message) }, color = color,
        style = resolved, softWrap = true, textAlign = TextAlign.Start, onTextLayout = onTextLayout,
        inlineContent = mapOf(NOTICE_ICON to InlineTextContent(Placeholder(height, height, PlaceholderVerticalAlign.AboveBaseline)) {
            Icon(painterResource(icon), contentDescription = null, tint = iconColor, modifier = Modifier.fillMaxSize())
        }))
}
