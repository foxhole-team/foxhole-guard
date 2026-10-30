package com.foxhole.guard.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FoxStatusWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(110.dp, FOX_STATUS_WIDGET_HEIGHT),
                DpSize(400.dp, FOX_STATUS_WIDGET_HEIGHT),
            ),
        )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as FoxholeApplication).appGraph
        val connection = graph.connectionController
        provideContent {
            val snapshot by connection.snapshot.collectAsState()
            val connected = widgetHasPrimaryConnection(snapshot)
            val animatedFrame by FoxStatusWidgetAnimation.frame.collectAsState()
            val frame = foxStatusFrame(connected, false, animatedFrame)
            Box(
                modifier =
                GlanceModifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)
                    .clickable(
                        actionSendBroadcast(
                            Intent(context, StatusWidgetCommandReceiver::class.java)
                                .setAction(StatusWidgetCommandReceiver.ACTION_TOGGLE),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(frame),
                    contentDescription = context.getString(R.string.fox_status_widget_toggle),
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
        }
    }
}

class FoxStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FoxStatusWidget()
}

internal object FoxStatusWidgetAnimation {
    private val frameMutable = MutableStateFlow(R.drawable.ic_launcher_openwrt)
    val frame = frameMutable.asStateFlow()

    suspend fun renderConnectionState(context: Context, connected: Boolean) {
        frameMutable.value = foxStatusFrame(connected, false, 0)
        FoxStatusWidget().updateAll(context)
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun foxStatusFrame(
    connected: Boolean,
    animationEnabled: Boolean,
    animatedFrame: Int,
): Int = R.drawable.ic_launcher_openwrt

internal val FOX_STATUS_ANIMATION_KEY: Preferences.Key<Boolean> =
    booleanPreferencesKey("fox_status_animation")

internal val FOX_STATUS_ANIMATION_FRAMES =
    listOf(
        R.drawable.fhg_status_frame_1,
        R.drawable.fhg_status_frame_2,
        R.drawable.fhg_status_frame_3,
        R.drawable.fhg_status_frame_4,
        R.drawable.fhg_status_frame_5,
        R.drawable.fhg_status_frame_6,
    )
private val FOX_STATUS_WIDGET_HEIGHT = 110.dp
internal const val FOX_STATUS_FRAME_DURATION_MS = 320L
