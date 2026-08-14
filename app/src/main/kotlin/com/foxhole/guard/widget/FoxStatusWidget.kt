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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The image-only status control. Its height is fixed by provider metadata; horizontal resizing
 * changes only the centred scale. There is no decorative frame or label around the supplied art.
 */
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
        val connection = (context.applicationContext as FoxholeApplication).appGraph.connectionController
        provideContent {
            val preferences = currentState<Preferences>()
            val animationEnabled = preferences[FOX_STATUS_ANIMATION_KEY] ?: true
            val snapshot by connection.snapshot.collectAsState()
            val connected = widgetHasPrimaryConnection(snapshot)
            val animatedFrame by FoxStatusWidgetAnimation.frame.collectAsState()
            val frame = foxStatusFrame(connected, animationEnabled, animatedFrame)
            Box(
                modifier =
                GlanceModifier
                    .fillMaxSize()
                    .padding(2.dp)
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

/** Continuous six-frame loop while a confirmed primary connection keeps the process alive. */
internal object FoxStatusWidgetAnimation {
    private val frameMutable = MutableStateFlow(FOX_STATUS_DISCONNECTED_FRAME)
    private val generation = AtomicLong(0L)
    val frame = frameMutable.asStateFlow()

    suspend fun renderConnectionState(context: Context, connected: Boolean) {
        val ownedGeneration = generation.incrementAndGet()
        if (!connected) {
            frameMutable.value = FOX_STATUS_DISCONNECTED_FRAME
            FoxStatusWidget().updateAll(context)
            return
        }
        while (generation.get() == ownedGeneration) {
            val widgetIds = animatedWidgetIds(context)
            if (widgetIds.isEmpty()) {
                frameMutable.value = FOX_STATUS_CONNECTED_FRAME
                FoxStatusWidget().updateAll(context)
                return
            }
            FOX_STATUS_ANIMATION_FRAMES.forEach { frame ->
                if (generation.get() != ownedGeneration) return
                frameMutable.value = frame
                // Glance's updateAll may be coalesced by the launcher. Updating each concrete id
                // gives every animation-enabled widget one visible frame.
                widgetIds.forEach { id -> FoxStatusWidget().update(context, id) }
                delay(FOX_STATUS_FRAME_DURATION_MS)
            }
        }
    }

    private suspend fun animatedWidgetIds(context: Context): List<GlanceId> =
        GlanceAppWidgetManager(context)
            .getGlanceIds(FoxStatusWidget::class.java)
            .filter { id -> animationEnabled(context, id) }

    private suspend fun animationEnabled(context: Context, id: GlanceId): Boolean =
        try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[FOX_STATUS_ANIMATION_KEY]
                ?: true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A launcher may remove an id between discovery and state lookup. The following update
            // will reconcile it; default-on preserves the documented preference for live ids.
            true
        }
}

internal fun foxStatusFrame(
    connected: Boolean,
    animationEnabled: Boolean,
    animatedFrame: Int,
): Int =
    when {
        !connected -> FOX_STATUS_DISCONNECTED_FRAME
        !animationEnabled -> FOX_STATUS_CONNECTED_FRAME
        animatedFrame in FOX_STATUS_ANIMATION_FRAMES -> animatedFrame
        else -> FOX_STATUS_CONNECTED_FRAME
    }

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

// Resource ids are intentionally read at runtime. Inlining Android's generated R constants into a
// pure JVM caller makes local unit tests observe the stub value (0) instead of the packaged id.
private val FOX_STATUS_CONNECTED_FRAME = R.drawable.fhg_status_frame_1
private val FOX_STATUS_DISCONNECTED_FRAME = R.drawable.fhg_status_frame_4
