package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import kotlinx.serialization.Serializable

@Serializable
internal data class LegacyFlatSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val locale: AppLocale = AppLocale.SYSTEM,
    val autoReconnect: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    val ipInfoEndpoint: String = "",
) {
    fun toCurrent(): Settings =
        Settings(
            ui =
            UiSettings(
                themeMode = themeMode,
                locale = locale,
                onboardingCompleted = true,
                showExpertSettings = true,
            ),
            connection =
            ConnectionSettings(
                autoReconnect = autoReconnect,
                autoStartOnBoot = autoStartOnBoot,
                ipInfoEndpoint = normalizeIpInfoEndpoint(ipInfoEndpoint),
            ),
        )
}
