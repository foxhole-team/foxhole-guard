package com.foxhole.guard.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.unit.ColorProvider
import com.foxhole.guard.R

internal val WIDGET_FRAME_INSET = 4.dp
internal val WIDGET_SURFACE_CORNER_RADIUS = 8.dp

@Composable
internal fun WidgetFrame(
    background: WidgetBackground,
    outlined: Boolean,
    modifier: GlanceModifier = GlanceModifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)
            .background(ColorProvider(background.fill)),
    ) {
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
        StyledWidgetText(
            context = context,
            text = context.getString(R.string.app_name),
            color = WIDGET_ACCENT,
            fontSize = 12,
            maxWidth = WIDGET_BRAND_TEXT_MAX_WIDTH,
        )
        trailing?.let { content ->
            Spacer(modifier = GlanceModifier.defaultWeight())
            content()
        }
    }
}

private val WIDGET_BRAND_TEXT_MAX_WIDTH: Dp = 150.dp
