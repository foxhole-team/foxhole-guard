package com.foxhole.guard.ui.cli

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.home.routeState

internal enum class CliConnectionHapticEvent {
    CONNECTED,
    DISCONNECTED,
}

internal data class CliConnectionHapticTracker(
    val initialized: Boolean = false,
    val connectedSession: Boolean = false,
)

internal fun CliConnectionHapticTracker.next(
    state: ConnectionState,
): Pair<CliConnectionHapticTracker, CliConnectionHapticEvent?> {
    if (!initialized) {
        return copy(initialized = true, connectedSession = state == ConnectionState.CONNECTED) to null
    }
    return when {
        state == ConnectionState.CONNECTED && !connectedSession ->
            copy(connectedSession = true) to CliConnectionHapticEvent.CONNECTED
        state in setOf(ConnectionState.IDLE, ConnectionState.ERROR) && connectedSession ->
            copy(connectedSession = false) to CliConnectionHapticEvent.DISCONNECTED
        else -> this to null
    }
}

@Composable
internal fun CliConnectionHapticEffect(viewModel: HomeViewModel) {
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    val state = home.connection.routeState()
    val view = LocalView.current
    var tracker by remember { mutableStateOf(CliConnectionHapticTracker()) }

    LaunchedEffect(state) {
        val (next, event) = tracker.next(state)
        tracker = next
        val feedback = when (event) {
            CliConnectionHapticEvent.CONNECTED ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            CliConnectionHapticEvent.DISCONNECTED ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            null -> return@LaunchedEffect
        }
        view.performHapticFeedback(feedback)
    }
}
