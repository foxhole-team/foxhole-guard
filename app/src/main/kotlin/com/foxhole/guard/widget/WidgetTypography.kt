package com.foxhole.guard.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.height
import androidx.glance.layout.width
import com.foxhole.guard.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal val LocalWidgetPixelArtEnabled = staticCompositionLocalOf { true }

@Composable
internal fun StyledWidgetText(
    context: Context,
    text: String,
    color: Color,
    fontSize: Int,
    modifier: GlanceModifier = GlanceModifier,
    uppercase: Boolean = false,
    maxWidth: Dp? = null,
) {
    val pixelArtEnabled = LocalWidgetPixelArtEnabled.current
    val displayText = if (uppercase) text.uppercase() else text
    val rendered = remember(context, displayText, color, fontSize, pixelArtEnabled, maxWidth) {
        renderStyledWidgetText(
            context = context,
            text = displayText,
            color = color,
            fontSizeSp = fontSize,
            pixelArtEnabled = pixelArtEnabled,
            maxWidth = maxWidth,
        )
    }
    Image(
        provider = ImageProvider(rendered.bitmap),
        contentDescription = text,
        modifier = modifier.width(rendered.width).height(rendered.height),
    )
}

private data class RenderedWidgetText(
    val bitmap: Bitmap,
    val width: Dp,
    val height: Dp,
)

private fun renderStyledWidgetText(
    context: Context,
    text: String,
    color: Color,
    fontSizeSp: Int,
    pixelArtEnabled: Boolean,
    maxWidth: Dp?,
): RenderedWidgetText {
    val metrics = context.resources.displayMetrics
    val pixelPaint = widgetTextPaint(context, R.font.tiny5_regular, color, fontSizeSp, pixelArt = true)
    val monoPaint = widgetTextPaint(context, R.font.jetbrains_mono_bold, color, fontSizeSp, pixelArt = false)
    val horizontalPadding = ceil(metrics.density).toInt().coerceAtLeast(1)
    val maximumTextWidthPx = maxWidth
        ?.let { width -> (width.value * metrics.density).toInt() - horizontalPadding * 2 }
        ?.coerceAtLeast(1)
    val fittedText = fitWidgetText(text, pixelPaint, monoPaint, maximumTextWidthPx)
    val pixelMetrics = pixelPaint.fontMetrics
    val monoMetrics = monoPaint.fontMetrics
    val ascent = min(pixelMetrics.ascent, monoMetrics.ascent)
    val descent = max(pixelMetrics.descent, monoMetrics.descent)
    val measuredWidth = max(pixelPaint.measureText(fittedText), monoPaint.measureText(fittedText))
    val widthPx = (ceil(measuredWidth).toInt() + horizontalPadding * 2)
        .let { width ->
            maxWidth
                ?.let { limit -> min(width, (limit.value * metrics.density).toInt()) }
                ?: width
        }
        .coerceAtLeast(1)
    val heightPx = ceil(descent - ascent).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
        density = metrics.densityDpi
    }
    val activePaint = if (pixelArtEnabled) pixelPaint else monoPaint
    Canvas(bitmap).drawText(fittedText, horizontalPadding.toFloat(), -ascent, activePaint)
    return RenderedWidgetText(
        bitmap = bitmap,
        width = (widthPx / metrics.density).dp,
        height = (heightPx / metrics.density).dp,
    )
}

private fun widgetTextPaint(
    context: Context,
    fontRes: Int,
    color: Color,
    fontSizeSp: Int,
    pixelArt: Boolean,
): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, fontRes) ?: Typeface.MONOSPACE
        textSize = fontSizeSp * context.resources.displayMetrics.density *
            context.resources.configuration.fontScale
        this.color = color.toArgb()
        isDither = true
        isSubpixelText = !pixelArt
    }

private fun fitWidgetText(
    text: String,
    firstPaint: Paint,
    secondPaint: Paint,
    maximumWidthPx: Int?,
): String {
    if (maximumWidthPx == null || widgetTextWidth(text, firstPaint, secondPaint) <= maximumWidthPx) {
        return text
    }
    val ellipsis = "…"
    if (widgetTextWidth(ellipsis, firstPaint, secondPaint) > maximumWidthPx) return ""
    var low = 0
    var high = text.length
    while (low < high) {
        val candidateLength = (low + high + 1) / 2
        val candidate = text.take(candidateLength).trimEnd() + ellipsis
        if (widgetTextWidth(candidate, firstPaint, secondPaint) <= maximumWidthPx) {
            low = candidateLength
        } else {
            high = candidateLength - 1
        }
    }
    return text.take(low).trimEnd() + ellipsis
}

private fun widgetTextWidth(text: String, firstPaint: Paint, secondPaint: Paint): Float =
    max(firstPaint.measureText(text), secondPaint.measureText(text))
