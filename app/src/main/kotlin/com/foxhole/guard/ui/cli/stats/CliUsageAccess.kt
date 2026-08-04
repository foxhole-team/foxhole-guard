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
import com.foxhole.guard.core.sentinel.anomaly.UsageStatsAccess

/**
 * Usage Access is the one system permission without which per-app statistics are physically empty.
 * It is granted on a settings screen rather than in a dialog, so the state must be re-read on every
 * return to the app: the user leaves to grant it and comes back through ON_RESUME.
 *
 * A port of the original front's helpers into the CLI canon; the check itself lives in
 * [UsageStatsAccess].
 */
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

/** The Usage Access screen; on ROMs lacking it, fall back to the settings root. */
internal fun openUsageAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
