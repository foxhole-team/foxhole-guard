package com.foxhole.guard

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

internal fun Context.withStoredAppLocale(): Context {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (tags.isBlank()) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(tags))
    return createConfigurationContext(config)
}
