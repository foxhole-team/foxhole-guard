package com.foxhole.guard.core.settings

import android.content.Context
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import com.foxhole.guard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SettingsRepositoryStorage(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val encryptedStore = SettingsEncryptedStore(appContext, ::defaultSettings)
    private val fastUiStore = SettingsFastUiStore(appContext)

    fun bootstrapInitialSettings(): Settings {
        val defaults = defaultSettings()
        return defaults.copy(
            ui =
            fastUiStore.readBootstrapDashboardUi(
                defaults = defaults.ui,
                encryptedUiFallback = { encryptedStore.readStoredUiOrDefault(defaults.ui) },
            ).copy(
                themeMode = fastUiStore.readThemeMode() ?: ThemeMode.SYSTEM,
                locale = fastUiStore.readLocale() ?: AppLocale.SYSTEM,
            ),
        )
    }

    suspend fun loadAndFinalizeInitialSettings(): Settings {
        val encryptedSettings = encryptedStore.loadInitialSettings()
        val fastThemeMode = fastUiStore.readThemeMode()
        val fastLocale = fastUiStore.readLocale()
        val effectiveSettings =
            fastThemeMode
                ?.takeIf { it != encryptedSettings.ui.themeMode && encryptedSettings.ui.themeMode != ThemeMode.SYSTEM }
                ?.let { themeMode -> encryptedSettings.copy(ui = encryptedSettings.ui.copy(themeMode = themeMode)).normalized() }
                ?: encryptedSettings
        if (fastThemeMode == null || encryptedSettings.ui.themeMode == ThemeMode.SYSTEM) {
            fastUiStore.writeThemeMode(effectiveSettings.ui.themeMode)
        }
        if (fastLocale == null) {
            fastUiStore.writeLocale(effectiveSettings.ui.locale)
        }
        fastUiStore.writeDashboardUi(effectiveSettings.ui)
        if (effectiveSettings != encryptedSettings) {
            withContext(Dispatchers.IO) {
                writeEncrypted(effectiveSettings)
            }
        }
        return effectiveSettings
    }

    fun defaultSettings(): Settings =
        Settings(
            connection = ConnectionSettings(ipInfoEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT),
        )

    fun writeEncrypted(value: Settings) {
        encryptedStore.write(value)
    }

    fun writeFastThemeMode(value: ThemeMode) {
        fastUiStore.writeThemeMode(value)
    }

    fun writeFastLocale(value: AppLocale) {
        fastUiStore.writeLocale(value)
    }

    fun writeFastUiSnapshot(value: UiSettings) {
        fastUiStore.writeUiSnapshot(value)
    }

    fun writeFastAppLockMode(value: AppLockMode) {
        fastUiStore.writeAppLockMode(value)
    }

    fun writeFastAppLockUi(value: AppLockSettings) {
        fastUiStore.writeAppLockUi(value)
    }
}
