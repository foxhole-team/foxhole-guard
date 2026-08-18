package com.foxhole.guard.ui.cli.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import kotlin.math.roundToInt

private const val FLAG_W = 32
private const val FLAG_H = 18

private const val PLAIN_FLAG_W = 4
private const val PLAIN_FLAG_H = 3

private const val CAP_HEIGHT_RATIO = 0.72f

internal fun cliFlagCode(countryCode: String?): String? =
    countryCode?.trim()?.lowercase()
        ?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

@Composable
internal fun cliFlagIconSize(style: TextStyle): DpSize {
    val height = with(LocalDensity.current) { (style.fontSize * CAP_HEIGHT_RATIO).toDp() }
    val plain = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val width = if (plain) height * PLAIN_FLAG_W / PLAIN_FLAG_H else height * FLAG_W / FLAG_H
    return DpSize(width = width, height = height)
}

@Composable
internal fun CliFlagIcon(
    countryCode: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = CliType.body,
) {
    val slot = cliFlagIconSize(style)
    val height = slot.height
    val code = cliFlagCode(countryCode)
    val context = LocalContext.current
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        val bitmap = remember(code, context) { plainFlagBitmap(context, code) } ?: return
        val label = countryCode!!.trim().uppercase()
        Canvas(
            modifier = modifier
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
        return
    }
    val resources = LocalResources.current
    val resId = remember(code, resources, context.packageName) {
        if (code == null) {
            0
        } else {
            @Suppress("DiscouragedApi")
            resources.getIdentifier("flag_$code", "drawable", context.packageName)
        }
    }
    if (resId == 0) return
    val bitmap: ImageBitmap = ImageBitmap.imageResource(resId)
    val label = countryCode!!.trim().uppercase()
    Canvas(
        modifier = modifier
            .size(slot)
            .semantics { contentDescription = label },
    ) {
        val scale = (size.height.roundToInt() / FLAG_H).coerceAtLeast(1)
        val w = (FLAG_W * scale).coerceAtMost(size.width.roundToInt())
        val h = (FLAG_H * scale).coerceAtMost(size.height.roundToInt())
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(
                ((size.width - w) / 2f).roundToInt(),
                ((size.height - h) / 2f).roundToInt(),
            ),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.None,
        )
    }
}

private val plainFlagCache = object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) =
        size > PLAIN_FLAG_CACHE_SIZE
}

private const val PLAIN_FLAG_CACHE_SIZE = 64

private fun plainFlagBitmap(context: Context, code: String?): ImageBitmap? {
    if (code == null) return null
    return plainFlagCache.getOrPut(code) {
        runCatching {
            context.assets.open("flags/$code.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }
}
