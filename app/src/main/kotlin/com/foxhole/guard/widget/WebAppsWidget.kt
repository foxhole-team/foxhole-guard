package com.foxhole.guard.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foxhole.core.model.WidgetDefaultsSettings
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.webapps.circularCrop
import com.foxhole.guard.core.webapps.decodeWebAppIcon
import com.foxhole.guard.core.webapps.webAppBadgeBitmap
import com.foxhole.guard.core.webapps.webAppLetterIconBitmap
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.withTimeoutOrNull

/** One launcher row shows four apps; the second row unlocks the next four. */
internal fun webAppSlots(size: DpSize): Int =
    if (size.width >= WIDE_WIDTH_THRESHOLD || size.height >= TALL_HEIGHT_THRESHOLD) 8 else 4

/**
 * The web apps widget: a logo row plus the first four or eight apps in sort order, with the same
 * badges as the screen. A tap opens the app's frame. Background comes from the per-widget config.
 */
class WebAppsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(180.dp, 80.dp),
                DpSize(400.dp, 80.dp),
                DpSize(180.dp, 160.dp),
                DpSize(400.dp, 160.dp),
            ),
        )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as FoxholeApplication).appGraph
        // Under app lock the database is closed by databaseReadyGate: show empty rather than hang.
        val apps =
            withTimeoutOrNull(WIDGET_DB_TIMEOUT_MS) {
                runCatching { graph.webAppsRepository.listWebApps() }.getOrDefault(emptyList())
            }.orEmpty()
        val icons: Map<Long, Bitmap> =
            apps.mapNotNull { app ->
                decodeWebAppIcon(context.filesDir, app.iconPath, WIDGET_ICON_PX)
                    ?.let { bitmap -> app.id to bitmap.circularCrop(WIDGET_ICON_PX) }
            }.toMap()

        val defaults = graph.settingsRepository.settings.value.widgets
        provideContent {
            val prefs = currentState<Preferences>()
            val background = widgetBackground(prefs, defaults)
            val outlined = widgetOutlineEnabled(prefs)
            val slots = webAppSlots(LocalSize.current)
            WebAppsWidgetContent(
                context = context,
                apps = apps.take(slots),
                icons = icons,
                background = background,
                outlined = outlined,
                compact = LocalSize.current.height < TALL_HEIGHT_THRESHOLD,
            )
        }
    }
}

class WebAppsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WebAppsWidget()
}

@androidx.compose.runtime.Composable
private fun WebAppsWidgetContent(
    context: Context,
    apps: List<WebAppEntity>,
    icons: Map<Long, Bitmap>,
    background: WidgetBackground,
    outlined: Boolean,
    compact: Boolean,
) {
    WidgetPixelFrame(background = background, outlined = outlined) {
        WebAppsWidgetBody(
            context = context,
            apps = apps,
            icons = icons,
            background = background,
            compact = compact,
        )
    }
}

@androidx.compose.runtime.Composable
private fun WebAppsWidgetBody(
    context: Context,
    apps: List<WebAppEntity>,
    icons: Map<Long, Bitmap>,
    background: WidgetBackground,
    compact: Boolean,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 10.dp),
    ) {
        WidgetBrandHeader(
            context = context,
            background = background,
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (apps.isEmpty()) {
            Text(
                text = context.getString(R.string.cli_webapps_empty),
                style = TextStyle(color = ColorProvider(background.text), fontSize = 11.sp),
            )
        } else {
            apps.chunked(WIDGET_APPS_PER_ROW).forEach { row ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    row.forEach { app ->
                        WebAppWidgetCell(
                            context = context,
                            app = app,
                            icon = icons[app.id],
                            textColor = background.text,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                    repeat(WIDGET_APPS_PER_ROW - row.size) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WebAppWidgetCell(
    context: Context,
    app: WebAppEntity,
    icon: Bitmap?,
    textColor: Color,
    modifier: GlanceModifier,
) {
    val openIntent =
        Intent(context, CliMainActivity::class.java)
            .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, app.id)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    Column(
        modifier = modifier.clickable(actionStartActivity(openIntent)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (icon != null) {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = app.name,
                    modifier = GlanceModifier.size(36.dp),
                )
            } else {
                Image(
                    provider = ImageProvider(
                        webAppLetterIconBitmap(
                            label = app.name,
                            sizePx = WIDGET_ICON_PX,
                            backgroundColor = WIDGET_FALLBACK_TILE.toArgb(),
                            textColor = WIDGET_ACCENT.toArgb(),
                        ),
                    ),
                    contentDescription = app.name,
                    modifier = GlanceModifier.size(36.dp),
                )
            }
            if (app.badgeCount > 0) {
                Box(modifier = GlanceModifier.size(36.dp), contentAlignment = Alignment.TopEnd) {
                    Image(
                        provider = ImageProvider(
                            webAppBadgeBitmap(
                                count = app.badgeCount,
                                sizePx = WIDGET_BADGE_PX,
                                backgroundColor = WIDGET_ACCENT.toArgb(),
                                textColor = Color.Black.toArgb(),
                            ),
                        ),
                        contentDescription = null,
                        modifier = GlanceModifier.size(WIDGET_BADGE_DP),
                    )
                }
            }
        }
        Text(
            text = app.name.lowercase(),
            style = TextStyle(color = ColorProvider(textColor), fontSize = 9.sp),
            maxLines = 1,
        )
    }
}

/** Per-widget background config, stored in the widget's Glance state. */
internal data class WidgetBackground(
    val fill: Color,
    val text: Color,
    val secondaryText: Color,
    val icon: Color,
)

internal val WIDGET_BG_BLACK_KEY: Preferences.Key<Boolean> = booleanPreferencesKey("bg_black")
internal val WIDGET_ALPHA_KEY: Preferences.Key<Int> = intPreferencesKey("alpha_percent")
internal val WIDGET_OUTLINE_KEY: Preferences.Key<Boolean> = booleanPreferencesKey("outline_enabled")

internal fun widgetOutlineEnabled(prefs: Preferences): Boolean = prefs[WIDGET_OUTLINE_KEY] ?: true

internal fun widgetBackground(prefs: Preferences, defaults: WidgetDefaultsSettings): WidgetBackground {
    val isBlack = prefs[WIDGET_BG_BLACK_KEY] ?: defaults.blackBackground
    val alpha = (prefs[WIDGET_ALPHA_KEY] ?: defaults.alphaPercent).coerceIn(0, 100) / 100f
    return WidgetBackground(
        fill = (if (isBlack) Color.Black else Color.White).copy(alpha = alpha),
        text = if (isBlack) Color.White else Color.Black,
        secondaryText = if (isBlack) CliColors().dim else Color(0xFF5B6472),
        icon = if (isBlack) Color.White else Color.Black,
    )
}

// The accent has one source, CliColors: widgets do not duplicate the hex, and lightening and
// transparency derive from that same token.
internal val WIDGET_ACCENT = CliColors().accent
internal val WIDGET_OK = CliColors().ok
internal val WIDGET_INFO = CliColors().info
internal val WIDGET_TOR = CliColors().tor
internal val WIDGET_ERROR = CliColors().err
private val WIDGET_FALLBACK_TILE = WIDGET_ACCENT.copy(alpha = WIDGET_TILE_ALPHA)
private val TALL_HEIGHT_THRESHOLD = 120.dp
private val WIDE_WIDTH_THRESHOLD = 240.dp
private const val WIDGET_APPS_PER_ROW = 4
private const val WIDGET_ICON_PX = 96
private const val WIDGET_BADGE_PX = 40
private val WIDGET_BADGE_DP = 16.dp
private const val WIDGET_DB_TIMEOUT_MS = 2_000L
private const val WIDGET_TILE_ALPHA = 0.2f
