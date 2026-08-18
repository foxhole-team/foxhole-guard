package com.foxhole.guard.widget

import android.content.Context
import android.content.res.Configuration
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.WidgetDefaultsSettings
import com.foxhole.core.model.WidgetKindAppearance

internal fun widgetDefaultBlackBackground(
    context: Context,
    panelAppearance: PanelAppearance,
): Boolean =
    when (panelAppearance) {
        PanelAppearance.LIGHT -> false
        PanelAppearance.STANDARD,
        PanelAppearance.DARK,
        -> true
        PanelAppearance.AUTO ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

internal fun WidgetDefaultsSettings.statusAppearanceForWidget(
    context: Context,
    panelAppearance: PanelAppearance,
): WidgetKindAppearance =
    status ?: statusAppearance().copy(
        blackBackground = widgetDefaultBlackBackground(context, panelAppearance),
    )

internal fun WidgetDefaultsSettings.webAppsAppearanceForWidget(
    context: Context,
    panelAppearance: PanelAppearance,
): WidgetKindAppearance =
    webApps ?: webAppsAppearance().copy(
        blackBackground = widgetDefaultBlackBackground(context, panelAppearance),
    )
