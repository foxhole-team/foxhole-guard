package com.foxhole.beta.ui

import android.os.SystemClock
import android.util.Log
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
        if (!BuildConfig.DEBUG) {
            return
        }
        pendingTransition.updateAndGet { pending ->
            when {
                pending == null -> null
                pending.routeTo != routeTo -> pending
                else -> pending.copy(navigateCallTimeMs = navigateCallTimeMs)
            }
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
                append("transition_end_estimate=${navigateCallTimeMs + NAVIGATION_DETAIL_TRANSITION_MS} ")
                append("skipped_frames_during_transition=unavailable ")
                append("route_state_size_estimate=unavailable")
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
    }
}

private data class PendingNavigationTransition(
    val routeFrom: String,
    val routeTo: String,
    val tapTimeMs: Long,
    val navigateCallTimeMs: Long?,
)

private fun String?.orUnknownRoute(): String = this ?: "unknown"

private const val NAVIGATION_TELEMETRY_TAG = "FoxholeNavigation"
private const val NAVIGATION_DETAIL_TRANSITION_MS = 150L
