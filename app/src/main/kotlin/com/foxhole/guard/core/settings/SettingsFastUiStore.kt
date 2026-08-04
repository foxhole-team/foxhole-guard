package com.foxhole.guard.core.settings

import android.content.Context
import androidx.core.content.edit
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TrafficCardView
import com.foxhole.core.model.UiSettings

internal class SettingsFastUiStore(
    context: Context,
) {
    private val fastUiPreferences =
        context.applicationContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readThemeMode(): ThemeMode? =
        parseOptionalStoredThemeMode(fastUiPreferences.getString(FAST_THEME_MODE_KEY, null))

    fun readLocale(): AppLocale? =
        parseOptionalStoredAppLocale(fastUiPreferences.getString(FAST_LOCALE_KEY, null))

    fun readBootstrapDashboardUi(
        defaults: UiSettings,
        encryptedUiFallback: () -> UiSettings,
    ): UiSettings {
        val storedUi =
            if (hasDashboardUi()) {
                defaults
            } else {
                encryptedUiFallback()
            }
        return readDashboardUi(storedUi)
    }

    fun readAppLockMode(): AppLockMode? =
        parseOptionalStoredAppLockMode(fastUiPreferences.getString(FAST_APP_LOCK_MODE_KEY, null))

    fun writeAppLockMode(value: AppLockMode) {
        fastUiPreferences.edit {
            putString(FAST_APP_LOCK_MODE_KEY, value.name)
        }
    }

    /** The unlock screen renders before hydration: mirror its style flags too. */
    fun writeAppLockUi(value: AppLockSettings) {
        fastUiPreferences.edit {
            putString(FAST_APP_LOCK_MODE_KEY, value.mode.name)
            putBoolean(FAST_APP_LOCK_BUILTIN_PAD_KEY, value.builtInPinPadEnabled)
            putBoolean(FAST_APP_LOCK_SCRAMBLE_KEY, value.scrambleKeypadDigits)
        }
    }

    fun writeThemeMode(value: ThemeMode) {
        fastUiPreferences.edit {
            putString(FAST_THEME_MODE_KEY, value.name)
        }
    }

    fun writeLocale(value: AppLocale) {
        fastUiPreferences.edit {
            putString(FAST_LOCALE_KEY, value.name)
        }
    }

    fun writeUiSnapshot(value: UiSettings) {
        fastUiPreferences.edit {
            putString(FAST_THEME_MODE_KEY, value.themeMode.name)
            putString(FAST_LOCALE_KEY, value.locale.name)
            putBoolean(FAST_NETWORK_CARD_ENABLED_KEY, value.networkCardEnabled)
            putBoolean(FAST_TRAFFIC_CARD_ENABLED_KEY, value.trafficCardEnabled)
            putString(FAST_TRAFFIC_CARD_VIEW_KEY, value.trafficCardView.name)
            putBoolean(FAST_NETWORK_DNS_INFO_ENABLED_KEY, value.networkDnsInfoEnabled)
            putBoolean(FAST_TRAFFIC_MAP_ENABLED_KEY, value.trafficMapEnabled)
            putBoolean(FAST_SHOW_FIREWALL_STATUS_KEY, value.showFirewallStatus)
            putBoolean(FAST_SHOW_TOR_QUICK_LAUNCH_KEY, value.showTorQuickLaunch)
            putBoolean(FAST_SHOW_QUICK_ACCESS_PANEL_KEY, value.showQuickAccessPanel)
            putBoolean(FAST_MAP_ON_RIGHT_KEY, value.mapWidgetMapOnRight)
            putString(FAST_DASHBOARD_CARD_ORDER_KEY, encodeFastDashboardCardOrder(value.dashboardCardOrder))
        }
    }

    fun writeDashboardUi(value: UiSettings) {
        fastUiPreferences.edit {
            putBoolean(FAST_NETWORK_CARD_ENABLED_KEY, value.networkCardEnabled)
            putBoolean(FAST_TRAFFIC_CARD_ENABLED_KEY, value.trafficCardEnabled)
            putString(FAST_TRAFFIC_CARD_VIEW_KEY, value.trafficCardView.name)
            putBoolean(FAST_NETWORK_DNS_INFO_ENABLED_KEY, value.networkDnsInfoEnabled)
            putBoolean(FAST_TRAFFIC_MAP_ENABLED_KEY, value.trafficMapEnabled)
            putBoolean(FAST_SHOW_FIREWALL_STATUS_KEY, value.showFirewallStatus)
            putBoolean(FAST_SHOW_TOR_QUICK_LAUNCH_KEY, value.showTorQuickLaunch)
            putBoolean(FAST_SHOW_QUICK_ACCESS_PANEL_KEY, value.showQuickAccessPanel)
            putBoolean(FAST_MAP_ON_RIGHT_KEY, value.mapWidgetMapOnRight)
            putString(FAST_DASHBOARD_CARD_ORDER_KEY, encodeFastDashboardCardOrder(value.dashboardCardOrder))
        }
    }

    private fun hasDashboardUi(): Boolean =
        fastUiPreferences.contains(FAST_DASHBOARD_CARD_ORDER_KEY) ||
            fastUiPreferences.contains(FAST_NETWORK_CARD_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_TRAFFIC_CARD_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_TRAFFIC_CARD_VIEW_KEY) ||
            fastUiPreferences.contains(FAST_NETWORK_DNS_INFO_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_TRAFFIC_MAP_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_SHOW_FIREWALL_STATUS_KEY) ||
            fastUiPreferences.contains(FAST_SHOW_TOR_QUICK_LAUNCH_KEY) ||
            fastUiPreferences.contains(FAST_SHOW_QUICK_ACCESS_PANEL_KEY) ||
            fastUiPreferences.contains(FAST_MAP_ON_RIGHT_KEY)

    private fun readDashboardUi(fallback: UiSettings): UiSettings =
        fallback.copy(
            networkCardEnabled =
            fastUiPreferences.getBoolean(
                FAST_NETWORK_CARD_ENABLED_KEY,
                fallback.networkCardEnabled,
            ),
            trafficCardEnabled =
            fastUiPreferences.getBoolean(
                FAST_TRAFFIC_CARD_ENABLED_KEY,
                fallback.trafficCardEnabled,
            ),
            trafficCardView =
            parseOptionalStoredTrafficCardView(
                fastUiPreferences.getString(FAST_TRAFFIC_CARD_VIEW_KEY, null),
            ) ?: fallback.trafficCardView,
            networkDnsInfoEnabled =
            fastUiPreferences.getBoolean(
                FAST_NETWORK_DNS_INFO_ENABLED_KEY,
                fallback.networkDnsInfoEnabled,
            ),
            trafficMapEnabled =
            fastUiPreferences.getBoolean(
                FAST_TRAFFIC_MAP_ENABLED_KEY,
                fallback.trafficMapEnabled,
            ),
            showFirewallStatus =
            fastUiPreferences.getBoolean(
                FAST_SHOW_FIREWALL_STATUS_KEY,
                fallback.showFirewallStatus,
            ),
            showTorQuickLaunch =
            fastUiPreferences.getBoolean(
                FAST_SHOW_TOR_QUICK_LAUNCH_KEY,
                fallback.showTorQuickLaunch,
            ),
            showQuickAccessPanel =
            fastUiPreferences.getBoolean(
                FAST_SHOW_QUICK_ACCESS_PANEL_KEY,
                fallback.showQuickAccessPanel,
            ),
            mapWidgetMapOnRight =
            fastUiPreferences.getBoolean(
                FAST_MAP_ON_RIGHT_KEY,
                fallback.mapWidgetMapOnRight,
            ),
            dashboardCardOrder =
            parseFastDashboardCardOrder(fastUiPreferences.getString(FAST_DASHBOARD_CARD_ORDER_KEY, null))
                ?: fallback.dashboardCardOrder,
        )
}

private fun parseOptionalStoredTrafficCardView(value: String?): TrafficCardView? =
    value?.let { stored -> TrafficCardView.entries.firstOrNull { it.name == stored } }
