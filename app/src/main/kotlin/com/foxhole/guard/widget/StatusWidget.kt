package com.foxhole.guard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeTileDependencies
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.reconcileActiveVpnNetworkIfNeeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The connection-status widget: a status line from the runtime bridge snapshot plus the quick
 * commands, which dispatch the same service actions as the tile and hold no connection logic of
 * their own.
 */
class StatusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val defaults =
            (context.applicationContext as FoxholeApplication).appGraph
                .settingsRepository.settings.value.widgets
        provideContent {
            val prefs = currentState<Preferences>()
            val background = widgetBackground(prefs, defaults)
            val snapshot by FoxholeVpnRuntimeBridge.snapshot.collectAsState()
            StatusWidgetContent(context = context, snapshot = snapshot, background = background)
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

@Composable
private fun StatusWidgetContent(
    context: Context,
    snapshot: ConnectionSnapshot,
    background: WidgetBackground,
) {
    Box(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(background.fill))) {
        Image(
            provider = ImageProvider(R.drawable.widget_frame),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
            StatusWidgetBody(context = context, snapshot = snapshot, background = background)
        }
    }
}

@Composable
private fun StatusWidgetBody(
    context: Context,
    snapshot: ConnectionSnapshot,
    background: WidgetBackground,
) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Text(
            text = context.getString(R.string.cli_status_widget_label),
            style = TextStyle(
                color = ColorProvider(WIDGET_ACCENT),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Text(
            // The state words are CLI-canon commands and are not localised.
            text = statusLine(snapshot),
            style = TextStyle(
                color = ColorProvider(background.text),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusCommandChip(context, "[start]", StatusWidgetCommandReceiver.ACTION_START)
            Spacer(modifier = GlanceModifier.defaultWeight())
            StatusCommandChip(context, "[stop]", StatusWidgetCommandReceiver.ACTION_STOP)
            Spacer(modifier = GlanceModifier.defaultWeight())
            StatusCommandChip(context, "[restart]", StatusWidgetCommandReceiver.ACTION_RESTART)
        }
    }
}

@Composable
private fun StatusCommandChip(context: Context, label: String, action: String) {
    Text(
        text = label,
        style = TextStyle(
            color = ColorProvider(WIDGET_ACCENT),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = GlanceModifier
            .padding(4.dp)
            .clickable(
                actionSendBroadcast(
                    Intent(context, StatusWidgetCommandReceiver::class.java).setAction(action),
                ),
            ),
    )
}

internal fun statusLine(snapshot: ConnectionSnapshot): String =
    when (snapshot.state) {
        ConnectionState.CONNECTED ->
            "${snapshot.trafficMode.name.lowercase()}: up" +
                (snapshot.profileName?.takeIf(String::isNotBlank)?.let { " · ${it.lowercase()}" } ?: "")
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> "connecting…"
        ConnectionState.ERROR -> "error"
        ConnectionState.IDLE -> "idle"
    }

/**
 * The quick commands replicate the tile's path exactly: an optimistic bridge update plus the same
 * contract service actions. A widget click grants a temporary allowlist for the FGS start.
 */
class StatusWidgetCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handle(context.applicationContext, action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(appContext: Context, action: String) {
        val application = appContext as? FoxholeApplication ?: return
        val dependencies: FoxholeTileDependencies = application.appGraph
        dependencies.connectionController.reconcileActiveVpnNetworkIfNeeded()
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val active = snapshot.state in ACTIVE_STATES
        val trafficMode = dependencies.settingsRepository.settings.value.traffic.mode
        when (action) {
            ACTION_START -> if (!active) {
                FoxholeVpnRuntimeBridge.update(
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTING,
                        trafficMode = trafficMode,
                        profileId = snapshot.profileId,
                        profileName = snapshot.profileName,
                        protocolHint = snapshot.protocolHint,
                        protocolOptionId = snapshot.protocolOptionId,
                    ),
                )
                FoxholeConnectionServiceContract.startForegroundService(
                    context = appContext,
                    mode = trafficMode,
                    action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                )
            }
            ACTION_STOP -> if (active) {
                FoxholeVpnRuntimeBridge.update(snapshot.copy(state = ConnectionState.IDLE, message = null))
                FoxholeConnectionServiceContract.startForegroundService(
                    context = appContext,
                    mode = FoxholeConnectionServiceContract.serviceMode(
                        snapshot = snapshot,
                        fallbackMode = trafficMode,
                    ),
                    action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                )
            }
            ACTION_RESTART -> if (active) {
                FoxholeConnectionServiceContract.startForegroundService(
                    context = appContext,
                    mode = FoxholeConnectionServiceContract.serviceMode(
                        snapshot = snapshot,
                        fallbackMode = trafficMode,
                    ),
                    action = FoxholeConnectionServiceContract.ACTION_RELOAD,
                )
            }
        }
    }

    companion object {
        const val ACTION_START = "com.foxhole.guard.widget.action.START"
        const val ACTION_STOP = "com.foxhole.guard.widget.action.STOP"
        const val ACTION_RESTART = "com.foxhole.guard.widget.action.RESTART"
        private val ACTIVE_STATES =
            setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)
    }
}
