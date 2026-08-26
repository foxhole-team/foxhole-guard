package com.foxhole.guard.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Locale

internal typealias StatusWidgetLayoutMode = com.foxhole.core.model.StatusWidgetLayoutMode

internal val STATUS_WIDGET_LAYOUT_MODE_KEY: Preferences.Key<String> =
    stringPreferencesKey("status_layout_mode")

internal val SELECTABLE_STATUS_WIDGET_LAYOUT_MODES: List<StatusWidgetLayoutMode> =
    listOf(StatusWidgetLayoutMode.SIMPLE)

internal fun statusWidgetLayoutMode(preferences: Preferences): StatusWidgetLayoutMode =
    activeStatusWidgetLayoutMode(
        parseStatusWidgetLayoutMode(preferences[STATUS_WIDGET_LAYOUT_MODE_KEY]),
    )

internal fun initialStatusWidgetLayoutMode(
    preferences: Preferences,
    durableDefault: StatusWidgetLayoutMode,
): StatusWidgetLayoutMode =
    activeStatusWidgetLayoutMode(
        preferences[STATUS_WIDGET_LAYOUT_MODE_KEY]?.let(::parseStatusWidgetLayoutMode)
            ?: durableDefault,
    )

internal fun activeStatusWidgetLayoutMode(mode: StatusWidgetLayoutMode): StatusWidgetLayoutMode =
    mode.takeIf(SELECTABLE_STATUS_WIDGET_LAYOUT_MODES::contains)
        ?: StatusWidgetLayoutMode.SIMPLE

internal fun parseStatusWidgetLayoutMode(value: String?): StatusWidgetLayoutMode =
    when (value?.trim()?.lowercase(Locale.US)) {
        StatusWidgetLayoutMode.SIMPLE.persistedValue,
        "compact",
        -> StatusWidgetLayoutMode.SIMPLE
        StatusWidgetLayoutMode.EXPANDED.persistedValue,
        "full",
        -> StatusWidgetLayoutMode.EXPANDED
        else -> StatusWidgetLayoutMode.SIMPLE
    }
