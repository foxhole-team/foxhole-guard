package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.foxhole.guard.ui.cli.CliType
import kotlin.math.roundToInt

// Base grid of the project's own flag renders (see third_party/flags).
private const val FLAG_W = 32
private const val FLAG_H = 18

// Cap height of the pixel faces as a fraction of the em box: their capitals occupy roughly this
// much of the font size, so a flag scaled by it sits level with the letters beside it.
private const val CAP_HEIGHT_RATIO = 0.72f

/**
 * Pixel country flag by ISO2 code. The only untinted raster in the theme — a flag keeps its native
 * colours and so reads on any palette. Blitted by the same law as the icons: whole-pixel scale,
 * centred, unfiltered.
 *
 * Flag height tracks the neighbouring text's *cap height* — the height of a capital letter — not
 * its em size, so the flag reads as one glyph in the line rather than overpowering it. Derived from
 * the style's font size so it still scales with the system font setting; no ad-hoc Dp height.
 *
 * An unknown or empty code draws nothing at all; the caller decides what to put there, and
 * usually the textual country code already stands beside it.
 */
@Composable
internal fun CliFlagIcon(
    countryCode: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = CliType.body,
) {
    val height = with(LocalDensity.current) { (style.fontSize * CAP_HEIGHT_RATIO).toDp() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val resId = remember(countryCode, resources, context.packageName) {
        val code = countryCode?.trim()?.lowercase()
            ?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
        if (code == null) {
            0
        } else {
            // 212 flags: a hand-written table is worse than a lookup by name.
            @Suppress("DiscouragedApi")
            resources.getIdentifier("flag_$code", "drawable", context.packageName)
        }
    }
    if (resId == 0) return
    val bitmap: ImageBitmap = ImageBitmap.imageResource(resId)
    val label = countryCode!!.trim().uppercase()
    Canvas(
        modifier = modifier
            .size(width = height * FLAG_W / FLAG_H, height = height)
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
