package com.foxhole.guard.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.core.settings.updateWidgetAlphaPercent
import com.foxhole.guard.core.settings.updateWidgetBlackBackground
import com.foxhole.guard.widget.FoxStatusWidgetReceiver
import com.foxhole.guard.widget.StatusWidgetReceiver
import com.foxhole.guard.widget.WebAppsWidgetReceiver
import kotlinx.coroutines.launch

// Home widget defaults from app settings; the widgets pick up changes through the settings
// collector in FoxholeApplication.

internal fun HomeViewModel.onWidgetBlackBackgroundChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateWidgetBlackBackground(value)
    }
}

internal fun HomeViewModel.onWidgetAlphaPercentChanged(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateWidgetAlphaPercent(value)
    }
}

internal enum class HomeWidgetKind {
    CONNECTION,
    WEB_APPS,
    STATUS,
}

/** Opens the launcher's native add/configure flow for exactly the selected widget provider. */
internal fun HomeViewModel.onAddHomeWidget(kind: HomeWidgetKind) {
    val app = getApplication<Application>()
    val provider =
        when (kind) {
            HomeWidgetKind.CONNECTION -> StatusWidgetReceiver::class.java
            HomeWidgetKind.WEB_APPS -> WebAppsWidgetReceiver::class.java
            HomeWidgetKind.STATUS -> FoxStatusWidgetReceiver::class.java
        }
    val pinRequest = runCatching {
        val manager = AppWidgetManager.getInstance(app)
        if (!manager.isRequestPinAppWidgetSupported) {
            container.diagnosticsLogger.record(
                "widget",
                "launcher does not support pinned widget requests",
            )
            return
        }
        manager.requestPinAppWidget(ComponentName(app, provider), null, null)
    }
    val requestAccepted = pinRequest.getOrElse { failure ->
        // Launcher implementations are outside our process boundary. A rejected or broken pin
        // flow must leave this settings screen usable instead of taking the whole app down.
        container.diagnosticsLogger.record(
            "widget",
            "launcher widget request failed kind=$kind type=${failure::class.java.simpleName}",
        )
        false
    }
    if (!requestAccepted) {
        container.diagnosticsLogger.record("widget", "launcher declined pinned widget request kind=$kind")
    }
}
