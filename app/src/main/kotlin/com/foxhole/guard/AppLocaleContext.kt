package com.foxhole.guard

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * A Context carrying the locale chosen in the app. On Android 13+ the system applies the per-app
 * locale, services included; below that AppCompatDelegate reaches only AppCompat activities, so
 * Service.getString() resolves against the *system* locale and notifications ignore the setting.
 * SYSTEM returns the original Context unwrapped.
 */
internal fun Context.withStoredAppLocale(): Context {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (tags.isBlank()) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(tags))
    return createConfigurationContext(config)
}
