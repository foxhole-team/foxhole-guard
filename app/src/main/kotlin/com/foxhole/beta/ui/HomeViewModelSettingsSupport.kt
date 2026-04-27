package com.foxhole.beta.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.applyAppLocale
import com.foxhole.beta.applySubscriptionRefreshSchedule
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.V2RayApiSettings
import kotlinx.coroutines.launch

internal fun HomeViewModel.profileInternal(profileId: Long): Profile? = uiState.value.profiles.firstOrNull { it.id == profileId }

internal fun HomeViewModel.refreshProfileInternal(profileId: Long) {
    viewModelScope.launch {
        runCatching { refreshProfileAndMaybeReconnect(profileId) }
            .onFailure { handleProfileRefreshFailure(profileId, it) }
    }
}

internal fun HomeViewModel.deleteProfileInternal(profileId: Long) {
    viewModelScope.launch {
        runCatching { container.profileRepository.deleteProfile(profileId) }
            .onSuccess {
                startupActiveProfileMutable.value = container.profileRepository.getActiveProfile()
                emitSuccess(getApplication<Application>().getString(R.string.profile_deleted))
            }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.profile_delete_failed)) }
    }
}

internal fun HomeViewModel.onThemeSelectedInternal(value: ThemeMode) {
    viewModelScope.launch {
        container.settingsRepository.updateThemeMode(value)
    }
}

internal fun HomeViewModel.onLocaleSelectedInternal(value: AppLocale) {
    viewModelScope.launch {
        container.settingsRepository.updateLocale(value)
        applyAppLocale(value)
    }
}

internal fun HomeViewModel.onSupportBotHandleChangedInternal(value: String?) {
    viewModelScope.launch {
        container.settingsRepository.updateSupportBotHandleOverride(value)
    }
}

internal fun HomeViewModel.onAutoReconnectChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoReconnect(value)
    }
}

internal fun HomeViewModel.onAutoStartChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoStartOnBoot(value)
    }
}

internal fun HomeViewModel.onAutoRefreshSubscriptionsChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoRefreshSubscriptions(value)
        getApplication<Application>().applySubscriptionRefreshSchedule(
            enabled = value,
            interval = container.settingsRepository.current().connection.subscriptionRefreshInterval,
        )
    }
}

internal fun HomeViewModel.onSubscriptionRefreshIntervalSelectedInternal(value: SubscriptionRefreshInterval) {
    viewModelScope.launch {
        container.settingsRepository.updateSubscriptionRefreshInterval(value)
        val enabled = container.settingsRepository.current().connection.autoRefreshSubscriptions
        getApplication<Application>().applySubscriptionRefreshSchedule(
            enabled = enabled,
            interval = value,
        )
    }
}

internal fun HomeViewModel.onIpInfoEndpointChangedInternal(value: String) {
    viewModelScope.launch {
        container.settingsRepository.updateIpInfoEndpoint(value)
    }
}

internal fun HomeViewModel.onLatencyProbeMethodSelectedInternal(value: LatencyProbeMethod) {
    viewModelScope.launch {
        container.settingsRepository.updateLatencyProbeMethod(value)
        scheduleActiveProfileLatencyRefresh()
    }
}

internal fun HomeViewModel.onTunStackSelectedInternal(value: TunStack) {
    viewModelScope.launch {
        container.settingsRepository.updateTunStack(value)
    }
}

internal fun HomeViewModel.onTrafficModeSelectedInternal(value: TrafficMode) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMode(value)
    }
}

internal fun HomeViewModel.onMtuChangedInternal(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMtu(value)
    }
}

internal fun HomeViewModel.onPreferIpv6ChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updatePreferIpv6(value)
    }
}

internal fun HomeViewModel.onDomainStrategySelectedInternal(value: DomainStrategy) {
    viewModelScope.launch {
        container.settingsRepository.updateDomainStrategy(value)
    }
}

internal fun HomeViewModel.acknowledgeUnsafeWarningInternal() {
    viewModelScope.launch {
        container.settingsRepository.acknowledgeUnsafeWarning()
    }
}

internal fun HomeViewModel.unlockExpertSettingsInternal() {
    viewModelScope.launch {
        container.settingsRepository.unlockExpertSettings()
    }
}

internal fun HomeViewModel.onShowExpertSettingsChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowExpertSettings(value)
    }
}

internal fun HomeViewModel.onBlockScreenshotsChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateBlockScreenshots(value)
    }
}

internal fun HomeViewModel.onNetworkActivityLoggingChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkActivityLogging(value)
        emitInfo(
            getApplication<Application>().getString(
                if (value) R.string.network_activity_logging_enabled else R.string.network_activity_logging_disabled,
            ),
        )
    }
}

internal fun HomeViewModel.onSmartStartReplayLoggingChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartReplayLogging(value)
    }
}

internal fun HomeViewModel.onDiagnosticsRetentionSelectedInternal(value: DiagnosticsRetention) {
    viewModelScope.launch {
        container.settingsRepository.updateDiagnosticsRetention(value)
    }
}

internal fun HomeViewModel.onAllowHttpConfigImportsChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAllowHttpConfigImports(value)
    }
}

internal fun HomeViewModel.onAllowInsecureTlsChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAllowInsecureTls(value)
    }
}

internal fun HomeViewModel.onSniffChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSniff(value)
    }
}

internal fun HomeViewModel.onRouteOnlyChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateRouteOnly(value)
    }
}

internal fun HomeViewModel.onStrictRouteChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateStrictRoute(value)
    }
}

internal fun HomeViewModel.onBypassLanChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateBypassLan(value)
    }
}

internal fun HomeViewModel.onAllowPrivateOutboundHostsChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAllowPrivateOutboundHosts(value)
    }
}

internal fun HomeViewModel.onPerAppRoutingModeSelectedInternal(value: PerAppRoutingMode) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePerAppRoutingMode(value)
    }
}

internal fun HomeViewModel.onSelectedPackagesChangedInternal(value: List<String>) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSelectedPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
    }
}

internal fun HomeViewModel.onSocksSurfaceChangedInternal(value: ProxyInboundSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSocksSurface(value)
    }
}

internal fun HomeViewModel.onHttpSurfaceChangedInternal(value: ProxyInboundSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateHttpSurface(value)
    }
}

internal fun HomeViewModel.onMixedSurfaceChangedInternal(value: ProxyInboundSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateMixedSurface(value)
    }
}

internal fun HomeViewModel.onLocalProxyAuthEnabledChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLocalProxyAuthEnabled(value)
    }
}

internal fun HomeViewModel.onLocalProxyAuthChangedInternal(value: LocalAuthSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLocalProxyAuth(value)
    }
}

internal fun HomeViewModel.onLocalProxyLanAccessChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLocalProxyLanAccessEnabled(value)
    }
}

internal fun HomeViewModel.onClashApiChangedInternal(value: ClashApiSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateClashApi(value)
    }
}

internal fun HomeViewModel.onV2RayApiChangedInternal(value: V2RayApiSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateV2RayApi(value)
    }
}

internal fun HomeViewModel.resetExpertToSafeDefaultsInternal() {
    viewModelScope.launch {
        container.settingsRepository.resetExpertToSafeDefaults()
        container.routingRepository.setActivePreset(null)
    }
}

internal fun HomeViewModel.resetUsageTrackingInternal() {
    viewModelScope.launch {
        container.settingsRepository.resetUsageTracking()
    }
}

internal fun HomeViewModel.createPresetInternal(name: String) {
    viewModelScope.launch {
        runCatching { container.routingRepository.createPreset(name = name, activate = uiState.value.presets.isEmpty()) }
            .onSuccess { maybeReloadActiveRuntime() }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_preset_create_failed)) }
    }
}

internal fun HomeViewModel.updatePresetInternal(
    presetId: Long,
    name: String,
    overrideMode: RoutingPresetOverrideMode,
    enabled: Boolean,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.updatePreset(presetId, name, overrideMode, enabled) }
            .onSuccess { maybeReloadActiveRuntime() }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_preset_update_failed)) }
    }
}

internal fun HomeViewModel.setActivePresetInternal(presetId: Long?) {
    viewModelScope.launch {
        container.routingRepository.setActivePreset(presetId)
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.deletePresetInternal(presetId: Long) {
    viewModelScope.launch {
        container.routingRepository.deletePreset(presetId)
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.saveRuleInternal(
    presetId: Long,
    ruleId: Long?,
    name: String,
    enabled: Boolean,
    order: Int?,
    action: RoutingRuleAction,
    matchDomains: List<String>,
    matchIpCidrs: List<String>,
    matchPorts: List<String>,
    matchProtocols: List<String>,
    matchNetworks: List<String>,
) {
    viewModelScope.launch {
        runCatching {
            container.routingRepository.upsertRule(
                presetId = presetId,
                ruleId = ruleId,
                name = name,
                enabled = enabled,
                order = order,
                action = action,
                matchDomains = matchDomains,
                matchIpCidrs = matchIpCidrs,
                matchPorts = matchPorts,
                matchProtocols = matchProtocols,
                matchNetworks = matchNetworks,
            )
        }.onSuccess {
            maybeReloadActiveRuntime()
        }.onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_rule_save_failed)) }
    }
}

internal fun HomeViewModel.deleteRuleInternal(ruleId: Long) {
    viewModelScope.launch {
        container.routingRepository.deleteRule(ruleId)
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.addCatalogInternal(
    name: String,
    url: String,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.addCatalog(name, url) }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_catalog_add_failed)) }
    }
}

internal fun HomeViewModel.refreshCatalogInternal(catalogId: Long) {
    viewModelScope.launch {
        runCatching { container.routingRepository.refreshCatalog(catalogId) }
            .onSuccess { loadCatalogPreview(catalogId) }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_catalog_refresh_failed)) }
    }
}

internal fun HomeViewModel.deleteCatalogInternal(catalogId: Long) {
    viewModelScope.launch {
        container.routingRepository.deleteCatalog(catalogId)
        catalogPresetPreviewsMutable.value = catalogPresetPreviewsMutable.value - catalogId
    }
}

internal fun HomeViewModel.importPresetFromCatalogInternal(
    catalogId: Long,
    presetId: String,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.importPresetFromCatalog(catalogId, presetId) }
            .onSuccess { emitSuccess(getApplication<Application>().getString(R.string.routing_preset_imported)) }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_catalog_import_failed)) }
    }
}

internal fun HomeViewModel.loadCatalogPreviewInternal(catalogId: Long) {
    viewModelScope.launch {
        runCatching { container.routingRepository.previewCatalogPresets(catalogId) }
            .onSuccess { previews ->
                catalogPresetPreviewsMutable.value = catalogPresetPreviewsMutable.value + (catalogId to previews)
            }.onFailure {
                emitError(it.message ?: getApplication<Application>().getString(R.string.routing_catalog_preview_failed))
            }
    }
}

internal suspend fun HomeViewModel.exportPresetDocumentInternal(presetId: Long): String = container.routingRepository.exportPresetDocument(presetId)
