package com.foxhole.guard.core.settings

import android.content.Context
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.UiSettings
import com.foxhole.guard.BuildConfig

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
            fastUiStore.readBootstrapUi(
                defaults = defaults.ui,
                encryptedUiFallback = { encryptedStore.readStoredUiOrDefault(defaults.ui) },
            ),
        )
    }

    suspend fun loadAndFinalizeInitialSettings(): Settings {
        val encryptedSettings = encryptedStore.loadInitialSettings()

        fastUiStore.writeUiSnapshot(encryptedSettings.ui)
        return encryptedSettings
    }

    fun defaultSettings(): Settings =
        newInstallationSettings(BuildConfig.DEFAULT_IP_INFO_ENDPOINT)

    fun writeEncrypted(value: Settings) {
        encryptedStore.write(value)
    }

    fun readAcceptedLicenseAgreementVersion(): Int =
        encryptedStore.readStoredUiOrDefault(UiSettings()).acceptedLicenseAgreementVersion

    fun writeFastLocale(value: AppLocale) {
        fastUiStore.writeLocale(value)
    }

    fun readRouterHomeShortcutVisible(): Boolean = fastUiStore.readRouterHomeShortcutVisible()

    fun writeRouterHomeShortcutVisible(value: Boolean) {
        fastUiStore.writeRouterHomeShortcutVisible(value)
    }

    fun readRouterDockShortcutVisible(): Boolean = fastUiStore.readRouterDockShortcutVisible()

    fun readDockOptionalOrder(): List<String> = fastUiStore.readDockOptionalOrder()

    fun writeDockOptionalOrder(value: List<String>) = fastUiStore.writeDockOptionalOrder(value)

    fun writeRouterDockShortcutVisible(value: Boolean) {
        fastUiStore.writeRouterDockShortcutVisible(value)
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
