package com.foxhole.guard.ui.cli.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.foxhole.guard.ui.cli.CLI_ICON_OPTICAL_OFFSET
import com.foxhole.guard.ui.cli.CliType
import kotlin.math.roundToInt

private const val FLAG_W = 4
private const val FLAG_H = 3

private const val CAP_HEIGHT_RATIO = 0.72f

internal fun cliFlagCode(countryCode: String?): String? =
    countryCode?.trim()?.lowercase()
        ?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

@Composable
internal fun cliFlagIconSize(style: TextStyle): DpSize {
    val height = with(LocalDensity.current) { (style.fontSize * CAP_HEIGHT_RATIO).toDp() }
    val width = height * FLAG_W / FLAG_H
    return DpSize(width = width, height = height)
}

@Composable
internal fun CliFlagIcon(
    countryCode: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = CliType.body,
    visualOffsetY: Dp = CLI_ICON_OPTICAL_OFFSET,
) {
    val slot = cliFlagIconSize(style)
    val height = slot.height
    val alignedModifier = modifier.offset(
        y = visualOffsetY,
    )
    val code = cliFlagCode(countryCode)
    val context = LocalContext.current
    val bitmap = remember(code, context) { flagBitmap(context, code) } ?: return
    val label = countryCode!!.trim().uppercase()
    Canvas(
        modifier = alignedModifier
            .size(slot)
            .semantics { contentDescription = label },
    ) {
        drawImage(
            image = bitmap,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

private val flagCache = object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) =
        size > FLAG_CACHE_SIZE
}

private const val FLAG_CACHE_SIZE = 64

private fun flagBitmap(context: Context, code: String?): ImageBitmap? {
    if (code == null) return null
    return flagCache.getOrPut(code) {
        runCatching {
            context.assets.open("flags/$code.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }
}
