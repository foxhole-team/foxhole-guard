package com.foxhole.beta.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.foxhole.beta.BuildConfig
import java.util.concurrent.atomic.AtomicReference

internal const val SETTINGS_DETAIL_DUPLICATE_TAP_SUPPRESSION_MS = 350L

internal class SettingsDetailNavigationGate(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastAcceptedRoute: String? = null
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE

    fun tryAccept(
        currentRoute: String?,
        targetRoute: String,
    ): SettingsDetailNavigationDecision {
        val nowMs = clock()
        val elapsedSinceLastAccepted = nowMs - lastAcceptedAtMs
        val decision =
            when {
                currentRoute == targetRoute ->
                    SettingsDetailNavigationDecision.Rejected(nowMs, "same_route")
                lastAcceptedRoute == targetRoute &&
                    elapsedSinceLastAccepted in 0 until SETTINGS_DETAIL_DUPLICATE_TAP_SUPPRESSION_MS ->
                    SettingsDetailNavigationDecision.Rejected(nowMs, "duplicate_tap")
                else -> {
                    lastAcceptedRoute = targetRoute
                    lastAcceptedAtMs = nowMs
                    SettingsDetailNavigationDecision.Accepted(nowMs)
                }
            }
        return decision
    }
}

internal sealed interface SettingsDetailNavigationDecision {
    val atMs: Long

    data class Accepted(
        override val atMs: Long,
    ) : SettingsDetailNavigationDecision

    data class Rejected(
        override val atMs: Long,
        val reason: String,
    ) : SettingsDetailNavigationDecision
}

internal class NavigationTransitionTelemetry(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val scheduleFirstFrameCallback: (() -> Unit) -> Unit = ::scheduleNavigationFirstFrameCallback,
) {
    private val pendingTransition = AtomicReference<PendingNavigationTransition?>(null)

    fun recordTap(
        routeFrom: String?,
        routeTo: String,
        tapTimeMs: Long = clock(),
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        pendingTransition.set(
            PendingNavigationTransition(
                routeFrom = routeFrom.orUnknownRoute(),
                routeTo = routeTo,
                tapTimeMs = tapTimeMs,
                navigateCallTimeMs = null,
            ),
        )
    }

    fun recordNavigateCall(
        routeTo: String,
        navigateCallTimeMs: Long = clock(),
    ) {
        if (BuildConfig.DEBUG) {
            var shouldScheduleFirstFrame = false
            while (!shouldScheduleFirstFrame) {
                val pending = pendingTransition.get()
                if (pending == null || pending.routeTo != routeTo) {
                    break
                }
                val updated = pending.copy(navigateCallTimeMs = navigateCallTimeMs)
                shouldScheduleFirstFrame = pendingTransition.compareAndSet(pending, updated)
            }
            if (shouldScheduleFirstFrame) {
                scheduleFirstFrameCallback { recordFirstFrame(routeTo) }
            }
        }
    }

    fun recordTransitionJank(
        routeTo: String,
        droppedFrames: Int,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                NAVIGATION_TELEMETRY_TAG,
                "transition_jank route_to=$routeTo skipped_frames_during_transition=$droppedFrames",
            )
        }
    }

    fun recordRejected(
        routeFrom: String?,
        routeTo: String,
        reason: String,
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        Log.d(
            NAVIGATION_TELEMETRY_TAG,
            "navigation_rejected route_from=${routeFrom.orUnknownRoute()} route_to=$routeTo reason=$reason",
        )
    }

    fun recordCancelled(routeTo: String) {
        if (BuildConfig.DEBUG) {
            pendingTransition.updateAndGet { pending ->
                when {
                    pending == null -> null
                    pending.routeTo == routeTo -> null
                    else -> pending
                }
            }
        }
    }

    fun recordFirstFrame(
        currentRoute: String,
        firstFrameTimeMs: Long = clock(),
    ) {
        if (BuildConfig.DEBUG) {
            val pending = pendingTransition.get()
            if (
                pending != null &&
                pending.routeTo == currentRoute &&
                pendingTransition.compareAndSet(pending, null)
            ) {
                logFirstFrame(pending, firstFrameTimeMs)
            }
        }
    }

    private fun logFirstFrame(
        pending: PendingNavigationTransition,
        firstFrameTimeMs: Long,
    ) {
        val navigateCallTimeMs = pending.navigateCallTimeMs ?: pending.tapTimeMs
        Log.d(
            NAVIGATION_TELEMETRY_TAG,
            buildString {
                append("transition ")
                append("route_from=${pending.routeFrom} ")
                append("route_to=${pending.routeTo} ")
                append("tap_time=${pending.tapTimeMs} ")
                append("navigate_call_time=$navigateCallTimeMs ")
                append("first_frame_after_tap_ms=${firstFrameTimeMs - pending.tapTimeMs} ")
                append("transition_start=$navigateCallTimeMs ")
                append("transition_end_estimate=${navigateCallTimeMs + NAVIGATION_DETAIL_TRANSITION_MS}")
            },
        )
    }
}

@Composable
internal fun NavigationTransitionTelemetryEffect(
    currentRoute: String?,
    telemetry: NavigationTransitionTelemetry,
) {
    if (!BuildConfig.DEBUG) {
        return
    }
    LaunchedEffect(currentRoute) {
        val route = currentRoute ?: return@LaunchedEffect
        withFrameNanos { }
        telemetry.recordFirstFrame(route)
        // Count real dropped frames across the full animation window by measuring vsync gaps.
        // Each gap > 1 vsync at 60fps means frames were dropped by the compositor.
        var lastFrameNanos = 0L
        var droppedFrames = 0
        repeat(TRANSITION_JANK_WINDOW_FRAMES) {
            val frameNanos: Long = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val gapNs = frameNanos - lastFrameNanos
                val dropsInGap = ((gapNs.toFloat() / VSYNC_INTERVAL_NS) - 1f).toInt().coerceAtLeast(0)
                droppedFrames += dropsInGap
            }
            lastFrameNanos = frameNanos
        }
        telemetry.recordTransitionJank(route, droppedFrames)
    }
}

private data class PendingNavigationTransition(
    val routeFrom: String,
    val routeTo: String,
    val tapTimeMs: Long,
    val navigateCallTimeMs: Long?,
)

private fun String?.orUnknownRoute(): String = this ?: "unknown"

private fun scheduleNavigationFirstFrameCallback(callback: () -> Unit) {
    val postFrameCallback = {
        Choreographer.getInstance().postFrameCallback {
            callback()
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        postFrameCallback()
    } else {
        Handler(Looper.getMainLooper()).post {
            postFrameCallback()
        }
    }
}

private const val NAVIGATION_TELEMETRY_TAG = "FoxholeNavigation"
private const val NAVIGATION_DETAIL_TRANSITION_MS = 150L

// 25 frames covers ~416ms at 60fps — enough to span the longest animation (320ms indicator).
private const val TRANSITION_JANK_WINDOW_FRAMES = 25

// Nanoseconds per vsync at 60fps; gaps larger than this indicate dropped frames.
private const val VSYNC_INTERVAL_NS = 16_666_667L
