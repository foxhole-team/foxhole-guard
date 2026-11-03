package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.AppLocale
import com.foxhole.guard.languageFromPlatformTag

internal fun displayedLocaleFlagCountry(locale: AppLocale, effectiveTag: String): String? {
    val displayed = if (locale == AppLocale.SYSTEM) languageFromPlatformTag(effectiveTag) ?: return null else locale
    return when (displayed) {
        AppLocale.SYSTEM -> null
        AppLocale.EN -> "gb"
        AppLocale.RU -> "ru"
        AppLocale.DE -> "de"
        AppLocale.FR -> "fr"
        AppLocale.ES -> "es"
        AppLocale.JA -> "jp"
        AppLocale.ZH -> "cn"
    }
}
