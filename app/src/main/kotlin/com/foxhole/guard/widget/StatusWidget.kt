package com.foxhole.guard.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.unit.ColorProvider
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.networkUp
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMainActivity
import java.util.Locale

class StatusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as FoxholeApplication).appGraph
        val connection = graph.connectionController
        provideContent {
            val prefs = currentState<Preferences>()
            val snapshot by connection.snapshot.collectAsState()
            val settings by graph.settingsRepository.settings.collectAsState()
            val defaults =
                settings.widgets.statusAppearanceForWidget(
                    context = context,
                    themeMode = settings.ui.themeMode,
                )
            val background = widgetBackground(prefs, defaults)
            val outlined = widgetOutlineEnabled(prefs, defaults)
            val layoutMode =
                initialStatusWidgetLayoutMode(
                    preferences = prefs,
                    durableDefault = settings.widgets.statusLayoutMode,
                )
            val vpnIpInfo by connection.ipInfo.collectAsState()
            val torIpInfo by connection.torRouteIpInfo.collectAsState()
            val deviceIpInfo by connection.deviceIpInfo.collectAsState()
            val i2pPhase by connection.i2pPhase.collectAsState()
            val refreshPhase by StatusWidgetRefreshState.phase.collectAsState()
            val refreshFrame by StatusWidgetRefreshState.frame.collectAsState()
            CompositionLocalProvider(LocalWidgetPixelArtEnabled provides settings.ui.pixelArtEnabled) {
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
                    layoutMode = layoutMode,
                    expanded = LocalSize.current.height >= STATUS_WIDGET_EXPANDED_HEIGHT,
                    refreshPhase = refreshPhase,
                    refreshFrame = refreshFrame,
                )
            }
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
    layoutMode: StatusWidgetLayoutMode,
    expanded: Boolean,
    refreshPhase: StatusWidgetRefreshPhase,
    refreshFrame: Int,
) {
    if (layoutMode == StatusWidgetLayoutMode.SIMPLE) {
        StatusWidgetSimpleContent(
            context = context,
            presentation = presentation,
            background = background,
            outlined = outlined,
            refreshPhase = refreshPhase,
        )
    } else {
        StatusWidgetExpandedContent(
            context = context,
            presentation = presentation,
            background = background,
            outlined = outlined,
            expanded = expanded,
            refreshPhase = refreshPhase,
            refreshFrame = refreshFrame,
        )
    }
}

@Composable
private fun StatusWidgetExpandedContent(
    context: Context,
    presentation: StatusWidgetPresentation,
    background: WidgetBackground,
    outlined: Boolean,
    expanded: Boolean,
    refreshPhase: StatusWidgetRefreshPhase,
    refreshFrame: Int,
) {
    WidgetFrame(background = background, outlined = outlined) {
        Column(
            modifier =
            GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)
                .clickable(actionStartActivity<CliMainActivity>()),
        ) {
            StatusWidgetHeader(context, background, refreshPhase, refreshFrame)
            Spacer(modifier = GlanceModifier.height(2.dp))
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
private fun StatusWidgetSimpleContent(
    context: Context,
    presentation: StatusWidgetPresentation,
    background: WidgetBackground,
    outlined: Boolean,
    refreshPhase: StatusWidgetRefreshPhase,
) {
    val status = presentation.simpleStatus()
    val identityContent = StatusWidgetSimpleIdentityContent(
        tokens = presentation.simpleIdentityTokens(),
        refreshPhase = refreshPhase,
        connection = presentation.connection,
    )
    val size = LocalSize.current
    val metrics = statusWidgetSimpleMetrics(size.width, size.height)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)
            .clickable(actionStartActivity<CliMainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        WidgetFrame(
            background = background,
            outlined = outlined,
            modifier = GlanceModifier.fillMaxWidth().height(statusWidgetSimpleSurfaceHeight(metrics)),
        ) {
            Row(
                modifier =
                GlanceModifier
                    .fillMaxSize()
                    .padding(
                        horizontal = metrics.horizontalPadding,
                        vertical = metrics.verticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusWidgetSimpleLogo(
                    context = context,
                    background = background,
                    metrics = metrics,
                )
                Spacer(modifier = GlanceModifier.width(metrics.sideSpacing))
                StatusWidgetSimpleStatusBlock(
                    context = context,
                    text = context.getString(status.labelRes),
                    color = status.color,
                    identityContent = identityContent,
                    deviceTone = presentation.simpleDeviceTone(),
                    background = background,
                    metrics = metrics,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(modifier = GlanceModifier.width(metrics.sideSpacing))
                StatusWidgetSimplePowerButton(context, presentation.simplePowerAction(), metrics)
            }
        }
    }
}

@Composable
private fun StatusWidgetSimpleLogo(
    context: Context,
    background: WidgetBackground,
    metrics: StatusWidgetSimpleMetrics,
) {
    Box(
        modifier = GlanceModifier
            .size(metrics.circleSize)
            .cornerRadius(WIDGET_CIRCLE_CORNER_RADIUS)
            .clickable(
                actionSendBroadcast(
                    Intent(context, StatusWidgetCommandReceiver::class.java)
                        .setAction(StatusWidgetCommandReceiver.ACTION_REFRESH),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_simple_logo_circle),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(background.icon)),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Image(
            provider = ImageProvider(R.drawable.ic_qs_tile),
            contentDescription = context.getString(R.string.widget_status_refresh),
            colorFilter = ColorFilter.tint(ColorProvider(background.icon)),
            modifier = GlanceModifier.size(metrics.logoSize),
        )
    }
}

@Composable
private fun StatusWidgetSimpleStatusBlock(
    context: Context,
    text: String,
    color: Color,
    identityContent: StatusWidgetSimpleIdentityContent,
    deviceTone: StatusWidgetSimpleDeviceTone,
    background: WidgetBackground,
    metrics: StatusWidgetSimpleMetrics,
    modifier: GlanceModifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusWidgetSimpleStatusText(
                context = context,
                text = text,
                color = color,
                fontSize = metrics.statusFontSize,
            )
            Spacer(modifier = GlanceModifier.height(1.dp))
            StatusWidgetSimpleIdentityRow(
                context = context,
                content = identityContent,
                deviceTone = deviceTone,
                background = background,
                metrics = metrics,
            )
        }
    }
}

@Composable
private fun StatusWidgetSimpleStatusText(
    context: Context,
    text: String,
    color: Color,
    fontSize: Int,
) {
    StyledWidgetText(
        context = context,
        text = text,
        color = color,
        fontSize = fontSize,
        uppercase = true,
    )
}

@Composable
private fun StatusWidgetSimpleIdentityRow(
    context: Context,
    content: StatusWidgetSimpleIdentityContent,
    deviceTone: StatusWidgetSimpleDeviceTone,
    background: WidgetBackground,
    metrics: StatusWidgetSimpleMetrics,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusWidgetSimpleDeviceIcon(
            context = context,
            tone = deviceTone,
            background = background,
            metrics = metrics,
        )
        Spacer(modifier = GlanceModifier.width(metrics.tokenSpacing))
        val refreshStatusRes = statusWidgetSimpleIdentityStatusRes(
            connection = content.connection,
            refreshPhase = content.refreshPhase,
        )
        if (refreshStatusRes != null) {
            val refreshColor =
                if (content.refreshPhase == StatusWidgetRefreshPhase.FAILED) {
                    WIDGET_ERROR
                } else {
                    background.text
                }
            StyledWidgetText(
                context = context,
                text = context.getString(refreshStatusRes),
                color = refreshColor,
                fontSize = metrics.locationFontSize,
            )
        } else {
            content.tokens.forEachIndexed { index, token ->
                if (index > 0) {
                    StyledWidgetText(
                        context = context,
                        text = " · ",
                        color = background.secondaryText,
                        fontSize = metrics.locationFontSize,
                    )
                }
                StatusWidgetSimpleLocation(context, token.identity, background, metrics)
            }
        }
    }
}

private data class StatusWidgetSimpleIdentityContent(
    val tokens: List<StatusWidgetSimpleIdentityToken>,
    val refreshPhase: StatusWidgetRefreshPhase,
    val connection: StatusWidgetConnection,
)

internal fun statusWidgetSimpleIdentityStatusRes(
    connection: StatusWidgetConnection,
    refreshPhase: StatusWidgetRefreshPhase,
): Int? = refreshPhase.simpleIdentityStatusRes() ?: when {
    connection == StatusWidgetConnection.CONNECTING ||
        connection == StatusWidgetConnection.RECONNECTING ||
        connection == StatusWidgetConnection.DISCONNECTING
    -> R.string.widget_status_updating_status
    else -> null
}

internal fun StatusWidgetRefreshPhase.simpleIdentityStatusRes(): Int? = when (this) {
    StatusWidgetRefreshPhase.IDLE -> null
    StatusWidgetRefreshPhase.LOADING -> R.string.widget_status_updating_status
    StatusWidgetRefreshPhase.FAILED -> R.string.widget_status_refresh_failed
}

@Composable
private fun StatusWidgetSimpleDeviceIcon(
    context: Context,
    tone: StatusWidgetSimpleDeviceTone,
    background: WidgetBackground,
    metrics: StatusWidgetSimpleMetrics,
) {
    if (tone == StatusWidgetSimpleDeviceTone.VPN_TOR) {
        Image(
            provider = ImageProvider(R.drawable.widget_simple_device_vpn_tor),
            contentDescription = context.getString(R.string.cli_rt_device),
            modifier = GlanceModifier.size(metrics.deviceIconSize),
        )
    } else {
        Image(
            provider = ImageProvider(R.drawable.lin_device),
            contentDescription = context.getString(R.string.cli_rt_device),
            colorFilter =
            ColorFilter.tint(
                ColorProvider(
                    if (tone == StatusWidgetSimpleDeviceTone.DISCONNECTED) {
                        background.secondaryText
                    } else {
                        tone.color
                    },
                ),
            ),
            modifier = GlanceModifier.size(metrics.deviceIconSize),
        )
    }
}

@Composable
private fun StatusWidgetSimpleLocation(
    context: Context,
    identity: IpInfo?,
    background: WidgetBackground,
    metrics: StatusWidgetSimpleMetrics,
) {
    val code = statusWidgetCountryFlagCode(identity?.countryCode)?.uppercase(Locale.US)
    val countryFlag = statusWidgetCountryFlag(context, code)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (countryFlag != null) {
            Image(
                provider = ImageProvider(countryFlag.drawableId),
                contentDescription = countryFlag.countryCode,
                modifier = GlanceModifier.width(metrics.flagWidth).height(metrics.flagHeight),
            )
            Spacer(modifier = GlanceModifier.width(3.dp))
        }
        StyledWidgetText(
            context = context,
            text = code ?: WIDGET_DASH,
            color = background.text,
            fontSize = metrics.locationFontSize,
        )
    }
}

internal fun statusWidgetSimpleSurfaceHeight(metrics: StatusWidgetSimpleMetrics): Dp =
    metrics.circleSize + metrics.verticalPadding + metrics.verticalPadding

@Composable
private fun StatusWidgetSimplePowerButton(
    context: Context,
    action: StatusWidgetSimplePowerAction,
    metrics: StatusWidgetSimpleMetrics,
) {
    val background =
        when (action) {
            StatusWidgetSimplePowerAction.CONNECT -> R.drawable.widget_simple_action_connect
            StatusWidgetSimplePowerAction.STOP -> R.drawable.widget_simple_action_stop
        }
    val color =
        when (action) {
            StatusWidgetSimplePowerAction.CONNECT -> WIDGET_OK
            StatusWidgetSimplePowerAction.STOP -> WIDGET_ERROR
        }
    Box(
        modifier =
        GlanceModifier
            .size(metrics.circleSize)
            .cornerRadius(WIDGET_CIRCLE_CORNER_RADIUS)
            .background(ImageProvider(background))
            .clickable(
                actionSendBroadcast(
                    Intent(context, StatusWidgetCommandReceiver::class.java)
                        .setAction(StatusWidgetCommandReceiver.ACTION_TOGGLE),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.lin_power),
            contentDescription =
            context.getString(
                when (action) {
                    StatusWidgetSimplePowerAction.CONNECT -> R.string.widget_status_turn_on
                    StatusWidgetSimplePowerAction.STOP -> R.string.widget_status_turn_off
                },
            ),
            colorFilter = ColorFilter.tint(ColorProvider(color)),
            modifier = GlanceModifier.size(metrics.powerSize),
        )
    }
}

internal fun statusWidgetSimpleMetrics(
    width: Dp,
    height: Dp,
): StatusWidgetSimpleMetrics {
    val compact =
        width < STATUS_WIDGET_SIMPLE_WIDE_WIDTH || height < STATUS_WIDGET_SIMPLE_TALL_HEIGHT
    return if (compact) {
        StatusWidgetSimpleMetrics(
            circleSize = 30.dp,
            logoSize = 20.dp,
            powerSize = 18.dp,
            deviceIconSize = 12.dp,
            statusFontSize = 11,
            locationFontSize = 10,
            horizontalPadding = 5.dp,
            verticalPadding = 2.dp,
            sideSpacing = 4.dp,
            tokenSpacing = 2.dp,
            flagWidth = 16.dp,
            flagHeight = 10.dp,
        )
    } else {
        StatusWidgetSimpleMetrics(
            circleSize = 36.dp,
            logoSize = 24.dp,
            powerSize = 20.dp,
            deviceIconSize = 15.dp,
            statusFontSize = 14,
            locationFontSize = 13,
            horizontalPadding = 10.dp,
            verticalPadding = 6.dp,
            sideSpacing = 8.dp,
            tokenSpacing = 3.dp,
            flagWidth = 20.dp,
            flagHeight = 12.dp,
        )
    }
}

internal data class StatusWidgetSimpleMetrics(
    val circleSize: Dp,
    val logoSize: Dp,
    val powerSize: Dp,
    val deviceIconSize: Dp,
    val statusFontSize: Int,
    val locationFontSize: Int,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val sideSpacing: Dp,
    val tokenSpacing: Dp,
    val flagWidth: Dp,
    val flagHeight: Dp,
)

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
                    .cornerRadius(WIDGET_CIRCLE_CORNER_RADIUS)
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
                            R.drawable.lin_update
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
        StyledWidgetText(
            context = context,
            text = presentation.connection.text(context),
            color = presentation.connection.color,
            fontSize = 9,
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Box(
            modifier = GlanceModifier.defaultWeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            StyledWidgetText(
                context = context,
                text = summary,
                color = background.text,
                fontSize = 9,
                maxWidth = statusWidgetSummaryMaxWidth(LocalSize.current.width),
            )
        }
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
        context = context,
        icon = R.drawable.lin_status,
        label = context.getString(R.string.widget_status_state),
        value = presentation.connection.text(context),
        valueColor = presentation.connection.color,
        background = background,
    )
    StatusFactRow(
        context = context,
        icon = R.drawable.lin_profiles,
        label = context.getString(R.string.widget_status_vpn_profile),
        value = presentation.vpnProfile.orDash(),
        background = background,
    )
    StatusFactRow(
        context = context,
        icon = R.drawable.lin_shield,
        label = context.getString(R.string.widget_status_vpn_protocol),
        value = presentation.vpnProtocol.orDash(),
        background = background,
    )
    StatusFactRow(
        context = context,
        icon = R.drawable.lin_dns,
        label = context.getString(R.string.widget_status_dns),
        value = presentation.dnsServer,
        background = background,
    )
    StatusFactRow(
        context = context,
        icon = R.drawable.lin_link,
        label = context.getString(R.string.widget_status_scenario),
        value = presentation.routeText(context),
        background = background,
    )
    StatusComponentsRow(context, presentation.components, background)
    val identityInfo = presentation.expandedIdentity()
    val identity =
        when (refreshPhase) {
            StatusWidgetRefreshPhase.LOADING -> context.getString(R.string.widget_status_refreshing)
            StatusWidgetRefreshPhase.FAILED -> context.getString(R.string.widget_status_refresh_failed)
            StatusWidgetRefreshPhase.IDLE -> identityInfo.widgetIdentityText()
        }
    StatusFactRow(
        context = context,
        icon = R.drawable.lin_map,
        label = context.getString(R.string.widget_status_ip_geo),
        value = identity,
        countryFlag = statusWidgetCountryFlag(
            context = context,
            countryCode = identityInfo?.countryCode
                .takeIf { refreshPhase == StatusWidgetRefreshPhase.IDLE },
        ),
        background = background,
    )
    StatusFactRow(
        context = context,
        icon = R.drawable.lin_clock,
        label = context.getString(R.string.widget_status_latency),
        value = presentation.vpnLatencyMs.widgetLatencyText(context),
        background = background,
    )
    if (presentation.mode == StatusWidgetMode.TOR || presentation.mode == StatusWidgetMode.VPN_TOR) {
        StatusFactRow(
            context = context,
            icon = R.drawable.lin_tor,
            label = context.getString(R.string.widget_status_tor_ip_geo),
            value =
            if (refreshPhase == StatusWidgetRefreshPhase.LOADING) {
                context.getString(R.string.widget_status_refreshing)
            } else {
                presentation.torIdentity.widgetIdentityText()
            },
            countryFlag = statusWidgetCountryFlag(
                context = context,
                countryCode = presentation.torIdentity?.countryCode
                    .takeIf { refreshPhase == StatusWidgetRefreshPhase.IDLE },
            ),
            valueColor = WIDGET_TOR,
            background = background,
        )
    }
}

@Composable
private fun StatusFactRow(
    context: Context,
    icon: Int,
    label: String,
    value: String,
    background: WidgetBackground,
    valueColor: Color = background.text,
    countryFlag: StatusWidgetCountryFlag? = null,
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
        StyledWidgetText(
            context = context,
            text = label,
            color = background.secondaryText,
            fontSize = 8,
            maxWidth = STATUS_WIDGET_FACT_LABEL_MAX_WIDTH,
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        StyledWidgetText(
            context = context,
            text = value,
            color = valueColor,
            fontSize = 8,
            maxWidth = statusWidgetFactValueMaxWidth(LocalSize.current.width),
        )
        countryFlag?.let { flag ->
            Spacer(modifier = GlanceModifier.width(3.dp))
            Image(
                provider = ImageProvider(flag.drawableId),
                contentDescription = flag.countryCode,
                modifier = GlanceModifier.width(12.dp).height(7.dp),
            )
        }
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
            provider = ImageProvider(R.drawable.lin_settings),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(background.secondaryText)),
            modifier = GlanceModifier.size(10.dp),
        )
        Spacer(modifier = GlanceModifier.width(3.dp))
        StyledWidgetText(
            context = context,
            text = context.getString(R.string.widget_status_components),
            color = background.secondaryText,
            fontSize = 8,
            maxWidth = STATUS_WIDGET_FACT_LABEL_MAX_WIDTH,
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (components.isEmpty()) {
            StyledWidgetText(context, WIDGET_DASH, background.text, 8)
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
                StyledWidgetText(context, component.text(context), component.color, 7)
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
            .cornerRadius(WIDGET_ACTION_CORNER_RADIUS)
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
        StyledWidgetText(
            context = context,
            text = label,
            color = color,
            fontSize = 10,
            uppercase = true,
        )
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

private val WIDGET_ACTION_CORNER_RADIUS = 6.dp
private val WIDGET_CIRCLE_CORNER_RADIUS = 100.dp

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

private val StatusWidgetSimpleStatus.color: Color
    get() =
        when (this) {
            StatusWidgetSimpleStatus.VPN,
            StatusWidgetSimpleStatus.VPN_TOR,
            -> WIDGET_OK
            StatusWidgetSimpleStatus.TOR -> WIDGET_TOR
            StatusWidgetSimpleStatus.I2P,
            StatusWidgetSimpleStatus.FIREWALL,
            StatusWidgetSimpleStatus.CONNECTING,
            StatusWidgetSimpleStatus.RECONNECTING,
            StatusWidgetSimpleStatus.DISCONNECTING,
            -> WIDGET_INFO
            StatusWidgetSimpleStatus.OFF,
            StatusWidgetSimpleStatus.ERROR,
            -> WIDGET_ERROR
        }

private val StatusWidgetSimpleDeviceTone.color: Color
    get() =
        when (this) {
            StatusWidgetSimpleDeviceTone.DISCONNECTED -> WIDGET_INFO
            StatusWidgetSimpleDeviceTone.VPN -> WIDGET_VPN
            StatusWidgetSimpleDeviceTone.TOR -> WIDGET_TOR
            StatusWidgetSimpleDeviceTone.VPN_TOR -> WIDGET_VPN
            StatusWidgetSimpleDeviceTone.I2P -> WIDGET_I2P
        }

private val StatusWidgetComponent.icon: Int
    get() =
        when (this) {
            StatusWidgetComponent.FIREWALL -> R.drawable.lin_fire
            StatusWidgetComponent.TOR -> R.drawable.lin_tor
            StatusWidgetComponent.I2P -> R.drawable.lin_incognito
            StatusWidgetComponent.SENTINEL -> R.drawable.lin_shield
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
    val country = countryCode?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        ?: countryName?.trim()?.takeIf(String::isNotBlank)
    return listOfNotNull(
        country,
        city?.trim()?.takeIf(String::isNotBlank),
        address,
    ).joinToString(" · ").ifBlank { WIDGET_DASH }
}

internal data class StatusWidgetCountryFlag(
    val drawableId: Int,
    val countryCode: String,
)

internal fun statusWidgetCountryFlag(
    context: Context,
    countryCode: String?,
): StatusWidgetCountryFlag? {
    val code = statusWidgetCountryFlagCode(countryCode) ?: return null

    @Suppress("DiscouragedApi")
    val drawableId = context.resources.getIdentifier("flag_$code", "drawable", context.packageName)
    return drawableId.takeIf { it != 0 }?.let {
        StatusWidgetCountryFlag(drawableId = it, countryCode = code.uppercase(Locale.US))
    }
}

internal fun statusWidgetCountryFlagCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { value -> value.length == 2 && value.all { it in 'a'..'z' } }

private fun Long?.widgetLatencyText(context: Context): String =
    this?.takeIf { it > 0L }?.let { context.getString(R.string.widget_status_latency_value, it) }
        ?: WIDGET_DASH

private fun String?.orDash(): String = this?.takeIf(String::isNotBlank) ?: WIDGET_DASH

internal val STATUS_WIDGET_EXPANDED_HEIGHT = 162.dp
private val STATUS_WIDGET_SIMPLE_WIDE_WIDTH = 240.dp
private val STATUS_WIDGET_SIMPLE_TALL_HEIGHT = 64.dp
private val STATUS_WIDGET_CONTROL_HEIGHT = 32.dp
private val STATUS_WIDGET_FACT_LABEL_MAX_WIDTH = 88.dp
private val STATUS_WIDGET_FACT_RESERVED_WIDTH = 150.dp
private val STATUS_WIDGET_FACT_VALUE_MIN_WIDTH = 72.dp
private val STATUS_WIDGET_SUMMARY_RESERVED_WIDTH = 105.dp
private val STATUS_WIDGET_SUMMARY_MIN_WIDTH = 72.dp
private const val WIDGET_DASH = "—"

private fun statusWidgetFactValueMaxWidth(widgetWidth: Dp): Dp =
    (widgetWidth - STATUS_WIDGET_FACT_RESERVED_WIDTH).coerceAtLeast(STATUS_WIDGET_FACT_VALUE_MIN_WIDTH)

private fun statusWidgetSummaryMaxWidth(widgetWidth: Dp): Dp =
    (widgetWidth - STATUS_WIDGET_SUMMARY_RESERVED_WIDTH).coerceAtLeast(STATUS_WIDGET_SUMMARY_MIN_WIDTH)

internal fun statusWidgetRefreshSpinnerFrame(frame: Int): Int =
    when (frame.mod(STATUS_WIDGET_REFRESH_FRAME_COUNT)) {
        1 -> R.drawable.widget_refresh_spinner_90
        2 -> R.drawable.widget_refresh_spinner_180
        3 -> R.drawable.widget_refresh_spinner_270
        else -> R.drawable.lin_update
    }
