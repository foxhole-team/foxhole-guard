package com.foxhole.beta.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.foxhole.beta.core.model.DashboardCard

internal const val FAST_UI_PREFERENCES_NAME = "foxhole_fast_ui"
internal const val FAST_THEME_MODE_KEY = "theme_mode"
internal const val FAST_LOCALE_KEY = "locale"
internal const val FAST_NETWORK_CARD_ENABLED_KEY = "network_card_enabled"
internal const val FAST_TRAFFIC_CARD_ENABLED_KEY = "traffic_card_enabled"
internal const val FAST_TRAFFIC_MAP_ENABLED_KEY = "traffic_map_enabled"
internal const val FAST_SHOW_FIREWALL_STATUS_KEY = "show_firewall_status"
internal const val FAST_SHOW_TOR_QUICK_LAUNCH_KEY = "show_tor_quick_launch"
internal const val FAST_SMART_START_DASHBOARD_CONTROLS_ENABLED_KEY = "smart_start_dashboard_controls_enabled"
internal const val FAST_DASHBOARD_CARD_ORDER_KEY = "dashboard_card_order"

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

internal fun normalizeFastDashboardCardOrder(order: List<DashboardCard>): List<DashboardCard> =
    (order + DashboardCard.entries).distinct()
