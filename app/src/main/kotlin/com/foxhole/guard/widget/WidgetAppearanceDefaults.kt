package com.foxhole.guard.widget

import android.content.Context
import android.content.res.Configuration
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.WidgetDefaultsSettings
import com.foxhole.core.model.WidgetKindAppearance

internal fun widgetDefaultBlackBackground(
    context: Context,
    themeMode: ThemeMode,
): Boolean =
    widgetDefaultBlackBackground(
        themeMode = themeMode,
        systemInDarkTheme =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES,
    )

internal fun widgetDefaultBlackBackground(
    themeMode: ThemeMode,
    systemInDarkTheme: Boolean,
): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.DARK -> true
    ThemeMode.OLED -> true
    ThemeMode.LIGHT -> false
}

internal fun WidgetDefaultsSettings.statusAppearanceForWidget(
    context: Context,
    themeMode: ThemeMode,
): WidgetKindAppearance =
    widgetAppearanceOrDefault(
        saved = status,
        defaultBlackBackground = widgetDefaultBlackBackground(context, themeMode),
        fallbackAlphaPercent = widgetFallbackOpacityPercent(alphaPercent),
    )

internal fun WidgetDefaultsSettings.webAppsAppearanceForWidget(
    context: Context,
    themeMode: ThemeMode,
): WidgetKindAppearance =
    widgetAppearanceOrDefault(
        saved = webApps,
        defaultBlackBackground = widgetDefaultBlackBackground(context, themeMode),
        fallbackAlphaPercent = widgetFallbackOpacityPercent(alphaPercent),
    )

internal fun widgetAppearanceOrDefault(
    saved: WidgetKindAppearance?,
    defaultBlackBackground: Boolean,
    fallbackAlphaPercent: Int = WIDGET_DEFAULT_OPACITY_PERCENT,
): WidgetKindAppearance =
    saved ?: WidgetKindAppearance(
        blackBackground = defaultBlackBackground,
        alphaPercent = fallbackAlphaPercent,
        outline = false,
    )

internal fun widgetFallbackOpacityPercent(legacyAlphaPercent: Int): Int {
    val normalized = legacyAlphaPercent.coerceIn(WIDGET_OPACITY_MIN_PERCENT, WIDGET_OPACITY_MAX_PERCENT)
    return if (normalized == LEGACY_WIDGET_DEFAULT_OPACITY_PERCENT) {
        WIDGET_DEFAULT_OPACITY_PERCENT
    } else {
        normalized
    }
}

private const val LEGACY_WIDGET_DEFAULT_OPACITY_PERCENT = 100
