package com.foxhole.guard.widget

import androidx.glance.appwidget.GlanceAppWidget

/** Exact identity for the two widgets handled by [WidgetConfigActivity]. */
internal enum class ConfigurableWidgetProvider {
    CONNECTION,
    WEB_APPS,
    ;

    fun createWidget(): GlanceAppWidget =
        when (this) {
            CONNECTION -> StatusWidget()
            WEB_APPS -> WebAppsWidget()
        }
}

/** Unknown and missing providers are configuration failures, never a different widget fallback. */
internal fun configurableWidgetProvider(className: String?): ConfigurableWidgetProvider? =
    when (className) {
        StatusWidgetReceiver::class.java.name -> ConfigurableWidgetProvider.CONNECTION
        WebAppsWidgetReceiver::class.java.name -> ConfigurableWidgetProvider.WEB_APPS
        else -> null
    }
