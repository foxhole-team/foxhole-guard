package com.foxhole.guard.core.settings

import com.foxhole.core.model.StatusWidgetLayoutMode
import com.foxhole.core.model.WidgetKindAppearance

suspend fun SettingsRepository.updateStatusWidgetAppearance(
    transform: (WidgetKindAppearance) -> WidgetKindAppearance,
    baseAppearance: WidgetKindAppearance? = null,
) = update { current ->
    current.copy(
        widgets = current.widgets.copy(
            status = transform(baseAppearance ?: current.widgets.statusAppearance()),
        ),
    )
}

suspend fun SettingsRepository.updateWebAppsWidgetAppearance(
    transform: (WidgetKindAppearance) -> WidgetKindAppearance,
    baseAppearance: WidgetKindAppearance? = null,
) = update { current ->
    current.copy(
        widgets = current.widgets.copy(
            webApps = transform(baseAppearance ?: current.widgets.webAppsAppearance()),
        ),
    )
}

suspend fun SettingsRepository.updateFoxWidgetAnimationEnabled(value: Boolean) =
    update { current -> current.copy(widgets = current.widgets.copy(foxAnimationEnabled = value)) }

suspend fun SettingsRepository.updateStatusWidgetLayoutMode(value: StatusWidgetLayoutMode) =
    update { current -> current.copy(widgets = current.widgets.copy(statusLayoutMode = value)) }
