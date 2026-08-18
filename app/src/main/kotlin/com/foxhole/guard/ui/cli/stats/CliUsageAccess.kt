package com.foxhole.guard.ui.cli.stats

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foxhole.guard.core.sentinel.anomaly.UsageAccessState
import com.foxhole.guard.core.sentinel.anomaly.UsageStatsAccess

@Composable
internal fun rememberUsageAccessState(): UsageAccessState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember(context) { mutableStateOf(UsageStatsAccess.state(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    state = UsageStatsAccess.state(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        state = UsageStatsAccess.state(context)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return state
}

internal fun shouldCompleteAppTrafficEnable(
    usageAccessGranted: Boolean,
    enablePending: Boolean,
): Boolean = usageAccessGranted && enablePending

internal fun openUsageAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
