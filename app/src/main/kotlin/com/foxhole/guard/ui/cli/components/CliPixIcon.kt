package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.LocalCliColors
import kotlin.math.roundToInt

/**
 * A 16x16 icon from the "1-bit Pixel Icons" pack (nikoichu, CC0; see third_party/icons). The
 * assets are pre-processed to pure line art, white on transparent, because the pack's two-colour
 * sprites would lose their interior under a tint.
 *
 * Blitted like the fox: nearest-neighbour at a whole pixel scale, centred in the [size] box, so
 * pixels stay square at any screen density. Tinted with the text or accent colour.
 */
@Composable
internal fun CliPixIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val bitmap = ImageBitmap.imageResource(id)
    val filter = ColorFilter.tint(if (tint == Color.Unspecified) colors.fg else tint)
    val a11y = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(size).then(a11y)) {
        val availablePx = this.size.minDimension.roundToInt()
        val scale = (availablePx / bitmap.width).coerceAtLeast(1)
        val side = (bitmap.width * scale).coerceAtMost(availablePx)
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(
                ((this.size.width - side) / 2f).roundToInt(),
                ((this.size.height - side) / 2f).roundToInt(),
            ),
            dstSize = IntSize(side, side),
            filterQuality = FilterQuality.None,
            colorFilter = filter,
        )
    }
}
