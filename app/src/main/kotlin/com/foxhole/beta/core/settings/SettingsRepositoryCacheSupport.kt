package com.foxhole.beta.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal const val FAST_UI_PREFERENCES_NAME = "foxhole_fast_ui"
internal const val FAST_THEME_MODE_KEY = "theme_mode"
internal const val FAST_LOCALE_KEY = "locale"

internal object LegacySettingsKeys {
    val themeMode = stringPreferencesKey("theme_mode")
    val locale = stringPreferencesKey("locale")
    val autoReconnect = booleanPreferencesKey("auto_reconnect")
    val autoStartOnBoot = booleanPreferencesKey("auto_start_on_boot")
    val ipInfoEndpoint = stringPreferencesKey("ip_info_endpoint")
}
