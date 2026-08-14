package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.NetworkRulesSettings
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.StatisticsMetric
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.isSupportedForSystemDnsProtection
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.shouldDeferLocalGuardStartForActiveProfileRuntime
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.acknowledgeUnsafeWarning
import com.foxhole.guard.core.settings.recordInstalledAppInventory
import com.foxhole.guard.core.settings.unlockExpertSettings
import com.foxhole.guard.core.settings.updateBlockScreenshots
import com.foxhole.guard.core.settings.updateBlurEffectsEnabled
import com.foxhole.guard.core.settings.updateDashboardCardOrder
import com.foxhole.guard.core.settings.updateDnsReplaceSystemDns
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updateInstalledAppMonitoringEnabled
import com.foxhole.guard.core.settings.updateMonochromeTorTheme
import com.foxhole.guard.core.settings.updateNetworkActivityLogging
import com.foxhole.guard.core.settings.updateNetworkCardEnabled
import com.foxhole.guard.core.settings.updateNetworkDnsInfoEnabled
import com.foxhole.guard.core.settings.updateNetworkRulesSettings
import com.foxhole.guard.core.settings.updateNewAppQuarantineEnabled
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updateProfileListOrder
import com.foxhole.guard.core.settings.updateShowFirewallStatus
import com.foxhole.guard.core.settings.updateShowI2pQuickLaunch
import com.foxhole.guard.core.settings.updateShowQuickAccessPanel
import com.foxhole.guard.core.settings.updateShowTorDashboardControls
import com.foxhole.guard.core.settings.updateShowTorQuickLaunch
import com.foxhole.guard.core.settings.updateShowTrafficModeSelector
import com.foxhole.guard.core.settings.updateStatisticsEnabled
import com.foxhole.guard.core.settings.updateStatisticsMetricEnabled
import com.foxhole.guard.core.settings.updateStatisticsWidgetOrder
import com.foxhole.guard.core.settings.updateTrafficCardEnabled
import com.foxhole.guard.core.settings.updateTrafficMapEnabled
import com.foxhole.guard.core.settings.updateTrafficMapHistoryClearedAt
import com.foxhole.guard.core.settings.updateTrafficMapSectionOrder
import com.foxhole.guard.guardian.enqueuePendingQuarantineAnalysis
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Security, dashboard-card and local-guard related settings handlers for HomeViewModel.
// Extracted from HomeViewModelSettingsSupport (file split by domain); extension functions only.

internal fun HomeViewModel.onNetworkRulesChanged(value: NetworkRulesSettings) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkRulesSettings(value)
    }
}

internal fun HomeViewModel.acknowledgeUnsafeWarning() {
    viewModelScope.launch {
        container.settingsRepository.acknowledgeUnsafeWarning()
    }
}

internal fun HomeViewModel.unlockExpertSettings() {
    viewModelScope.launch {
        container.settingsRepository.unlockExpertSettings()
    }
}

internal fun HomeViewModel.onShowExpertSettingsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowExpertSettings(value)
    }
}

internal fun HomeViewModel.onBlockScreenshotsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateBlockScreenshots(value)
    }
}

internal fun HomeViewModel.onNewAppQuarantineChanged(value: Boolean) {
    viewModelScope.launch {
        val updated =
            runCatching { container.settingsRepository.updateNewAppQuarantineEnabled(value) }
                .onFailure { error ->
                    container.diagnosticsLogger.recordFailure(
                        "security",
                        "new-app quarantine baseline failed error=${error.javaClass.simpleName}",
                    )
                }.isSuccess
        if (!updated) {
            snackbars.tryEmit(errorBanner(R.string.settings_secure_storage_failed))
            return@launch
        }
        if (value) {
            container.settingsRepository.updateShowFirewallStatus(true)
            requestNotificationPermission.tryEmit(Unit)
            getApplication<Application>().enqueuePendingQuarantineAnalysis()
        }
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.onInstalledAppMonitoringChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateInstalledAppMonitoringEnabled(value)
        if (value) {
            requestNotificationPermission.tryEmit(Unit)
            if (installedAppsMutable.value.isEmpty()) {
                loadInstalledApps(force = true)
            } else {
                container.settingsRepository.recordInstalledAppInventory(installedAppsMutable.value)
            }
        }
    }
}

internal fun HomeViewModel.onTrafficMapEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMapEnabled(value)
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.enableTrafficMapSupportSettings() {
    viewModelScope.launch {
        applyTrafficMapSupportSettings()
        container.connectionController.syncLocalGuard()
        syncLocalGuardWithPermissionRequest()
        emitInfo(getApplication<Application>().getString(R.string.traffic_map_support_enabled))
    }
}

internal fun HomeViewModel.onNetworkCardEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkCardEnabled(value)
    }
}

internal fun HomeViewModel.onTrafficCardEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficCardEnabled(value)
    }
}

internal fun HomeViewModel.onNetworkDnsInfoEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkDnsInfoEnabled(value)
    }
}

internal fun HomeViewModel.onShowQuickAccessPanelChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowQuickAccessPanel(value)
    }
}

internal fun HomeViewModel.onShowTorQuickLaunchChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowTorQuickLaunch(value)
    }
}

internal fun HomeViewModel.onShowI2pQuickLaunchChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowI2pQuickLaunch(value)
    }
}

internal fun HomeViewModel.onShowTorDashboardControlsChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowTorDashboardControls(value)
    }
}

internal fun HomeViewModel.onBlurEffectsEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateBlurEffectsEnabled(value)
    }
}

internal fun HomeViewModel.onMonochromeTorThemeChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateMonochromeTorTheme(value)
    }
}

/**
 * The routing-settings "Enable Tor core" switch is a pure permission — it never launches a
 * runtime and never arms the route. Granting it only persists the flag (the core stays off until
 * the dashboard controls / Tor window start it); revoking it fully stops whatever Tor is engaged
 * (a forbidden core must not keep running) and disables every Tor control in the UI.
 */
internal fun HomeViewModel.onTorRoutePermittedChanged(value: Boolean) {
    if (!value) {
        onPrivacyRouteModeSelected(PrivacyRouteMode.OFF)
    }
    viewModelScope.launch {
        container.settingsRepository.updatePrivacyRoutePermitted(value)
    }
}

internal fun HomeViewModel.onShowFirewallStatusChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowFirewallStatus(value)
    }
}

internal fun HomeViewModel.onShowTrafficModeSelectorChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateShowTrafficModeSelector(value)
    }
}

internal fun HomeViewModel.onProfileListOrderChanged(value: List<Long>) {
    viewModelScope.launch {
        container.settingsRepository.updateProfileListOrder(value)
    }
}

internal fun HomeViewModel.onDashboardCardOrderChanged(value: List<com.foxhole.core.model.DashboardCard>) {
    viewModelScope.launch {
        container.settingsRepository.updateDashboardCardOrder(value)
    }
}

internal fun HomeViewModel.onStatisticsWidgetOrderChanged(value: List<com.foxhole.core.model.StatisticsWidgetId>) {
    viewModelScope.launch {
        container.settingsRepository.updateStatisticsWidgetOrder(value)
    }
}

internal fun HomeViewModel.onTrafficMapSectionOrderChanged(value: List<com.foxhole.core.model.TrafficMapSectionId>) {
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMapSectionOrder(value)
    }
}

internal fun HomeViewModel.onClearTrafficMapHistory() {
    // Live aggregates drop immediately; the persisted watermark keeps the cut across restarts.
    // Journals and statistics stay untouched — the map just stops reading anything older.
    container.trafficMapRepository.clearTrafficMapHistory()
    viewModelScope.launch {
        container.settingsRepository.updateTrafficMapHistoryClearedAt(System.currentTimeMillis())
    }
}

internal fun HomeViewModel.onFirewallEnabledChangedInternal(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateFirewallEnabled(value)
        // Enabling the firewall reveals its quick-access pill; disabling it must NOT hide the pill —
        // the pill's visibility is owned by the settings show-flag so the user can re-arm from it.
        if (value) {
            container.settingsRepository.updateShowFirewallStatus(true)
        }
        if (value) {
            // Arming the firewall arms autostart with it. A firewall that stops at the next reboot
            // is not a firewall, and the boot receiver ALREADY restores the guard in that case
            // (bootRestorePlan starts the local guard even with autostart off) — so the switch was
            // reporting "off" while the behaviour was "on". Turning the flag on makes the screen
            // agree with the device instead of the other way round.
            //
            // One direction only: turning the firewall OFF leaves autostart alone, because by then
            // it may be carrying a VPN profile's restore, which is a promise the firewall never
            // made and must not revoke. Note this does widen boot restore to the profile as well —
            // that is the owner's decision, taken knowingly.
            container.settingsRepository.updateAutoStartOnBoot(true)
            // The firewall advertises the traffic map as one of its features, but the map only
            // lights up when map + country-traffic stats + activity logging are on. Enabling the
            // firewall now switches those on together so the map actually populates in firewall-only
            // mode (previously it stayed on the "waiting" placeholder until each was toggled by hand).
            applyTrafficMapSupportSettings()
        }
        if (value || container.settingsRepository.current().dns.replaceSystemDns) {
            requestNotificationPermission.tryEmit(Unit)
        }
        syncLocalGuardWithPermissionRequest()
        if (value) {
            refreshLocalGuardDashboardIpAfterSettingsChange()
        }
    }
}

// NOTE: must NOT be named enableTrafficMapSupportSettings — that collides with the public member
// HomeViewModel.enableTrafficMapSupportSettings() (which re-enters enableTrafficMapSupportSettings).
// A Kotlin member outranks a same-named extension, so the callers below would recurse into an
// unbounded coroutine spawn (startForegroundService flood -> main-thread ANR) instead of flipping
// these four settings.
internal suspend fun HomeViewModel.applyTrafficMapSupportSettings() {
    container.settingsRepository.updateTrafficMapEnabled(true)
    container.settingsRepository.updateStatisticsEnabled(true)
    container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.COUNTRY_TRAFFIC, true)
    container.settingsRepository.updateNetworkActivityLogging(true)
}

internal fun HomeViewModel.onDnsReplaceSystemDnsChanged(value: Boolean) {
    viewModelScope.launch {
        if (value && !PrivateDnsSettings.current(getApplication<Application>()).isSupportedForSystemDnsProtection()) {
            container.settingsRepository.updateDnsReplaceSystemDns(false)
            container.connectionController.syncLocalGuard()
            snackbars.tryEmit(errorBanner(R.string.error_system_dns_private_dns_conflict))
            return@launch
        }
        container.settingsRepository.updateDnsReplaceSystemDns(value)
        if (value) {
            requestNotificationPermission.tryEmit(Unit)
        }
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.onNetworkActivityLoggingChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateNetworkActivityLogging(value)
        // The journal flag controls how raw on-device diagnostics are: turning it off masks live
        // rows again, while turning it on affects new records.
        container.diagnosticsLogger.applyLiveDiagnosticsPrivacySetting()
        container.connectionController.syncLocalGuard()
        emitInfo(
            getApplication<Application>().getString(
                if (value) R.string.network_activity_logging_enabled else R.string.network_activity_logging_disabled,
            ),
        )
    }
}

internal suspend fun HomeViewModel.syncLocalGuardWithPermissionRequest(forceRestart: Boolean = false) {
    runtimeSettingUpdates.awaitPending()
    if (
        shouldDeferLocalGuardSyncForActiveProfileRuntime(
            snapshot = container.connectionController.snapshot.value,
            activeProfileVpnNetworkPresent = container.connectionController.hasActiveVpnNetwork(),
        )
    ) {
        container.diagnosticsLogger.record("connection", "local guard permission sync deferred: active profile runtime")
        return
    }
    val mode = container.settingsRepository.current().localGuardModeOrNull()
    if (mode != null && android.net.VpnService.prepare(getApplication<Application>()) != null) {
        val accepted =
            enqueueVpnPermissionRequest(
                PendingConnectRequest(
                    action = PendingConnectAction.LOCAL_GUARD,
                ),
            )
        if (!accepted && pendingConnectRequest?.action != PendingConnectAction.LOCAL_GUARD) {
            pendingLocalGuardPermissionSync = true
            container.diagnosticsLogger.record(
                "connection",
                "local guard permission sync deferred: active vpn permission request",
            )
        }
        if (!accepted && pendingConnectRequest?.action == PendingConnectAction.LOCAL_GUARD) {
            container.diagnosticsLogger.record(
                "connection",
                "local guard permission sync already pending",
            )
        }
        return
    }
    container.connectionController.syncLocalGuard(forceRestart = forceRestart)
}

internal fun shouldDeferLocalGuardSyncForActiveProfileRuntime(
    snapshot: com.foxhole.core.model.ConnectionSnapshot,
    activeProfileVpnNetworkPresent: Boolean,
): Boolean =
    shouldDeferLocalGuardStartForActiveProfileRuntime(
        snapshot = snapshot,
        activeProfileSessionPresent = false,
        activeProfileVpnNetworkPresent = activeProfileVpnNetworkPresent,
    )

internal suspend fun HomeViewModel.refreshLocalGuardDashboardIpAfterSettingsChange() {
    delay(HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS)
    if (
        !shouldRefreshLocalGuardDashboardIpAfterSettingsChange(
            snapshot = container.connectionController.snapshot.value,
            settings = container.settingsRepository.current(),
        )
    ) {
        return
    }
    startIpInfoRefresh(
        reportFailures = false,
        showLoading = true,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.ENTRY_QUICK,
        minimumLoadingDurationMs = HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS,
        reason = IpInfoRefreshReason.POST_UPDATE,
    )
}

internal fun shouldRefreshLocalGuardDashboardIpAfterSettingsChange(
    snapshot: com.foxhole.core.model.ConnectionSnapshot,
    settings: com.foxhole.core.model.Settings,
): Boolean =
    settings.localGuardModeOrNull() != null &&
        snapshot.state == ConnectionState.CONNECTED &&
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
