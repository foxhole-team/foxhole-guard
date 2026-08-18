package com.foxhole.guard.ui.cli.components

import android.graphics.drawable.BitmapDrawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliMetricScale
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import kotlin.math.roundToInt

/**
 * Every CLI glyph renders through here, which makes this the one place the style notch can be
 * applied to icons: callers keep passing the reference (retro) size and Modern draws it one notch
 * smaller, slot included. The dock re-provides [com.foxhole.guard.ui.cli.LocalCliMetricScale] as
 * 1f, so its glyphs are unaffected.
 */
@Composable
internal fun CliPixIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = CliIconSize.row,
    tint: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val drawnSize = size * LocalCliMetricScale.current
    val filter = ColorFilter.tint(if (tint == Color.Unspecified) colors.fg else tint)
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        val a11y = if (contentDescription != null) {
            Modifier.semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            }
        } else {
            Modifier
        }
        Image(
            painter = painterResource(cliLinIconRes(id)),
            contentDescription = null,
            modifier = modifier.size(drawnSize).then(a11y),
            colorFilter = filter,
        )
        return
    }
    val bitmap = cliPixIconBitmap(id)
    val a11y = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(drawnSize).then(a11y)) {
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

@Suppress("CyclomaticComplexMethod")
@DrawableRes
internal fun cliLinIconRes(@DrawableRes id: Int): Int = when (id) {
    R.drawable.pix_add -> R.drawable.lin_add
    R.drawable.pix_apps -> R.drawable.lin_apps
    R.drawable.pix_arrow_down -> R.drawable.lin_arrow_down
    R.drawable.pix_arrow_right -> R.drawable.lin_arrow_right
    R.drawable.pix_check -> R.drawable.lin_check
    R.drawable.pix_clock -> R.drawable.lin_clock
    R.drawable.pix_copy -> R.drawable.lin_copy
    R.drawable.pix_cross -> R.drawable.lin_cross
    R.drawable.pix_device -> R.drawable.lin_device
    R.drawable.pix_dns -> R.drawable.lin_dns
    R.drawable.pix_edit -> R.drawable.lin_edit
    R.drawable.pix_export -> R.drawable.lin_export
    R.drawable.pix_fire -> R.drawable.lin_fire
    R.drawable.pix_forbidden -> R.drawable.lin_forbidden
    R.drawable.pix_globe -> R.drawable.lin_globe
    R.drawable.pix_home -> R.drawable.lin_home
    R.drawable.pix_import -> R.drawable.lin_import
    R.drawable.pix_incognito -> R.drawable.lin_incognito
    R.drawable.pix_info -> R.drawable.lin_info
    R.drawable.pix_journal -> R.drawable.lin_journal
    R.drawable.pix_link -> R.drawable.lin_link
    R.drawable.pix_lock -> R.drawable.lin_lock
    R.drawable.pix_map -> R.drawable.lin_map
    R.drawable.pix_power -> R.drawable.lin_power
    R.drawable.pix_profiles -> R.drawable.lin_profiles
    R.drawable.pix_qr -> R.drawable.lin_qr
    R.drawable.pix_restart -> R.drawable.lin_restart
    R.drawable.pix_settings -> R.drawable.lin_settings
    R.drawable.pix_shield -> R.drawable.lin_shield
    R.drawable.pix_star -> R.drawable.lin_star
    R.drawable.pix_stats -> R.drawable.lin_stats
    R.drawable.pix_status -> R.drawable.lin_status
    R.drawable.pix_tor -> R.drawable.lin_tor
    R.drawable.pix_trash -> R.drawable.lin_trash
    R.drawable.pix_up -> R.drawable.lin_up
    R.drawable.pix_update -> R.drawable.lin_update
    R.drawable.pix_webapps -> R.drawable.lin_webapps
    R.drawable.widget_refresh_pixel -> R.drawable.lin_update
    else -> id
}

@Composable
private fun cliPixIconBitmap(@DrawableRes id: Int): ImageBitmap {
    val context = LocalContext.current
    return remember(id, context.theme) {
        val drawable = requireNotNull(AppCompatResources.getDrawable(context, id)) {
            "Drawable resource $id could not be loaded"
        }
        if (drawable is BitmapDrawable) {
            drawable.bitmap.asImageBitmap()
        } else {
            drawable.toBitmap().asImageBitmap()
        }
    }
}
