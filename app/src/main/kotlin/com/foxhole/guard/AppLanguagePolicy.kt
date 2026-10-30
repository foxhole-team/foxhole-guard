package com.foxhole.guard

import com.foxhole.core.model.AppLocale

internal val GUARD_APP_LANGUAGE_CHOICES = listOf(AppLocale.SYSTEM, AppLocale.EN, AppLocale.RU, AppLocale.DE, AppLocale.FR, AppLocale.ES, AppLocale.JA, AppLocale.ZH)

internal data class AppLanguageDecision<T>(val language: T, val applyStored: Boolean)

internal fun <T> decideAppLanguage(stored: T, platform: T?, lastSynced: T?, system: T): AppLanguageDecision<T>? {
    // An encrypted choice that differs from the last completed sync is a durable app-side change.
    if (lastSynced != null && stored != lastSynced) return AppLanguageDecision(stored, true)
    if (platform == null) return null
    val keepStored = lastSynced == null && platform == system && stored != system
    return AppLanguageDecision(if (keepStored) stored else platform, keepStored)
}

internal fun languageFromPlatformTag(tag: String): AppLocale? {
    if (tag.isEmpty()) return AppLocale.SYSTEM
    val locale = java.util.Locale.forLanguageTag(tag)
    return when (locale.language) {
        "en" -> AppLocale.EN
        "ru" -> AppLocale.RU
        "de" -> AppLocale.DE
        "fr" -> AppLocale.FR
        "es" -> AppLocale.ES
        "ja" -> AppLocale.JA
        "zh" -> AppLocale.ZH.takeIf { locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO") }
        else -> null
    }
}
