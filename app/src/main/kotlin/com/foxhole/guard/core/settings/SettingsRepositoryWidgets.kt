package com.foxhole.guard.core.settings

// Home widget defaults (background and transparency); a file extension in the style of the other
// domains.

suspend fun SettingsRepository.updateWidgetBlackBackground(value: Boolean) =
    update { current -> current.copy(widgets = current.widgets.copy(blackBackground = value)) }

suspend fun SettingsRepository.updateWidgetAlphaPercent(value: Int) =
    update { current -> current.copy(widgets = current.widgets.copy(alphaPercent = value)) }
