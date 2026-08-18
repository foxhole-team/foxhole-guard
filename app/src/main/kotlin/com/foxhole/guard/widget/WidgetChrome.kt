package com.foxhole.guard.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.cliLinIconRes

internal val WIDGET_FRAME_INSET = 4.dp

internal val LocalWidgetPlainIcons = staticCompositionLocalOf { false }

@Composable
internal fun widgetGlyph(id: Int): Int =
    if (LocalWidgetPlainIcons.current) {
        when (id) {
            R.drawable.widget_refresh_spinner_90,
            R.drawable.widget_refresh_spinner_180,
            R.drawable.widget_refresh_spinner_270,
            -> R.drawable.lin_update
            else -> cliLinIconRes(id)
        }
    } else {
        id
    }

@Composable
internal fun WidgetPixelFrame(
    background: WidgetBackground,
    outlined: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(background.fill))) {
        if (outlined) {
            Image(
                provider = ImageProvider(R.drawable.widget_frame),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize().padding(WIDGET_FRAME_INSET),
            )
        }
        content()
    }
}

@Composable
internal fun WidgetBrandHeader(
    context: Context,
    background: WidgetBackground,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_qs_tile),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(background.icon)),
            modifier = GlanceModifier.size(24.dp),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = context.getString(R.string.app_name),
            style =
            TextStyle(
                color = ColorProvider(WIDGET_ACCENT),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
        )
        trailing?.let { content ->
            Spacer(modifier = GlanceModifier.defaultWeight())
            content()
        }
    }
}
