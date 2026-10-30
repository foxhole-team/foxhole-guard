package com.foxhole.guard.ui.cli.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.foxhole.guard.flags.capitalLetterHeightPx
import com.foxhole.guard.flags.countryFlagBitmap
import com.foxhole.guard.flags.countryFlagCode
import com.foxhole.guard.flags.countryFlagSizePx
import com.foxhole.guard.flags.countryFlagVectorResource
import com.foxhole.guard.ui.cli.CLI_ICON_OPTICAL_OFFSET
import com.foxhole.guard.ui.cli.CliType
import java.util.Locale
import kotlin.math.roundToInt

internal fun cliFlagCode(countryCode: String?): String? = countryFlagCode(countryCode)

@Composable
internal fun cliFlagIconSize(style: TextStyle, imageWidth: Int = 4, imageHeight: Int = 3): DpSize {
    val resolvedStyle = LocalTextStyle.current.merge(style)
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(
        fontFamily = resolvedStyle.fontFamily,
        fontWeight = resolvedStyle.fontWeight ?: FontWeight.Normal,
        fontStyle = resolvedStyle.fontStyle ?: FontStyle.Normal,
        fontSynthesis = resolvedStyle.fontSynthesis ?: FontSynthesis.All,
    ).value as Typeface
    val sizePx = with(density) { resolvedStyle.fontSize.toPx() }
    val capitalHeight = remember(typeface, sizePx, resolvedStyle.fontFeatureSettings) {
        capitalLetterHeightPx(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = sizePx
            fontFeatureSettings = resolvedStyle.fontFeatureSettings
        })
    }
    val size = countryFlagSizePx(capitalHeight, imageWidth, imageHeight) ?: return DpSize.Zero
    return with(density) { DpSize(size.width.toDp(), size.height.toDp()) }
}

@Composable
internal fun CliFlagIcon(
    countryCode: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = CliType.body,
    visualOffsetY: Dp = CLI_ICON_OPTICAL_OFFSET,
) {
    val code = cliFlagCode(countryCode) ?: return
    val context = LocalContext.current
    val vector = countryFlagVectorResource(code)
    val bitmap = if (vector == null) remember(code, context) { countryFlagBitmap(context, code)?.asImageBitmap() } else null
    if (vector == null && bitmap == null) return
    val slot = cliFlagIconSize(style, bitmap?.width ?: 4, bitmap?.height ?: 3)
    if (slot == DpSize.Zero) return
    val imageModifier = modifier.offset(y = visualOffsetY).size(slot)
    val label = code.uppercase(Locale.ROOT)
    if (vector != null) {
        Image(painterResource(vector), contentDescription = label, modifier = imageModifier, contentScale = ContentScale.Fit)
        return
    }
    Canvas(
        modifier = imageModifier.semantics { contentDescription = label },
    ) {
        drawImage(
            image = requireNotNull(bitmap),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}
