package com.foxhole.guard.ui
import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ClashApiSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.V2RayApiSettings
import com.foxhole.guard.R
import com.foxhole.guard.applyAppLocale
import com.foxhole.guard.core.settings.resetApplicationSettingsToDefaults
import com.foxhole.guard.core.settings.resetExperimentalSettingsToDefaults
import com.foxhole.guard.core.settings.resetExpertToSafeDefaults
import com.foxhole.guard.core.settings.resetUsageTracking
import com.foxhole.guard.core.settings.updateClashApi
import com.foxhole.guard.core.settings.updateHttpSurface
import com.foxhole.guard.core.settings.updateLanProxyAuth
import com.foxhole.guard.core.settings.updateLanProxySurfaceMode
import com.foxhole.guard.core.settings.updateLocalProxyAuth
import com.foxhole.guard.core.settings.updateLocalProxyAuthEnabled
import com.foxhole.guard.core.settings.updateLocalProxyLanAccessEnabled
import com.foxhole.guard.core.settings.updateMixedSurface
import com.foxhole.guard.core.settings.updateProxySurfaceMode
import com.foxhole.guard.core.settings.updateShowLanProxyQuickAccess
import com.foxhole.guard.core.settings.updateSiteRoutingAction
import com.foxhole.guard.core.settings.updateSocksSurface
import com.foxhole.guard.core.settings.updateV2RayApi
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

// Proxy surface, local auth, reset-to-defaults and local-data clearing handlers for HomeViewModel.
// Extracted from HomeViewModelSettingsSupport (file split by domain); extension functions only.

internal fun HomeViewModel.onSiteRoutingActionSelected(value: RoutingRuleAction) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSiteRoutingAction(value)
    }
}

internal fun HomeViewModel.onProxySurfaceModeSelected(value: ProxySurfaceMode) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateProxySurfaceMode(value)
    }
}

internal fun HomeViewModel.onCopyLanProxyPassword() {
    val app = getApplication<Application>()
    val password = container.settingsRepository.settings.value.expert.localSurfaces.lanAuth.password
    if (password.isBlank()) {
        return
    }
    val clipboard = app.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip =
        android.content.ClipData.newPlainText("FoxHole LAN proxy", password).apply {
            // Marks the clip sensitive so the system clipboard preview masks the password
            // (honored from API 33; a harmless extra below).
            description.extras =
                android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
        }
    clipboard.setPrimaryClip(clip)
    viewModelScope.launch { emitInfo(app.getString(R.string.lan_proxy_password_copied)) }
}

internal fun HomeViewModel.onLanProxySurfaceModeSelected(value: ProxySurfaceMode) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLanProxySurfaceMode(value)
    }
}

internal fun HomeViewModel.onSocksSurfaceChanged(value: ProxyInboundSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSocksSurface(value)
    }
}

internal fun HomeViewModel.onHttpSurfaceChanged(value: ProxyInboundSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateHttpSurface(value)
    }
}

internal fun HomeViewModel.onMixedSurfaceChanged(value: ProxyInboundSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateMixedSurface(value)
    }
}

internal fun HomeViewModel.onLocalProxyAuthEnabledChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLocalProxyAuthEnabled(value)
    }
}

internal fun HomeViewModel.onLocalProxyAuthChanged(value: LocalAuthSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLocalProxyAuth(value)
    }
}

internal fun HomeViewModel.onLanProxyAuthChanged(value: LocalAuthSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLanProxyAuth(value)
    }
}

internal fun HomeViewModel.onLocalProxyLanAccessChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLocalProxyLanAccessEnabled(value)
    }
}

internal fun HomeViewModel.onShowLanProxyQuickAccessChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowLanProxyQuickAccess(value)
    }
}

internal fun HomeViewModel.onClashApiChanged(value: ClashApiSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateClashApi(value)
    }
}

internal fun HomeViewModel.onV2RayApiChanged(value: V2RayApiSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateV2RayApi(value)
    }
}

internal fun HomeViewModel.resetExpertToSafeDefaults() {
    viewModelScope.launch {
        container.settingsRepository.resetExpertToSafeDefaults()
        container.routingRepository.setActivePreset(null)
    }
}

internal fun HomeViewModel.resetExperimentalSettingsToDefaults() {
    viewModelScope.launch {
        container.settingsRepository.resetExperimentalSettingsToDefaults()
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.resetApplicationSettingsToDefaults() {
    viewModelScope.launch {
        container.settingsRepository.resetApplicationSettingsToDefaults()
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.resetUsageTrackingInternal() {
    viewModelScope.launch {
        container.settingsRepository.resetUsageTracking()
        container.anomalyRepository.clearTrafficStatistics()
    }
}

internal fun HomeViewModel.clearDiagnosticsLocalDataInternal() {
    viewModelScope.launch {
        runCatching {
            journalHistoryCleared("diagnostics")
            container.localDataRepository.clearDiagnostics()
        }.onSuccess {
            emitSuccess(getApplication<Application>().getString(R.string.privacy_local_data_clear_diagnostics_success))
        }.onFailure {
            emitError(getApplication<Application>().getString(R.string.privacy_local_data_clear_failed))
        }
    }
}

internal fun HomeViewModel.clearNetworkActivityLocalDataInternal() {
    viewModelScope.launch {
        runCatching {
            journalHistoryCleared("network-activity")
            container.localDataRepository.clearNetworkActivity()
        }.onSuccess {
            emitSuccess(
                getApplication<Application>().getString(
                    R.string.privacy_local_data_clear_network_activity_success,
                ),
            )
        }.onFailure {
            emitError(getApplication<Application>().getString(R.string.privacy_local_data_clear_failed))
        }
    }
}

internal fun HomeViewModel.clearRuntimeLogTagInternal(
    tag: String,
    journalKey: String,
) {
    viewModelScope.launch {
        runCatching {
            journalHistoryCleared(journalKey)
            container.diagnosticsLogger.clearTag(tag)
        }.onSuccess {
            emitSuccess(getApplication<Application>().getString(R.string.privacy_local_data_clear_diagnostics_success))
        }.onFailure {
            emitError(getApplication<Application>().getString(R.string.privacy_local_data_clear_failed))
        }
    }
}

internal fun HomeViewModel.clearAppTrafficLocalData() {
    viewModelScope.launch {
        runCatching {
            journalHistoryCleared("app-traffic")
            container.localDataRepository.clearAppTrafficStats()
            syncAppTrafficStatsSampler(false)
        }.onSuccess {
            emitSuccess(getApplication<Application>().getString(R.string.privacy_local_data_clear_app_traffic_success))
        }.onFailure {
            emitError(getApplication<Application>().getString(R.string.privacy_local_data_clear_failed))
        }
    }
}

internal fun HomeViewModel.clearProfilesAndSecretsLocalData() {
    viewModelScope.launch {
        runCatching {
            journalHistoryCleared("profiles-secrets")
            container.localDataRepository.clearProfilesAndSecrets()
            startupActiveProfileMutable.value = null
        }.onSuccess {
            emitSuccess(getApplication<Application>().getString(R.string.privacy_local_data_clear_profiles_success))
        }.onFailure {
            emitError(getApplication<Application>().getString(R.string.privacy_local_data_clear_failed))
        }
    }
}

internal fun HomeViewModel.factoryResetLocalData() {
    viewModelScope.launch {
        runCatching {
            cancelAutoConnect(clearUiOnly = true)
            container.connectionController.disconnect(suppressLocalGuard = true, userInitiated = true)
            container.localDataRepository.factoryReset()
            securityComponents.resetAfterFactoryReset()
            syncGuardMonitoringLifecycle()
            syncAppTrafficStatsSampler(false)
            startupActiveProfileMutable.value = null
            applyAppLocale(container.settingsRepository.settings.value.ui.locale)
        }.onSuccess {
            emitSuccess(getApplication<Application>().getString(R.string.privacy_local_data_factory_reset_success))
        }.onFailure {
            emitError(getApplication<Application>().getString(R.string.privacy_local_data_clear_failed))
        }
    }
}

internal suspend fun HomeViewModel.openSystemVpnSettingsInternal() {
    val app = getApplication<Application>()
    val vpnSettingsIntent =
        Intent(AndroidSettings.ACTION_VPN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        app.startActivity(vpnSettingsIntent)
    }.onFailure {
        emitError(app.getString(R.string.vpn_settings_unavailable))
    }
}

private fun HomeViewModel.journalHistoryCleared(scope: String) {
    if (!securityComponents.isPasswordProtectionActive()) {
        return
    }
    // Record the clear in the tamper-evident guard journal BEFORE deleting, so a coerced
    // wipe leaves a sealed trace the deletion itself cannot remove.
    securityComponents.journalEvent(
        com.foxhole.guard.guardian.GuardEvent(
            type = com.foxhole.guard.guardian.GuardEventType.HISTORY_CLEARED,
            detail = scope,
        ),
    )
}
