package com.foxhole.guard.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.networkUp
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R

/** Connection control and live facts. One launcher row stays useful; two rows expose the table. */
class StatusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as FoxholeApplication).appGraph
        val connection = graph.connectionController
        val defaults = graph.settingsRepository.settings.value.widgets
        provideContent {
            val prefs = currentState<Preferences>()
            val background = widgetBackground(prefs, defaults)
            val outlined = widgetOutlineEnabled(prefs)
            val snapshot by connection.snapshot.collectAsState()
            val settings by graph.settingsRepository.settings.collectAsState()
            val vpnIpInfo by connection.ipInfo.collectAsState()
            val torIpInfo by connection.torRouteIpInfo.collectAsState()
            val deviceIpInfo by connection.deviceIpInfo.collectAsState()
            val i2pPhase by connection.i2pPhase.collectAsState()
            val refreshPhase by StatusWidgetRefreshState.phase.collectAsState()
            val refreshFrame by StatusWidgetRefreshState.frame.collectAsState()
            StatusWidgetContent(
                context = context,
                presentation =
                statusWidgetPresentation(
                    snapshot = snapshot,
                    settings = settings,
                    vpnIpInfo = vpnIpInfo,
                    torIpInfo = torIpInfo,
                    i2pConnected = i2pPhase.phase.networkUp,
                    deviceIpInfo = deviceIpInfo,
                ),
                background = background,
                outlined = outlined,
                expanded = LocalSize.current.height >= STATUS_WIDGET_EXPANDED_HEIGHT,
                refreshPhase = refreshPhase,
                refreshFrame = refreshFrame,
            )
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

@Composable
private fun StatusWidgetContent(
    context: Context,
    presentation: StatusWidgetPresentation,
    background: WidgetBackground,
    outlined: Boolean,
    expanded: Boolean,
    refreshPhase: StatusWidgetRefreshPhase,
    refreshFrame: Int,
) {
    WidgetPixelFrame(background = background, outlined = outlined) {
        Column(
            modifier =
            GlanceModifier
                .fillMaxSize()
                // The 4dp frame inset belongs to the stroke. Content starts farther in so the
                // bottom controls never touch the contour, even after a launcher resize/crop.
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            StatusWidgetHeader(context, background, refreshPhase, refreshFrame)
            Spacer(modifier = GlanceModifier.height(2.dp))
            // Glance RemoteViews containers accept at most ten direct children. Keep the body in
            // one weighted slot so its fact rows cannot push the always-present controls past that
            // limit or below the launcher's crop during a resize.
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.TopStart,
            ) {
                if (expanded) {
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        StatusWidgetFacts(context, presentation, background, refreshPhase)
                    }
                } else {
                    StatusWidgetSummary(context, presentation, background, refreshPhase)
                }
            }
            StatusWidgetControls(context, presentation)
        }
    }
}

@Composable
private fun StatusWidgetHeader(
    context: Context,
    background: WidgetBackground,
    refreshPhase: StatusWidgetRefreshPhase,
    refreshFrame: Int,
) {
    WidgetBrandHeader(
        context = context,
        background = background,
        trailing = {
            Box(
                modifier =
                GlanceModifier
                    .size(22.dp)
                    .clickable(
                        actionSendBroadcast(
                            Intent(context, StatusWidgetCommandReceiver::class.java)
                                .setAction(StatusWidgetCommandReceiver.ACTION_REFRESH),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider =
                    ImageProvider(
                        if (refreshPhase == StatusWidgetRefreshPhase.LOADING) {
                            statusWidgetRefreshSpinnerFrame(refreshFrame)
                        } else {
                            R.drawable.widget_refresh_pixel
                        },
                    ),
                    contentDescription = context.getString(R.string.widget_status_refresh),
                    colorFilter = ColorFilter.tint(ColorProvider(background.icon)),
                    modifier = GlanceModifier.size(16.dp),
                )
            }
        },
    )
}

@Composable
private fun StatusWidgetSummary(
    context: Context,
    presentation: StatusWidgetPresentation,
    background: WidgetBackground,
    refreshPhase: StatusWidgetRefreshPhase,
) {
    val summary =
        if (refreshPhase == StatusWidgetRefreshPhase.LOADING) {
            context.getString(R.string.widget_status_refreshing)
        } else {
            presentation.summaryText(context)
        }
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = presentation.connection.text(context),
            style = widgetTextStyle(presentation.connection.color, 9),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = summary,
            style = widgetTextStyle(background.text, 9),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

@Composable
private fun StatusWidgetFacts(
    context: Context,
    presentation: StatusWidgetPresentation,
    background: WidgetBackground,
    refreshPhase: StatusWidgetRefreshPhase,
) {
    StatusFactRow(
        icon = R.drawable.pix_status,
        label = context.getString(R.string.widget_status_state),
        value = presentation.connection.text(context),
        valueColor = presentation.connection.color,
        background = background,
    )
    StatusFactRow(
        icon = R.drawable.pix_profiles,
        label = context.getString(R.string.widget_status_vpn_profile),
        value = presentation.vpnProfile.orDash(),
        background = background,
    )
    StatusFactRow(
        icon = R.drawable.pix_shield,
        label = context.getString(R.string.widget_status_vpn_protocol),
        value = presentation.vpnProtocol.orDash(),
        background = background,
    )
    StatusFactRow(
        icon = R.drawable.pix_link,
        label = context.getString(R.string.widget_status_scenario),
        value = presentation.routeText(context),
        background = background,
    )
    StatusComponentsRow(context, presentation.components, background)
    val identity =
        when (refreshPhase) {
            StatusWidgetRefreshPhase.LOADING -> context.getString(R.string.widget_status_refreshing)
            StatusWidgetRefreshPhase.FAILED -> context.getString(R.string.widget_status_refresh_failed)
            StatusWidgetRefreshPhase.IDLE -> presentation.vpnIdentity.widgetIdentityText()
        }
    StatusFactRow(
        icon = R.drawable.pix_map,
        label = context.getString(R.string.widget_status_ip_geo),
        value = identity,
        background = background,
    )
    StatusFactRow(
        icon = R.drawable.pix_clock,
        label = context.getString(R.string.widget_status_latency),
        value = presentation.vpnLatencyMs.widgetLatencyText(context),
        background = background,
    )
    if (presentation.mode == StatusWidgetMode.TOR || presentation.mode == StatusWidgetMode.VPN_TOR) {
        StatusFactRow(
            icon = R.drawable.pix_tor,
            label = context.getString(R.string.widget_status_tor_ip_geo),
            value =
            if (refreshPhase == StatusWidgetRefreshPhase.LOADING) {
                context.getString(R.string.widget_status_refreshing)
            } else {
                presentation.torIdentity.widgetIdentityText()
            },
            valueColor = WIDGET_TOR,
            background = background,
        )
    }
}

@Composable
private fun StatusFactRow(
    icon: Int,
    label: String,
    value: String,
    background: WidgetBackground,
    valueColor: Color = background.text,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(background.secondaryText)),
            modifier = GlanceModifier.size(10.dp),
        )
        Spacer(modifier = GlanceModifier.width(3.dp))
        Text(text = label, style = widgetTextStyle(background.secondaryText, 8), maxLines = 1)
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(text = value, style = widgetTextStyle(valueColor, 8), maxLines = 1)
    }
}

@Composable
private fun StatusComponentsRow(
    context: Context,
    components: List<StatusWidgetComponent>,
    background: WidgetBackground,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.pix_settings),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(background.secondaryText)),
            modifier = GlanceModifier.size(10.dp),
        )
        Spacer(modifier = GlanceModifier.width(3.dp))
        Text(
            text = context.getString(R.string.widget_status_components),
            style = widgetTextStyle(background.secondaryText, 8),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (components.isEmpty()) {
            Text(text = WIDGET_DASH, style = widgetTextStyle(background.text, 8))
        } else {
            components.forEachIndexed { index, component ->
                if (index > 0) Spacer(modifier = GlanceModifier.width(3.dp))
                Image(
                    provider = ImageProvider(component.icon),
                    contentDescription = component.text(context),
                    colorFilter = ColorFilter.tint(ColorProvider(component.color)),
                    modifier = GlanceModifier.size(9.dp),
                )
                Spacer(modifier = GlanceModifier.width(1.dp))
                Text(text = component.text(context), style = widgetTextStyle(component.color, 7))
            }
        }
    }
}

@Composable
private fun StatusWidgetControls(
    context: Context,
    presentation: StatusWidgetPresentation,
) {
    val actions = statusWidgetControlActions(presentation.connection, presentation.primaryActive)
    when {
        actions.isEmpty() ->
            StatusOutlinedButton(
                context,
                presentation.connection.text(context),
                R.drawable.widget_refreshing_button,
                WIDGET_INFO,
                null,
                STATUS_WIDGET_CONTROL_HEIGHT,
            )
        actions.size == 2 ->
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                StatusWidgetControlButton(
                    context,
                    actions.first(),
                    STATUS_WIDGET_CONTROL_HEIGHT,
                    GlanceModifier.defaultWeight(),
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                StatusWidgetControlButton(
                    context,
                    actions.last(),
                    STATUS_WIDGET_CONTROL_HEIGHT,
                    GlanceModifier.defaultWeight(),
                )
            }
        else ->
            StatusWidgetControlButton(context, actions.single(), STATUS_WIDGET_CONTROL_HEIGHT)
    }
}

@Composable
private fun StatusWidgetControlButton(
    context: Context,
    action: StatusWidgetControlAction,
    height: Dp,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    val label =
        when (action) {
            StatusWidgetControlAction.START -> R.string.widget_status_start
            StatusWidgetControlAction.STOP -> R.string.widget_status_stop
            StatusWidgetControlAction.RESTART -> R.string.widget_status_restart
        }
    val background =
        when (action) {
            StatusWidgetControlAction.START -> R.drawable.widget_start_button
            StatusWidgetControlAction.STOP -> R.drawable.widget_stop_button
            StatusWidgetControlAction.RESTART -> R.drawable.widget_restart_button
        }
    val color =
        when (action) {
            StatusWidgetControlAction.START -> WIDGET_OK
            StatusWidgetControlAction.STOP -> WIDGET_ERROR
            StatusWidgetControlAction.RESTART -> WIDGET_ACCENT
        }
    val command =
        when (action) {
            StatusWidgetControlAction.START -> StatusWidgetCommandReceiver.ACTION_START
            StatusWidgetControlAction.STOP -> StatusWidgetCommandReceiver.ACTION_STOP
            StatusWidgetControlAction.RESTART -> StatusWidgetCommandReceiver.ACTION_RESTART
        }
    StatusOutlinedButton(
        context = context,
        label = context.getString(label),
        backgroundRes = background,
        color = color,
        action = command,
        height = height,
        modifier = modifier,
    )
}

@Composable
private fun StatusOutlinedButton(
    context: Context,
    label: String,
    backgroundRes: Int,
    color: Color,
    action: String?,
    height: Dp,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    Box(
        modifier =
        modifier
            .height(height)
            .background(ImageProvider(backgroundRes))
            .let { base ->
                if (action == null) {
                    base
                } else {
                    base.clickable(
                        actionSendBroadcast(
                            Intent(context, StatusWidgetCommandReceiver::class.java).setAction(action),
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = widgetTextStyle(color, 10), maxLines = 1)
    }
}

private fun StatusWidgetPresentation.summaryText(context: Context): String =
    routeText(context)

private fun StatusWidgetPresentation.routeText(context: Context): String =
    listOfNotNull(
        routeStatus?.let { status -> context.getString(status.labelRes) },
        context.getString(R.string.widget_component_i2p).takeIf { i2pConnected },
    ).joinToString(" · ").ifBlank { WIDGET_DASH }

private fun StatusWidgetConnection.text(context: Context): String =
    context.getString(
        when (this) {
            StatusWidgetConnection.CONNECTED -> R.string.widget_status_connected
            StatusWidgetConnection.CONNECTING -> R.string.widget_status_connecting
            StatusWidgetConnection.RECONNECTING -> R.string.widget_status_reconnecting
            StatusWidgetConnection.DISCONNECTING -> R.string.widget_status_disconnecting
            StatusWidgetConnection.DISCONNECTED -> R.string.widget_status_disconnected
            StatusWidgetConnection.ERROR -> R.string.widget_status_error
        },
    )

private val StatusWidgetConnection.color: Color
    get() =
        when (this) {
            StatusWidgetConnection.CONNECTED -> WIDGET_OK
            StatusWidgetConnection.CONNECTING,
            StatusWidgetConnection.RECONNECTING,
            StatusWidgetConnection.DISCONNECTING,
            -> WIDGET_INFO
            StatusWidgetConnection.DISCONNECTED,
            StatusWidgetConnection.ERROR,
            -> WIDGET_ERROR
        }

private val StatusWidgetComponent.icon: Int
    get() =
        when (this) {
            StatusWidgetComponent.FIREWALL -> R.drawable.pix_fire
            StatusWidgetComponent.TOR -> R.drawable.pix_tor
            StatusWidgetComponent.I2P -> R.drawable.pix_incognito
            StatusWidgetComponent.SENTINEL -> R.drawable.pix_shield
        }

private val StatusWidgetComponent.color: Color
    get() = if (this == StatusWidgetComponent.FIREWALL) WIDGET_INFO else WIDGET_TOR

private fun StatusWidgetComponent.text(context: Context): String =
    context.getString(
        when (this) {
            StatusWidgetComponent.FIREWALL -> R.string.widget_component_firewall
            StatusWidgetComponent.TOR -> R.string.widget_component_tor
            StatusWidgetComponent.I2P -> R.string.widget_component_i2p
            StatusWidgetComponent.SENTINEL -> R.string.widget_component_sentinel
        },
    )

private fun IpInfo?.widgetIdentityText(): String {
    if (this == null) return WIDGET_DASH
    val address = ip.takeIf(String::isNotBlank) ?: ipv4?.takeIf(String::isNotBlank)
    return listOfNotNull(
        countryCode?.trim()?.uppercase()?.takeIf(String::isNotBlank),
        city?.trim()?.takeIf(String::isNotBlank),
        address,
    ).joinToString(" · ").ifBlank { WIDGET_DASH }
}

private fun Long?.widgetLatencyText(context: Context): String =
    this?.takeIf { it > 0L }?.let { context.getString(R.string.widget_status_latency_value, it) }
        ?: WIDGET_DASH

private fun String?.orDash(): String = this?.takeIf(String::isNotBlank) ?: WIDGET_DASH

private fun widgetTextStyle(color: Color, fontSize: Int): TextStyle =
    TextStyle(
        color = ColorProvider(color),
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
    )

// 8 TOR+VPN fact rows (96dp) + 24dp header + 2dp gap + 24dp controls + 16dp padding.
// Below this height the compact summary is complete instead of showing a clipped partial table.
internal val STATUS_WIDGET_EXPANDED_HEIGHT = 162.dp
private val STATUS_WIDGET_CONTROL_HEIGHT = 24.dp
private const val WIDGET_DASH = "—"

internal fun statusWidgetRefreshSpinnerFrame(frame: Int): Int =
    when (frame.mod(STATUS_WIDGET_REFRESH_FRAME_COUNT)) {
        1 -> R.drawable.widget_refresh_spinner_90
        2 -> R.drawable.widget_refresh_spinner_180
        3 -> R.drawable.widget_refresh_spinner_270
        else -> R.drawable.widget_refresh_pixel
    }
