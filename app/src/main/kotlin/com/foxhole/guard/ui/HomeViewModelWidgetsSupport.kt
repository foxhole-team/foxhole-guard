package com.foxhole.guard.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.StatusWidgetLayoutMode
import com.foxhole.guard.core.settings.updateFoxWidgetAnimationEnabled
import com.foxhole.guard.core.settings.updateStatusWidgetAppearance
import com.foxhole.guard.core.settings.updateStatusWidgetLayoutMode
import com.foxhole.guard.core.settings.updateWebAppsWidgetAppearance
import com.foxhole.guard.widget.FoxStatusWidgetReceiver
import com.foxhole.guard.widget.StatusWidgetReceiver
import com.foxhole.guard.widget.WebAppsWidgetReceiver
import com.foxhole.guard.widget.statusAppearanceForWidget
import com.foxhole.guard.widget.webAppsAppearanceForWidget
import kotlinx.coroutines.launch

internal fun HomeViewModel.onWidgetBlackBackgroundChanged(kind: HomeWidgetKind, value: Boolean) {
    updateWidgetAppearance(kind) { current -> current.copy(blackBackground = value) }
}

internal fun HomeViewModel.onWidgetAlphaPercentChanged(kind: HomeWidgetKind, value: Int) {
    updateWidgetAppearance(kind) { current -> current.copy(alphaPercent = value.coerceIn(0, 100)) }
}

internal fun HomeViewModel.onWidgetOutlineChanged(kind: HomeWidgetKind, value: Boolean) {
    updateWidgetAppearance(kind) { current -> current.copy(outline = value) }
}

internal fun HomeViewModel.onFoxWidgetAnimationChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateFoxWidgetAnimationEnabled(value)
    }
}

internal fun HomeViewModel.onStatusWidgetLayoutModeChanged(value: StatusWidgetLayoutMode) {
    viewModelScope.launch {
        container.settingsRepository.updateStatusWidgetLayoutMode(value)
    }
}

private fun HomeViewModel.updateWidgetAppearance(
    kind: HomeWidgetKind,
    transform: (com.foxhole.core.model.WidgetKindAppearance) -> com.foxhole.core.model.WidgetKindAppearance,
) {
    val settings = container.settingsRepository.settings.value
    val context = getApplication<Application>()
    val baseAppearance =
        when (kind) {
            HomeWidgetKind.CONNECTION ->
                settings.widgets.statusAppearanceForWidget(context, settings.ui.themeMode)
            HomeWidgetKind.WEB_APPS ->
                settings.widgets.webAppsAppearanceForWidget(context, settings.ui.themeMode)
            HomeWidgetKind.STATUS -> null
        }
    viewModelScope.launch {
        when (kind) {
            HomeWidgetKind.CONNECTION ->
                container.settingsRepository.updateStatusWidgetAppearance(transform, baseAppearance)
            HomeWidgetKind.WEB_APPS ->
                container.settingsRepository.updateWebAppsWidgetAppearance(transform, baseAppearance)
            HomeWidgetKind.STATUS -> Unit
        }
    }
}

internal enum class HomeWidgetKind {
    CONNECTION,
    WEB_APPS,
    STATUS,
}

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
