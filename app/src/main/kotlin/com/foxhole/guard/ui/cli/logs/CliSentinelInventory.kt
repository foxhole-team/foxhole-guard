package com.foxhole.guard.ui.cli.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.loadInstalledApps

@Composable
internal fun CliRefreshInstalledAppsOnResume(viewModel: HomeViewModel, enabled: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (enabled && event == Lifecycle.Event.ON_RESUME) viewModel.loadInstalledApps(force = true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
