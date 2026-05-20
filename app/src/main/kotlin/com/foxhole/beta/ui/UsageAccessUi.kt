package com.foxhole.beta.ui

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
import com.foxhole.beta.core.anomaly.UsageStatsAccess

@Composable
internal fun rememberUsageAccessGranted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember(context) { mutableStateOf(UsageStatsAccess.isGranted(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = UsageStatsAccess.isGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        granted = UsageStatsAccess.isGranted(context)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return granted
}

internal fun openUsageAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
