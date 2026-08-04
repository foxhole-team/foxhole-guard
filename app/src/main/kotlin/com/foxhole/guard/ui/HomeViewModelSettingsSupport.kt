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
import com.foxhole.guard.core.settings.updateDnsBypassPackages
import com.foxhole.guard.core.settings.updateDnsDomainBypassRules
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateDomainStrategy
import com.foxhole.guard.core.settings.updatePreferIpv6
import com.foxhole.guard.core.settings.updateTrafficMode
import com.foxhole.guard.core.settings.updateTrafficMtu
import com.foxhole.guard.core.settings.updateTunStack
import com.foxhole.guard.runtime.DnsFilterUpdateAvailability
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.userFacingErrorMessage
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

/**
 * Per-protocol on/off toggle from the smart-profile management sheet (N1). Persists the enabled flag
 * in the profile secret; the profiles flow re-emits so the sheet reflects the change immediately.
 */
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
    }
}

internal fun HomeViewModel.onAutoReconnectChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAutoReconnect(value)
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
    viewModelScope.launch {
        dnsFilterRefreshInProgressMutable.value = true
        try {
            emitInfo(getApplication<Application>().getString(R.string.dns_filter_refresh_started))
            val verifiedRuleSetReady = ensureVerifiedDownloadedDnsRuleSet(value)
            if (!verifiedRuleSetReady) {
                emitError(getApplication<Application>().getString(R.string.dns_filter_refresh_failed))
                return@launch
            }
            updateDnsSettingsAndMaybeReconnect(value)
            emitSuccess(getApplication<Application>().getString(R.string.dns_filter_refresh_complete))
        } finally {
            dnsFilterRefreshInProgressMutable.value = false
        }
    }
}

// Re-enable path when a verified list is already on disk: enable immediately (no download modal),
// then probe the update source. A newer list is auto-installed when auto-update is on, or surfaced
// as the "update available" prompt when it is off; an up-to-date or unreachable source is silent.
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

// Confirm action of the "update available" prompt: download the newer list and reload the runtime.
internal fun HomeViewModel.onDnsFilterUpdateNow() {
    dnsFilterUpdateAvailableMutable.value = false
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
        try {
            runCatching { container.dnsFilterUpdateRepository.refreshNow(requireAutoEnabled = false) }
                .onSuccess { result ->
                    reloadRuntimeAfterDnsRuleSetRefreshIfNeeded(result.status)
                }
                .onFailure { error ->
                    container.diagnosticsLogger.record(
                        "dns",
                        "background filter update failed error=${error.javaClass.simpleName}",
                    )
                }
        } finally {
            dnsFilterRefreshInProgressMutable.value = false
        }
    }
}

internal fun HomeViewModel.onDnsFilterEnablePreflight(
    value: DnsSettings,
    onResult: (Boolean) -> Unit,
) {
    if (dnsFilterRefreshInProgressMutable.value) {
        onResult(false)
        return
    }
    dnsFilterEnablePreflightJob =
        viewModelScope.launch {
            dnsFilterRefreshInProgressMutable.value = true
            val verifiedRuleSetReady =
                try {
                    // Hard upper bound so the interactive "enable + load" never hangs the dialog at 92% if
                    // the source is unreachable/slow (OkHttp's call timeout does not cover host resolution).
                    // On timeout the dialog resolves to the ERROR state instead of spinning forever.
                    withTimeoutOrNull(DNS_FILTER_PREFLIGHT_TIMEOUT_MS) {
                        ensureVerifiedDownloadedDnsRuleSet(value)
                    } ?: false
                } finally {
                    dnsFilterRefreshInProgressMutable.value = false
                }
            onResult(verifiedRuleSetReady)
        }
}

// Lets the enable-DNS-filter dialog's Cancel button actually abort an in-flight download instead of
// only hiding the dialog while dnsFilterRefreshInProgressMutable stays stuck true in the background
// (which would silently block a retry until the original attempt's 30s timeout elapsed).
internal fun HomeViewModel.onDnsFilterEnablePreflightCancelled() {
    dnsFilterEnablePreflightJob?.cancel()
    dnsFilterEnablePreflightJob = null
    dnsFilterRefreshInProgressMutable.value = false
}

private const val DNS_FILTER_PREFLIGHT_TIMEOUT_MS = 30_000L

// The gate for turning filtering ON is "a verified rule set is usable", not "the update source
// answered": the app ships verified lists, and a previously downloaded one stays on disk. An
// unreachable, stale or incompatible source must therefore fall back to what is already verified
// here instead of refusing to enable the filter at all (an app whose version predates the
// manifest's min_app_version otherwise cannot enable DNS filtering on any network).
private suspend fun HomeViewModel.ensureVerifiedDownloadedDnsRuleSet(value: DnsSettings): Boolean {
    val updateResult =
        runCatching {
            container.dnsFilterUpdateRepository.refreshNow(
                requireAutoEnabled = false,
                dnsSettingsOverride = value,
            )
        }.getOrElse { error ->
            container.diagnosticsLogger.record(
                "dns",
                "filter preflight failed error=${error.javaClass.simpleName}",
            )
            null
        }
    val downloadUsable =
        updateResult != null &&
            (
                updateResult.status == DnsFilterUpdateStatus.UPDATED ||
                    updateResult.status == DnsFilterUpdateStatus.UP_TO_DATE
                )
    val verifiedRuleSetReady =
        runCatching { container.dnsFilterAssetInstaller.prepareVerifiedOrNull() != null }
            .getOrDefault(false)
    if (!downloadUsable && verifiedRuleSetReady) {
        container.diagnosticsLogger.record(
            "dns",
            "filter preflight fell back to the verified rule set already on device " +
                "status=${updateResult?.status?.name?.lowercase() ?: "unavailable"}",
        )
    }
    return verifiedRuleSetReady
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
        )
    }.fold(
        onSuccess = { result ->
            when (result.status) {
                DnsFilterUpdateStatus.UPDATED -> {
                    dnsFilterUpdateAvailableMutable.value = false
                    reloadRuntimeAfterDnsRuleSetRefreshIfNeeded(result.status)
                    FoxholeUpdatePhase.DONE
                }
                DnsFilterUpdateStatus.UP_TO_DATE -> {
                    dnsFilterUpdateAvailableMutable.value = false
                    FoxholeUpdatePhase.NO_UPDATE
                }
                else -> {
                    container.diagnosticsLogger.record(
                        "dns",
                        "verified filter refresh unavailable status=${result.status.name.lowercase()}",
                    )
                    FoxholeUpdatePhase.FAILED
                }
            }
        },
        onFailure = { error ->
            container.diagnosticsLogger.record(
                "dns",
                "verified filter refresh failed error=${error.javaClass.simpleName}",
            )
            FoxholeUpdatePhase.FAILED
        },
    )

internal fun HomeViewModel.onDnsFilterManualRefreshCancel() {
    dnsFilterManualRefreshJob?.cancel()
    dnsFilterManualRefreshJob = null
    dnsFilterRefreshInProgressMutable.value = false
    dnsFilterUpdatePhaseMutable.value = FoxholeUpdatePhase.IDLE
}

internal fun dnsFilterRefreshAllowsRuntime(status: DnsFilterUpdateStatus): Boolean =
    status == DnsFilterUpdateStatus.UPDATED

internal fun shouldPreflightDnsRuleSetEnable(
    current: DnsSettings,
    next: DnsSettings,
): Boolean =
    !current.dnsRuleSetFilteringEnabled() &&
        next.dnsRuleSetFilteringEnabled() &&
        current.filtersUpdatedAt == null

internal fun shouldReloadRuntimeAfterDnsRuleSetRefresh(
    status: DnsFilterUpdateStatus,
    dnsSettings: DnsSettings,
): Boolean =
    dnsFilterRefreshAllowsRuntime(status) &&
        dnsSettings.dnsRuleSetFilteringEnabled()

private suspend fun HomeViewModel.reloadRuntimeAfterDnsRuleSetRefreshIfNeeded(status: DnsFilterUpdateStatus) {
    if (!shouldReloadRuntimeAfterDnsRuleSetRefresh(status, container.settingsRepository.current().dns)) {
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
