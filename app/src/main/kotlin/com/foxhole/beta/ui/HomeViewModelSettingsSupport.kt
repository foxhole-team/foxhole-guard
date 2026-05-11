package com.foxhole.beta.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.applyAppLocale
import com.foxhole.beta.applySubscriptionRefreshSchedule
import com.foxhole.beta.core.model.AnomalyHistoryRetention
import com.foxhole.beta.core.model.AnomalySensitivity
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.NetworkRulesSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.V2RayApiSettings
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

internal fun HomeViewModel.profileInternal(profileId: Long): Profile? = uiState.value.profiles.firstOrNull { it.id == profileId }

internal fun HomeViewModel.refreshProfileInternal(profileId: Long) {
    viewModelScope.launch {
        refreshProfileWithInsecureTlsDecision(
            profileId = profileId,
            allowInsecureTlsForProfile = false,
            excludeInsecureTlsOptions = false,
        )
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

internal fun HomeViewModel.onSmartStartProtocolSelectionTimeoutChangedInternal(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartProtocolSelectionTimeoutSeconds(value)
    }
}

internal fun HomeViewModel.onSmartStartRefreshSelectionTimeoutChangedInternal(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartRefreshSelectionTimeoutSeconds(value)
    }
}

internal fun HomeViewModel.onSmartStartTransportPrioritySelectedInternal(value: SmartStartTransportPriority) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartTransportPriority(value)
    }
}

internal fun HomeViewModel.onSmartStartV2RayTunSubscriptionsEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartV2RayTunSubscriptionsEnabled(value)
    }
}

internal fun HomeViewModel.onSmartStartFailoverEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartFailoverEnabled(value)
    }
}

internal fun HomeViewModel.onSmartStartSubscriptionRetryAttemptsChangedInternal(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartSubscriptionRetryAttempts(value)
    }
}

internal fun HomeViewModel.onSmartStartSubscriptionRetryDelaySecondsChangedInternal(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateSmartStartSubscriptionRetryDelaySeconds(value)
    }
}

internal fun HomeViewModel.clearSmartStartDataInternal() {
    viewModelScope.launch {
        cancelAutoConnect(clearUiOnly = true)
        cancelSmartProfileMetricsRefreshInternal(restoreConnection = true)
        clearProtocolLatencyState()
        recommendedProtocolMutable.value = null
        protocolMetricsRefreshingProfileIdsMutable.value = emptySet()
        protocolMetricsRefreshingOptionIdByProfileIdMutable.value = emptyMap()
        container.settingsRepository.clearSmartStartData()
        emitSuccess(getApplication<Application>().getString(R.string.smart_start_data_cleared))
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
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateDomainStrategy(value)
    }
}

internal fun HomeViewModel.onDnsSettingsChangedInternal(value: DnsSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateDnsSettings(value)
    }
}

internal fun HomeViewModel.onDnsBypassPackagesChangedInternal(value: List<String>) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateDnsBypassPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
    }
}

internal fun HomeViewModel.onDnsDomainBypassRulesChangedInternal(value: List<String>) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateDnsDomainBypassRules(value)
    }
}

internal fun HomeViewModel.onDnsFilterManualRefreshInternal() {
    viewModelScope.launch {
        runCatching { container.profileRepository.verifyBundledDnsFilters() }
            .onSuccess {
                container.settingsRepository.markDnsFiltersUpdated()
                emitSuccess(getApplication<Application>().getString(R.string.dns_filter_refresh_complete))
            }
            .onFailure { error ->
                container.diagnosticsLogger.record(
                    "dns",
                    "bundled filter verification failed error=${error.javaClass.simpleName}",
                )
                emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
            }
    }
}

internal fun HomeViewModel.onNetworkRulesChangedInternal(value: NetworkRulesSettings) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkRulesSettings(value)
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

internal fun HomeViewModel.onTrafficMapEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMapEnabled(value)
    }
}

internal fun HomeViewModel.onNetworkCardEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkCardEnabled(value)
    }
}

internal fun HomeViewModel.onTrafficCardEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficCardEnabled(value)
    }
}

internal fun HomeViewModel.onShowTorQuickLaunchChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowTorQuickLaunch(value)
    }
}

internal fun HomeViewModel.onShowFirewallStatusChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowFirewallStatus(value)
    }
}

internal fun HomeViewModel.onDashboardCardOrderChangedInternal(value: List<com.foxhole.beta.core.model.DashboardCard>) {
    viewModelScope.launch {
        container.settingsRepository.updateDashboardCardOrder(value)
    }
}

internal fun HomeViewModel.onKillSwitchChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateKillSwitchEnabled(value)
        container.connectionController.syncLocalGuard()
        if (value) {
            openSystemVpnSettings()
        }
    }
}

internal fun HomeViewModel.onFirewallEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateFirewallEnabled(value)
        container.settingsRepository.updateShowFirewallStatus(value)
        if (value && android.net.VpnService.prepare(getApplication<Application>()) != null) {
            pendingConnectRequest =
                PendingConnectRequest(
                    action = PendingConnectAction.LOCAL_GUARD,
                )
            requestVpnPermission.tryEmit(Unit)
            return@launch
        }
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onNetworkActivityLoggingChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkActivityLogging(value)
        if (!value) {
            container.settingsRepository.updateNetworkActivityPersistentLogging(false)
        }
        container.connectionController.syncLocalGuard()
        emitInfo(
            getApplication<Application>().getString(
                if (value) R.string.network_activity_logging_enabled else R.string.network_activity_logging_disabled,
            ),
        )
    }
}

internal fun HomeViewModel.onNetworkActivityPersistentLoggingChangedInternal(value: Boolean) {
    viewModelScope.launch {
        if (value) {
            container.settingsRepository.updateFirewallEnabled(true)
            container.settingsRepository.updateNetworkActivityLogging(true)
        }
        container.settingsRepository.updateNetworkActivityPersistentLogging(value)
        container.connectionController.syncLocalGuard()
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

internal fun HomeViewModel.onNotifyUnusualTrafficChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNotifyUnusualTraffic(value)
    }
}

internal fun HomeViewModel.onAnomalyEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalyEnabled(value)
    }
}

internal fun HomeViewModel.onAnomalySensitivitySelectedInternal(value: AnomalySensitivity) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalySensitivity(value)
    }
}

internal fun HomeViewModel.onAnalyzeBackgroundTrafficChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAnalyzeBackgroundTraffic(value)
    }
}

internal fun HomeViewModel.onAnalyzeDestinationCountriesChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAnalyzeDestinationCountries(value)
    }
}

internal fun HomeViewModel.onAnomalyHistoryRetentionSelectedInternal(value: AnomalyHistoryRetention) {
    viewModelScope.launch {
        container.settingsRepository.updateAnomalyHistoryRetention(value)
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
    updateAppRoutingSettingAndPromptReconnect {
        container.settingsRepository.updatePerAppRoutingMode(value)
    }
}

internal fun HomeViewModel.onSelectedPackagesChangedInternal(value: List<String>) {
    updateAppRoutingSettingAndPromptReconnect {
        val selectedPackages = value.filterNot { it == getApplication<Application>().packageName }
        container.settingsRepository.updateSelectedPackages(selectedPackages)
        if (selectedPackages.isEmpty()) {
            container.settingsRepository.updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
        }
    }
}

internal fun HomeViewModel.onPrivacyRouteModeSelectedInternal(value: PrivacyRouteMode) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteMode(value)
        if (value == PrivacyRouteMode.TOR_OVER_VPN) {
            val settings = container.settingsRepository.settings.value
            if (
                settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS &&
                settings.privacyRoute.selectedPackages.isEmpty()
            ) {
                container.settingsRepository.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
            }
        }
    }
}

internal fun HomeViewModel.onPrivacyRouteScopeSelectedInternal(value: PrivacyRouteScope) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteScope(value)
    }
}

internal fun HomeViewModel.onPrivacyRouteSelectedPackagesChangedInternal(value: List<String>) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteSelectedPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
    }
}

internal fun HomeViewModel.onBlockedPackagesChangedInternal(value: List<String>) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateBlockedPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onBlockedPackagesEnabledChangedInternal(value: Boolean) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateBlockedPackagesEnabled(value)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onBlockAppsAlwaysChangedInternal(value: Boolean) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        if (value) {
            container.settingsRepository.updateFirewallEnabled(true)
        }
        container.settingsRepository.updateBlockAppsAlways(value)
        container.connectionController.syncLocalGuard()
    }
}

private fun HomeViewModel.updateAppRoutingSettingAndPromptReconnect(
    requiresRuntimeWhenFull: Boolean = false,
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val previousMode = uiState.value.settings.expert.perAppRoutingMode
        val activeRuntime =
            uiState.value.activeProfile != null &&
                container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES
        val reconnectAlreadyRequired = runtimeReconnectRequiredMutable.value
        updateAction()
        val updatedMode = container.settingsRepository.current().expert.perAppRoutingMode
        val splitRulesAffectRuntime =
            requiresRuntimeWhenFull ||
                previousMode != PerAppRoutingMode.FULL_TUNNEL ||
                updatedMode != PerAppRoutingMode.FULL_TUNNEL
        if (!activeRuntime || !splitRulesAffectRuntime) {
            clearRuntimeReconnectRequired()
            return@launch
        }
        val appliedFingerprint = container.connectionController.appliedRuntimeSignature.value
        val currentFingerprint = container.connectionController.currentRuntimeFingerprint()
        if (appliedFingerprint != currentFingerprint) {
            markRuntimeReconnectRequired()
            if (!reconnectAlreadyRequired) {
                snackbars.emit(infoBanner(R.string.split_tunnel_reconnect_required))
            }
        } else {
            clearRuntimeReconnectRequired()
        }
    }
}

internal fun HomeViewModel.onSiteRoutingActionSelectedInternal(value: RoutingRuleAction) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateSiteRoutingAction(value)
    }
}

internal fun HomeViewModel.onProxySurfaceModeSelectedInternal(value: ProxySurfaceMode) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateProxySurfaceMode(value)
    }
}

internal fun HomeViewModel.onLanProxySurfaceModeSelectedInternal(value: ProxySurfaceMode) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLanProxySurfaceMode(value)
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

internal fun HomeViewModel.onLanProxyAuthEnabledChangedInternal(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLanProxyAuthEnabled(value)
    }
}

internal fun HomeViewModel.onLanProxyAuthChangedInternal(value: LocalAuthSettings) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateLanProxyAuth(value)
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

internal fun HomeViewModel.resetExperimentalSettingsToDefaultsInternal() {
    viewModelScope.launch {
        container.settingsRepository.resetExperimentalSettingsToDefaults()
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.resetApplicationSettingsToDefaultsInternal() {
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

private suspend fun HomeViewModel.openSystemVpnSettings() {
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
