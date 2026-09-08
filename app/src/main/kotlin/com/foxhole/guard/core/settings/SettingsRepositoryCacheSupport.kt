package com.foxhole.guard.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.DashboardCard
import com.foxhole.core.model.HomeAdditionalInfoCategory

internal const val FAST_UI_PREFERENCES_NAME = "foxhole_fast_ui"
internal const val FAST_THEME_MODE_KEY = "theme_mode"
internal const val FAST_MONOCHROME_KEY = "monochrome_enabled"
internal const val FAST_ACCENT_COLOR_KEY = "accent_color"
internal const val FAST_PIXEL_ART_ENABLED_KEY = "pixel_art_enabled"
internal const val FAST_LOCALE_KEY = "locale"
internal const val FAST_APP_LOCK_MODE_KEY = "app_lock_mode"
internal const val FAST_APP_LOCK_BUILTIN_PAD_KEY = "app_lock_builtin_pad"
internal const val FAST_APP_LOCK_SCRAMBLE_KEY = "app_lock_scramble"
internal const val FAST_NETWORK_CARD_ENABLED_KEY = "network_card_enabled"
internal const val FAST_TRAFFIC_CARD_ENABLED_KEY = "traffic_card_enabled"
internal const val FAST_TRAFFIC_MAP_ENABLED_KEY = "traffic_map_enabled"
internal const val FAST_NETWORK_DNS_INFO_ENABLED_KEY = "network_dns_info_enabled"
internal const val FAST_SHOW_FIREWALL_STATUS_KEY = "show_firewall_status"
internal const val FAST_SHOW_TOR_QUICK_LAUNCH_KEY = "show_tor_quick_launch"
internal const val FAST_SHOW_QUICK_ACCESS_PANEL_KEY = "show_quick_access_panel"
internal const val FAST_MAP_ON_RIGHT_KEY = "map_on_right"
internal const val FAST_TRAFFIC_CARD_VIEW_KEY = "traffic_card_view"
internal const val FAST_DASHBOARD_CARD_ORDER_KEY = "dashboard_card_order"
internal const val FAST_SHOW_HOME_ADDITIONAL_INFO_KEY = "show_home_additional_info"
internal const val FAST_HOME_ADDITIONAL_INFO_CATEGORY_KEY = "home_additional_info_category"
internal const val FAST_STATISTICS_DOCK_ICON_ENABLED_KEY = "statistics_dock_icon_enabled"

internal object LegacySettingsKeys {
    val themeMode = stringPreferencesKey("theme_mode")
    val locale = stringPreferencesKey("locale")
    val autoReconnect = booleanPreferencesKey("auto_reconnect")
    val autoStartOnBoot = booleanPreferencesKey("auto_start_on_boot")
    val ipInfoEndpoint = stringPreferencesKey("ip_info_endpoint")
}

internal fun encodeFastDashboardCardOrder(order: List<DashboardCard>): String =
    normalizeFastDashboardCardOrder(order).joinToString(separator = ",") { card -> card.name }

internal fun parseFastDashboardCardOrder(value: String?): List<DashboardCard>? {
    val parsed =
        value
            ?.split(',')
            ?.mapNotNull { raw ->
                runCatching { DashboardCard.valueOf(raw.trim().uppercase()) }.getOrNull()
            }.orEmpty()
    return parsed
        .takeIf(List<DashboardCard>::isNotEmpty)
        ?.let(::normalizeFastDashboardCardOrder)
}

internal fun normalizeFastDashboardCardOrder(order: List<DashboardCard>): List<DashboardCard> {
    val withStatus =
        if (DashboardCard.STATUS in order) order else listOf(DashboardCard.STATUS) + order
    return (withStatus + DashboardCard.entries).distinct()
}

internal fun parseFastHomeAdditionalInfoCategory(value: String?): HomeAdditionalInfoCategory? =
    value
        ?.trim()
        ?.uppercase()
        ?.let { stored -> HomeAdditionalInfoCategory.entries.firstOrNull { it.name == stored } }

internal fun parseFastAccentColor(value: String?): AccentColor? =
    value
        ?.trim()
        ?.uppercase()
        ?.let { stored -> AccentColor.entries.firstOrNull { it.name == stored } }
