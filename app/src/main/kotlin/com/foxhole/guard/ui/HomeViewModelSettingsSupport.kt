package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.DomainStrategy
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.Profile
import com.foxhole.core.model.SubscriptionRefreshInterval
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TunStack
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.R
import com.foxhole.guard.applyAppLocale
import com.foxhole.guard.applyDnsFilterUpdateSchedule
import com.foxhole.guard.applyGeoIpUpdateSchedule
import com.foxhole.guard.applySubscriptionRefreshSchedule
import com.foxhole.guard.core.settings.markDnsFiltersUpdated
import com.foxhole.guard.core.settings.updateDnsBypassPackages
import com.foxhole.guard.core.settings.updateDnsDomainBypassRules
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateDomainStrategy
import com.foxhole.guard.core.settings.updateGeoIpAutoUpdate
import com.foxhole.guard.core.settings.updatePreferIpv6
import com.foxhole.guard.core.settings.updateTrafficMode
import com.foxhole.guard.core.settings.updateTrafficMtu
import com.foxhole.guard.core.settings.updateTunStack
import com.foxhole.guard.refreshLocalizedNotificationChannels
import com.foxhole.guard.runtime.DnsFilterUpdateAvailability
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun HomeViewModel.profile(profileId: Long): Profile? = controlUiState.value.profiles.firstOrNull {
    it.id == profileId
}

internal fun HomeViewModel.refreshProfile(profileId: Long) {
    viewModelScope.launch {
        refreshProfileWithInsecureTlsDecision(
            profileId = profileId,
            allowInsecureTlsForProfile = false,
            excludeInsecureTlsOptions = false,
        )
    }
}

internal fun HomeViewModel.deleteProfile(profileId: Long) {
    viewModelScope.launch {
        runCatching { container.profileRepository.deleteProfile(profileId) }
            .onSuccess {
                startupActiveProfileMutable.value = container.profileRepository.getActiveProfile()
                emitSuccess(getApplication<Application>().getString(R.string.profile_deleted))
            }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.profile_delete_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.setSmartProfileProtocolEnabled(
    profileId: Long,
    optionId: String,
    enabled: Boolean,
) {
    viewModelScope.launch {
        runCatching {
            container.profileRepository.setProfileProtocolOptionEnabled(profileId, optionId, enabled)
        }.onSuccess { updated ->
            if (updated.isActive) {
                startupActiveProfileMutable.value = updated
            }
        }.onFailure {
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    it,
                    R.string.profile_update_failed,
                ),
            )
        }
    }
}

internal fun HomeViewModel.renameProfile(
    profileId: Long,
    name: String,
) {
    val trimmed = name.trim()
    if (trimmed.isBlank()) {
        return
    }
    viewModelScope.launch {
        runCatching { container.profileRepository.renameProfile(profileId, trimmed) }
            .onSuccess { renamed ->
                if (renamed.isActive) {
                    startupActiveProfileMutable.value = renamed
                }
                emitSuccess(getApplication<Application>().getString(R.string.profile_renamed))
            }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.profile_rename_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.onThemeSelected(value: ThemeMode) {
    viewModelScope.launch {
        container.settingsRepository.updateThemeMode(value)
    }
}

internal fun HomeViewModel.onLocaleSelected(value: AppLocale) {
    viewModelScope.launch {
        container.settingsRepository.updateLocale(value)
        applyAppLocale(value)
        getApplication<Application>().refreshLocalizedNotificationChannels()
    }
}

internal fun HomeViewModel.onAutoReconnectChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoReconnect(value)
    }
}

internal fun HomeViewModel.onAtomicConnectionChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAtomicConnection(value)
    }
}

internal fun HomeViewModel.onAutoStartChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoStartOnBoot(value)
    }
}

internal fun HomeViewModel.onAutoRefreshSubscriptionsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoRefreshSubscriptions(value)
        getApplication<Application>().applySubscriptionRefreshSchedule(
            enabled = value,
            interval = container.settingsRepository.current().connection.subscriptionRefreshInterval,
        )
    }
}

internal fun HomeViewModel.onSubscriptionRefreshIntervalSelected(value: SubscriptionRefreshInterval) {
    viewModelScope.launch {
        container.settingsRepository.updateSubscriptionRefreshInterval(value)
        val enabled = container.settingsRepository.current().connection.autoRefreshSubscriptions
        getApplication<Application>().applySubscriptionRefreshSchedule(
            enabled = enabled,
            interval = value,
        )
    }
}

internal fun HomeViewModel.onGeoIpAutoUpdateChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateGeoIpAutoUpdate(value)
        getApplication<Application>().applyGeoIpUpdateSchedule(
            enabled = value && container.settingsRepository.current().connection.componentUpdateCheckEnabled,
        )
    }
}

internal fun HomeViewModel.onGeoOfflineModeChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateGeoOfflineMode(value)
    }
}

internal fun HomeViewModel.onLatencyProbeMethodSelected(value: LatencyProbeMethod) {
    viewModelScope.launch {
        container.settingsRepository.updateLatencyProbeMethod(value)
        scheduleActiveProfileLatencyRefresh()
    }
}

internal fun HomeViewModel.onTunStackSelected(value: TunStack) {
    viewModelScope.launch {
        container.settingsRepository.updateTunStack(value)
    }
}

internal fun HomeViewModel.onTrafficModeSelected(value: TrafficMode) {
    val current = container.settingsRepository.settings.value.traffic.mode
    if (current == value) {
        return
    }
    updateRouteModeSettingAndPromptRestart {
        container.settingsRepository.updateTrafficMode(value)
    }
}

internal fun HomeViewModel.onMtuChanged(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMtu(value)
    }
}

internal fun HomeViewModel.onPreferIpv6Changed(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updatePreferIpv6(value)
    }
}

internal fun HomeViewModel.onDomainStrategySelected(value: DomainStrategy) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateDomainStrategy(value)
    }
}

internal fun HomeViewModel.onDnsSettingsChanged(value: DnsSettings) {
    val currentDns = container.settingsRepository.settings.value.dns
    if (shouldPreflightDnsRuleSetEnable(currentDns, value)) {
        preflightAndApplyDnsRuleSetSettings(value)
        return
    }
    updateDnsSettingsAndMaybeReconnect(value)
}

private fun HomeViewModel.updateDnsSettingsAndMaybeReconnect(value: DnsSettings) {
    updateRuntimeSettingAndMaybeReconnect {
        container.settingsRepository.updateDnsSettings(value)
        val settings = container.settingsRepository.current()
        getApplication<Application>().applyDnsFilterUpdateSchedule(
            enabled =
            settings.connection.componentUpdateCheckEnabled &&
                settings.dns.autoUpdateFilters &&
                settings.dns.dnsRuleSetFilteringEnabled(),
        )
    }
}

private fun HomeViewModel.preflightAndApplyDnsRuleSetSettings(value: DnsSettings) {
    if (dnsFilterRefreshInProgressMutable.value) {
        return
    }
    dnsFilterEnablePreflightJob = viewModelScope.launch {
        dnsFilterRefreshInProgressMutable.value = true
        dnsFilterUpdatePhaseMutable.value = FoxholeUpdatePhase.CHECKING
        componentUpdates.dnsFilterDownloadProgressMutable.value = null
        try {
            emitInfo(getApplication<Application>().getString(R.string.dns_filter_refresh_started))
            var terminalPhase =
                withTimeoutOrNull(DNS_FILTER_PREFLIGHT_TIMEOUT_MS) {
                    refreshVerifiedDnsRuleSet(value)
                } ?: FoxholeUpdatePhase.FAILED
            when (terminalPhase) {
                FoxholeUpdatePhase.DONE -> {
                    if (applyVerifiedDnsSettings(value)) {
                        dnsFilterUpdateAvailableMutable.value = false
                        emitSuccess(getApplication<Application>().getString(R.string.dns_filter_refresh_complete))
                    } else {
                        terminalPhase = FoxholeUpdatePhase.FAILED
                        emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
                    }
                }
                FoxholeUpdatePhase.NO_UPDATE -> {
                    if (applyVerifiedDnsSettings(value)) {
                        dnsFilterUpdateAvailableMutable.value = false
                        emitInfo(getApplication<Application>().getString(R.string.dns_filter_refresh_current))
                    } else {
                        terminalPhase = FoxholeUpdatePhase.FAILED
                        emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
                    }
                }
                else -> emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
            }
            settleUpdatePhase({ phase -> dnsFilterUpdatePhaseMutable.value = phase }, terminalPhase)
        } finally {
            dnsFilterRefreshInProgressMutable.value = false
            dnsFilterEnablePreflightJob = null
        }
    }
}

private suspend fun HomeViewModel.applyVerifiedDnsSettings(
    value: DnsSettings,
): Boolean =
    try {
        container.settingsRepository.updateDnsSettings(value)
        val settings = container.settingsRepository.current()
        getApplication<Application>().applyDnsFilterUpdateSchedule(
            enabled =
            settings.connection.componentUpdateCheckEnabled &&
                settings.dns.autoUpdateFilters &&
                settings.dns.dnsRuleSetFilteringEnabled(),
        )
        reloadRuntimeAfterDnsRuleSetEnableIfNeeded()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        container.diagnosticsLogger.recordFailure(
            "dns",
            "verified filter apply failed error=${error.javaClass.simpleName}",
        )
        false
    }

internal fun HomeViewModel.onDnsFilterReEnabled(value: DnsSettings) {
    dnsFilterUpdateAvailableMutable.value = false
    updateDnsSettingsAndMaybeReconnect(value)
    viewModelScope.launch {
        val check =
            runCatching { container.dnsFilterUpdateRepository.checkForUpdate(dnsSettingsOverride = value) }
                .getOrNull()
                ?: return@launch
        if (check.availability != DnsFilterUpdateAvailability.UPDATE_AVAILABLE) {
            return@launch
        }
        if (value.autoUpdateFilters) {
            refreshDnsFilterInBackground()
        } else {
            dnsFilterUpdateAvailableMutable.value = true
        }
    }
}

internal fun HomeViewModel.onDnsFilterUpdateNow() {
    onDnsFilterManualRefresh()
}

internal fun HomeViewModel.onDnsFilterUpdateDismissed() {
    dnsFilterUpdateAvailableMutable.value = false
}

private fun HomeViewModel.refreshDnsFilterInBackground() {
    if (dnsFilterRefreshInProgressMutable.value) {
        return
    }
    viewModelScope.launch {
        dnsFilterRefreshInProgressMutable.value = true
        componentUpdates.dnsFilterDownloadProgressMutable.value = null
        try {
            runCatching {
                container.dnsFilterUpdateRepository.refreshNow(
                    requireAutoEnabled = false,
                    onProgress = { progress ->
                        componentUpdates.dnsFilterDownloadProgressMutable.value = progress
                    },
                )
            }
                .onFailure { error ->
                    container.diagnosticsLogger.recordFailure(
                        "dns",
                        "background filter update failed error=${error.javaClass.simpleName}",
                    )
                }
        } finally {
            dnsFilterRefreshInProgressMutable.value = false
        }
    }
}

internal fun HomeViewModel.onDnsFilterEnablePreflightCancelled() {
    dnsFilterEnablePreflightJob?.cancel()
    dnsFilterEnablePreflightJob = null
    dnsFilterRefreshInProgressMutable.value = false
    componentUpdates.dnsFilterDownloadProgressMutable.value = null
}

private const val DNS_FILTER_PREFLIGHT_TIMEOUT_MS = 50_000L

private suspend fun HomeViewModel.refreshVerifiedDnsRuleSet(value: DnsSettings): FoxholeUpdatePhase {
    val updateResult =
        runCatching {
            container.dnsFilterUpdateRepository.refreshNow(
                requireAutoEnabled = false,
                dnsSettingsOverride = value,
                onPhase = { phase -> dnsFilterUpdatePhaseMutable.value = phase.toFoxholeUpdatePhase() },
                onProgress = { progress ->
                    componentUpdates.dnsFilterDownloadProgressMutable.value = progress
                },
            )
        }.getOrElse { error ->
            container.diagnosticsLogger.recordFailure(
                "dns",
                "filter preflight failed error=${error.javaClass.simpleName}",
            )
            return FoxholeUpdatePhase.FAILED
        }
    return verifiedDnsRefreshPhase(updateResult.status)
}

private suspend fun HomeViewModel.verifiedDnsRefreshPhase(status: DnsFilterUpdateStatus): FoxholeUpdatePhase {
    val updatePhase = dnsFilterTerminalPhase(status)
    if (!updatePhase.isSuccessfulDnsRefresh()) return updatePhase
    val verifiedRuleSetReady =
        runCatching { container.dnsFilterAssetInstaller.prepareVerifiedOrNull() != null }
            .getOrDefault(false)
    val terminalPhase = dnsFilterVerifiedTerminalPhase(status, verifiedRuleSetReady)
    if (!terminalPhase.isSuccessfulDnsRefresh()) return terminalPhase
    if (status == DnsFilterUpdateStatus.UP_TO_DATE) {
        container.settingsRepository.markDnsFiltersUpdated()
    }
    return terminalPhase
}

internal fun HomeViewModel.onDnsBypassPackagesChanged(value: List<String>) {
    updateRuntimeSettingAndMaybeReconnect {
        container.settingsRepository.updateDnsBypassPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
    }
}

internal fun HomeViewModel.onDnsDomainBypassRulesChanged(value: List<String>) {
    updateRuntimeSettingAndMaybeReconnect {
        container.settingsRepository.updateDnsDomainBypassRules(value)
    }
}

internal fun HomeViewModel.onDnsFilterManualRefresh() {
    if (dnsFilterRefreshInProgressMutable.value) {
        return
    }
    dnsFilterUpdatePhaseMutable.value = FoxholeUpdatePhase.CHECKING
    componentUpdates.dnsFilterDownloadProgressMutable.value = null
    dnsFilterManualRefreshJob =
        viewModelScope.launch {
            dnsFilterRefreshInProgressMutable.value = true
            val terminalPhase =
                try {
                    runDnsFilterManualRefreshRequest()
                } finally {
                    dnsFilterRefreshInProgressMutable.value = false
                }
            settleUpdatePhase({ phase -> dnsFilterUpdatePhaseMutable.value = phase }, terminalPhase)
        }
}

private suspend fun HomeViewModel.runDnsFilterManualRefreshRequest(): FoxholeUpdatePhase =
    runCatching {
        container.dnsFilterUpdateRepository.refreshNow(
            requireAutoEnabled = false,
            onPhase = { phase -> dnsFilterUpdatePhaseMutable.value = phase.toFoxholeUpdatePhase() },
            onProgress = { progress ->
                componentUpdates.dnsFilterDownloadProgressMutable.value = progress
            },
        )
    }.fold(
        onSuccess = { result ->
            when (val terminalPhase = verifiedDnsRefreshPhase(result.status)) {
                FoxholeUpdatePhase.DONE -> {
                    dnsFilterUpdateAvailableMutable.value = false
                    emitSuccess(getApplication<Application>().getString(R.string.dns_filter_refresh_complete))
                    terminalPhase
                }
                FoxholeUpdatePhase.NO_UPDATE -> {
                    dnsFilterUpdateAvailableMutable.value = false
                    emitInfo(getApplication<Application>().getString(R.string.dns_filter_refresh_current))
                    terminalPhase
                }
                else -> {
                    container.diagnosticsLogger.record(
                        "dns",
                        "verified filter refresh unavailable status=${result.status.name.lowercase()}",
                    )
                    emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
                    terminalPhase
                }
            }
        },
        onFailure = { error ->
            container.diagnosticsLogger.recordFailure(
                "dns",
                "verified filter refresh failed error=${error.javaClass.simpleName}",
            )
            emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
            FoxholeUpdatePhase.FAILED
        },
    )

internal fun HomeViewModel.onDnsFilterManualRefreshCancel() {
    dnsFilterManualRefreshJob?.cancel()
    dnsFilterManualRefreshJob = null
    dnsFilterRefreshInProgressMutable.value = false
    dnsFilterUpdatePhaseMutable.value = FoxholeUpdatePhase.IDLE
    componentUpdates.dnsFilterDownloadProgressMutable.value = null
}

private fun FoxholeUpdatePhase.isSuccessfulDnsRefresh(): Boolean =
    this == FoxholeUpdatePhase.DONE || this == FoxholeUpdatePhase.NO_UPDATE

private suspend fun HomeViewModel.reloadRuntimeAfterDnsRuleSetEnableIfNeeded() {
    if (!shouldReloadRuntimeAfterDnsRuleSetEnable(container.settingsRepository.current().dns)) {
        return
    }
    val snapshot = container.connectionController.snapshot.value
    if (snapshot.state !in ACTIVE_CONNECTION_STATES) {
        return
    }
    if (snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID) {
        container.connectionController.syncLocalGuard()
        return
    }
    val targetProfileId = activeRuntimeProfileIdForReload() ?: return
    markRuntimeReloadPending()
    if (container.connectionController.reload(targetProfileId)) {
        scheduleDashboardRefreshAfterRuntimeReload()
    } else {
        clearRuntimeReloadPending()
    }
}
