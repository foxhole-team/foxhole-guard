package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.runtime.torAllAppsCollidesWithVpnIncludeSplit
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.InstalledAppSecurityNotifier
import com.foxhole.guard.core.settings.applyAppLaneEdits
import com.foxhole.guard.core.settings.resolveQuarantinedApp
import com.foxhole.guard.core.settings.splitTunnelTrafficMode
import com.foxhole.guard.core.settings.updateAppLane
import com.foxhole.guard.core.settings.updateBlockAppsAlways
import com.foxhole.guard.core.settings.updateBlockedPackages
import com.foxhole.guard.core.settings.updateBlockedPackagesEnabled
import com.foxhole.guard.core.settings.updatePerAppRoutingMode
import com.foxhole.guard.core.settings.updatePrivacyRouteBlockAppsWhenTorUnavailable
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updatePrivacyRouteSelectedPackages
import com.foxhole.guard.core.settings.updateSelectedPackages
import com.foxhole.guard.core.settings.updateTrafficMode
import com.foxhole.guard.core.settings.updateVpnRoutingScenario
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.onPerAppRoutingModeSelected(value: PerAppRoutingMode) {
    onVpnRoutingScenarioSelected(
        when (value) {
            PerAppRoutingMode.FULL_TUNNEL -> VpnRoutingScenario.WHOLE_DEVICE
            PerAppRoutingMode.INCLUDE_SELECTED_APPS -> VpnRoutingScenario.SELECTED_INCLUDE
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> VpnRoutingScenario.SELECTED_EXCLUDE
        },
    )
}

internal fun HomeViewModel.onVpnRoutingScenarioSelected(value: VpnRoutingScenario) {
    val settings = container.settingsRepository.settings.value
    if (currentVpnRoutingScenario(settings) == value) {
        return
    }
    if (
        value.requiresSelectedApps() &&
        settings.expert.tunnelSelectedPackages().none(String::isNotBlank)
    ) {
        snackbars.tryEmit(warningBanner(R.string.split_tunnel_requires_apps))
        return
    }
    val change = PendingRoutingScenarioChange.Vpn(
        current = currentVpnRoutingScenario(settings),
        scenario = value,
    )
    if (requiresApplyConfirmation(AtomicApplyScope.SCENARIO, settings.connection.atomicConnection)) {
        pendingRoutingScenarioConfirmationMutable.value = change
        return
    }
    applyRoutingScenarioChange(change)
}

internal fun VpnRoutingScenario.requiresSelectedApps(): Boolean =
    this == VpnRoutingScenario.SELECTED_INCLUDE || this == VpnRoutingScenario.SELECTED_EXCLUDE

internal fun HomeViewModel.confirmPendingRoutingScenario() {
    val change = pendingRoutingScenarioConfirmationMutable.value ?: return
    pendingRoutingScenarioConfirmationMutable.value = null
    applyRoutingScenarioChange(change)
}

internal fun HomeViewModel.dismissPendingRoutingScenario() {
    pendingRoutingScenarioConfirmationMutable.value = null
}

private fun HomeViewModel.applyRoutingScenarioChange(change: PendingRoutingScenarioChange) {
    when (change) {
        is PendingRoutingScenarioChange.OperatingMode ->
            applyConfirmedConnectModeSwitch(change.target, change.scope)
        is PendingRoutingScenarioChange.I2pRelay -> applyI2pEngagement(change.targetEnabled)
        is PendingRoutingScenarioChange.Vpn -> applyVpnRoutingScenario(change.scenario)
        is PendingRoutingScenarioChange.Tor -> applyPrivacyRouteScopeSelection(change.scope)
    }
}

private fun HomeViewModel.applyVpnRoutingScenario(value: VpnRoutingScenario) {
    val settings = container.settingsRepository.settings.value
    val targetMode = value.perAppRoutingMode(settings.expert.perAppRoutingMode)
    val localHttpProxyEnabled = value == VpnRoutingScenario.PROXY_SERVER
    val liveSnapshot = container.connectionController.snapshot.value
    val targetProfileId = activeRuntimeProfileIdForReload()
    val activeRuntime = targetProfileId != null && liveSnapshot.state in ACTIVE_CONNECTION_STATES
    val liveTorOnly = targetProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    val routingChange =
        resolveRoutingChangeAction(
            old = RoutingChangeState(liveSnapshot.trafficMode, torOnlyRuntime = liveTorOnly),
            new = RoutingChangeState(
                splitTunnelTrafficMode(settings.traffic.mode, targetMode),
                torOnlyRuntime = liveTorOnly,
            ),
        )
    if (activeRuntime && routingChange == RoutingChangeAction.FULL_SWITCH) {
        updateRouteModeSettingAndPromptRestart {
            container.settingsRepository.updateVpnRoutingScenario(targetMode, localHttpProxyEnabled)
            emitRoutingScenarioSelected(value.terminalLabelRes())
        }
        return
    }
    updateAppRoutingSettingAndPromptReconnect {
        container.settingsRepository.updateVpnRoutingScenario(targetMode, localHttpProxyEnabled)
        emitRoutingScenarioSelected(value.terminalLabelRes())
    }
}

private fun VpnRoutingScenario.terminalLabelRes(): Int =
    when (this) {
        VpnRoutingScenario.WHOLE_DEVICE -> R.string.cli_route_vpn_whole_device
        VpnRoutingScenario.SELECTED_INCLUDE -> R.string.cli_route_split_include
        VpnRoutingScenario.SELECTED_EXCLUDE -> R.string.cli_route_split_exclude
        VpnRoutingScenario.PROXY_SERVER -> R.string.cli_route_vpn_proxy_server
    }

internal fun HomeViewModel.onSelectedPackagesChanged(value: List<String>) {
    updateAppRoutingSettingAndPromptReconnect {
        val selectedPackages = value.filterNot { it == getApplication<Application>().packageName }
        container.settingsRepository.updateSelectedPackages(selectedPackages)
        if (selectedPackages.isEmpty()) {
            container.settingsRepository.updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
        }
    }
}

internal fun HomeViewModel.onAppLaneChanged(
    packageName: String,
    lane: com.foxhole.core.model.AppTunnelLane?,
) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateAppLane(packageName, lane)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onAppLaneEditsApplied(
    added: List<String>,
    lane: com.foxhole.core.model.AppTunnelLane,
    removed: List<String>,
) {
    if (added.isEmpty() && removed.isEmpty()) {
        return
    }
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.applyAppLaneEdits(added, lane, removed)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onQuarantinedAppResolved(
    packageName: String,
    keepBlocked: Boolean,
) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.resolveQuarantinedApp(packageName, keepBlocked)
        InstalledAppSecurityNotifier(getApplication<Application>()).cancel(packageName)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onPrivacyRouteModeSelected(value: PrivacyRouteMode) {
    if (value == PrivacyRouteMode.OFF) {
        cancelPendingTorQuickStartPermissionRequest()
    }
    val settings = container.settingsRepository.settings.value
    if (rejectUnavailablePrivacyRouteMode(value, settings)) {
        return
    }
    val snapshot = container.connectionController.snapshot.value
    val torOnlyEngaged =
        snapshot.profileId == com.foxhole.guard.runtime.FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            snapshot.state != ConnectionState.IDLE
    val torStartPendingWithoutPrimaryRuntime =
        controlUiState.value.torOperation.active && !snapshot.isPrimaryConnectionRuntime()
    val mustStopEngagedTorOnly = torOnlyEngaged || torStartPendingWithoutPrimaryRuntime
    val routeScopeReady = settings.torScopeRunnable()
    if (value == PrivacyRouteMode.TOR_OVER_VPN && routeScopeReady) {
        markTorOperation(HomeTorOperationKind.CONNECTING)
    } else {
        clearTorOperation()
        torIpInfoMutable.value = null
    }
    container.diagnosticsLogger.record(
        "tor",
        "privacy route mode -> $value via hot-reload path (snapshot=${snapshot.state}/${snapshot.profileId})",
    )
    updateRuntimeSettingAndMaybeReload(
        forceRuntimeApply = true,
        forceStopStandaloneTor = mustStopEngagedTorOnly && value == PrivacyRouteMode.OFF,
    ) {
        container.settingsRepository.updatePrivacyRouteMode(value)
        val settings = container.settingsRepository.settings.value
        if (value == PrivacyRouteMode.TOR_OVER_VPN && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
            snackbars.tryEmit(warningBanner(R.string.privacy_route_all_apps_start_warning))
        }
    }
}

private fun HomeViewModel.rejectUnavailablePrivacyRouteMode(
    value: PrivacyRouteMode,
    settings: com.foxhole.core.model.Settings,
): Boolean {
    if (value != PrivacyRouteMode.TOR_OVER_VPN) {
        return false
    }
    when {
        !settings.privacyRoute.permitted ->
            snackbars.tryEmit(errorBanner(R.string.privacy_route_core_forbidden))
        !settings.torScopeRunnable() ->
            snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
        torAllAppsBesideActiveVpnCollision(
            settings = settings,
            mode = value,
            primaryVpnActive = container.connectionController.snapshot.value.isPrimaryConnectionRuntime(),
        ) -> snackbars.tryEmit(errorBanner(R.string.privacy_route_all_apps_requires_no_vpn))
        privacyRouteModeCollidesWithVpnIncludeSplit(settings, value) ->
            snackbars.tryEmit(errorBanner(R.string.error_tor_all_apps_needs_full_tunnel))
        shouldBlockTorOverUdpVpnEnable() ->
            torTransitionPromptMutable.value =
                TorTransitionPrompt.UdpVpnProtocolNotSupported(
                    protocolName = controlUiState.value.activeProfile?.protocolOptionOrDefault(null)?.displayName,
                )
        else -> return false
    }
    return true
}

internal fun privacyRouteModeCollidesWithVpnIncludeSplit(
    settings: com.foxhole.core.model.Settings,
    mode: PrivacyRouteMode,
): Boolean =
    settings.copy(
        privacyRoute = settings.privacyRoute.copy(mode = mode),
    ).torAllAppsCollidesWithVpnIncludeSplit()

internal fun HomeViewModel.onPrivacyRouteModeConfigured(value: PrivacyRouteMode) {
    onPrivacyRouteModeSelected(value)
}

internal fun HomeViewModel.shouldBlockTorOverUdpVpnEnable(
    bypassVpnTunnel: Boolean = controlUiState.value.settings.privacyRoute.bypassVpnTunnel,
): Boolean {
    val state = controlUiState.value
    val snapshot = state.connection
    if (
        bypassVpnTunnel ||
        snapshot.state !in ACTIVE_CONNECTION_STATES ||
        snapshot.profileId == com.foxhole.guard.runtime.FoxholeVpnService.TOR_ONLY_PROFILE_ID
    ) {
        return false
    }
    val protocolHint =
        snapshot.protocolHint ?: state.activeProfile?.protocolOptionOrDefault(null)?.protocolHint
    return protocolHint?.isUdpTransport() == true
}

internal fun HomeViewModel.onPrivacyRouteScopeSelected(value: PrivacyRouteScope): Boolean {
    val settings = container.settingsRepository.settings.value
    if (settings.privacyRoute.scope == value) {
        return true
    }
    if (rejectUnavailablePrivacyRouteScope(settings, value)) return false
    val change = PendingRoutingScenarioChange.Tor(
        current = settings.privacyRoute.scope,
        scope = value,
    )
    if (requiresApplyConfirmation(AtomicApplyScope.SCENARIO, settings.connection.atomicConnection)) {
        pendingRoutingScenarioConfirmationMutable.value = change
        return true
    }
    applyRoutingScenarioChange(change)
    return true
}

private fun HomeViewModel.applyPrivacyRouteScopeSelection(value: PrivacyRouteScope) {
    val settings = container.settingsRepository.settings.value
    if (rejectUnavailablePrivacyRouteScope(settings, value)) return
    updateRuntimeSettingAndMaybeReload(forceRuntimeApply = true) {
        container.settingsRepository.updatePrivacyRouteScope(value)
        emitRoutingScenarioSelected(value.terminalLabelRes())
    }
}

internal fun privacyRouteScopeCollidesWithVpnIncludeSplit(
    settings: com.foxhole.core.model.Settings,
    scope: PrivacyRouteScope,
): Boolean =
    settings.copy(
        privacyRoute = settings.privacyRoute.copy(scope = scope),
    ).torAllAppsCollidesWithVpnIncludeSplit()

private fun PrivacyRouteScope.terminalLabelRes(): Int =
    when (this) {
        PrivacyRouteScope.ALL_APPS -> R.string.cli_route_tor_device
        PrivacyRouteScope.SELECTED_APPS -> R.string.cli_route_tor_apps
    }

internal fun HomeViewModel.applyPrivacyRouteScopeChange(
    value: PrivacyRouteScope,
    restartNow: Boolean,
) {
    val settings = container.settingsRepository.settings.value
    if (rejectUnavailablePrivacyRouteScope(settings, value)) return
    updateRuntimeSettingAndMaybeReload(forceRuntimeApply = true) {
        container.settingsRepository.updatePrivacyRouteScope(value)
        if (value == PrivacyRouteScope.ALL_APPS && container.settingsRepository.current().privacyRoute.enabled) {
            snackbars.tryEmit(warningBanner(R.string.privacy_route_all_apps_start_warning))
        }
        if (!restartNow) {
            snackbars.tryEmit(warningBanner(R.string.privacy_route_settings_saved_deferred))
        }
    }
}

internal fun HomeViewModel.onPrivacyRouteScopeConfigured(value: PrivacyRouteScope) {
    applyPrivacyRouteScopeChange(value, restartNow = true)
}

internal fun HomeViewModel.onPrivacyRouteBypassVpnTunnelChanged(value: Boolean) {
    val settings = container.settingsRepository.settings.value
    if (
        torAllAppsBesideActiveVpnCollision(
            settings = settings,
            bypassVpnTunnel = value,
            primaryVpnActive = container.connectionController.snapshot.value.isPrimaryConnectionRuntime(),
        )
    ) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_all_apps_requires_no_vpn))
        return
    }
    if (settings.privacyRoute.enabled && !value && shouldBlockTorOverUdpVpnEnable(bypassVpnTunnel = false)) {
        val protocolName = controlUiState.value.activeProfile?.protocolOptionOrDefault(null)?.displayName
        torTransitionPromptMutable.value = TorTransitionPrompt.UdpVpnProtocolNotSupported(protocolName)
        return
    }
    updateRuntimeSettingAndMaybeReload(forceRuntimeApply = true) {
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(value)
    }
}

private fun HomeViewModel.rejectUnavailablePrivacyRouteScope(
    settings: com.foxhole.core.model.Settings,
    scope: PrivacyRouteScope,
): Boolean {
    val messageResource =
        when {
            torAllAppsBesideActiveVpnCollision(
                settings = settings,
                scope = scope,
                primaryVpnActive = container.connectionController.snapshot.value.isPrimaryConnectionRuntime(),
            ) -> R.string.privacy_route_all_apps_requires_no_vpn
            privacyRouteScopeCollidesWithVpnIncludeSplit(settings, scope) ->
                R.string.error_tor_all_apps_needs_full_tunnel
            else -> return false
        }
    snackbars.tryEmit(errorBanner(messageResource))
    return true
}

internal fun torAllAppsBesideActiveVpnCollision(
    settings: com.foxhole.core.model.Settings,
    mode: PrivacyRouteMode = settings.privacyRoute.mode,
    scope: PrivacyRouteScope = settings.privacyRoute.scope,
    bypassVpnTunnel: Boolean = settings.privacyRoute.bypassVpnTunnel,
    primaryVpnActive: Boolean,
): Boolean =
    primaryVpnActive &&
        settings.privacyRoute.permitted &&
        mode == PrivacyRouteMode.TOR_OVER_VPN &&
        scope == PrivacyRouteScope.ALL_APPS &&
        bypassVpnTunnel

internal fun HomeViewModel.onPrivacyRouteBypassVpnTunnelConfigured(value: Boolean) {
    onPrivacyRouteBypassVpnTunnelChanged(value)
}

internal fun HomeViewModel.onPrivacyRouteBlockAppsWhenTorUnavailableChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload(forceRuntimeApply = true) {
        container.settingsRepository.updatePrivacyRouteBlockAppsWhenTorUnavailable(value)
    }
}

internal fun HomeViewModel.onPrivacyRouteSelectedPackagesChanged(value: List<String>) {
    updateRuntimeSettingAndMaybeReload(forceRuntimeApply = true) {
        container.settingsRepository.updatePrivacyRouteSelectedPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
    }
}

internal fun HomeViewModel.onPrivacyRouteSelectedPackagesConfigured(value: List<String>) {
    onPrivacyRouteSelectedPackagesChanged(value)
}

internal fun HomeViewModel.onBlockedPackagesChanged(value: List<String>) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateBlockedPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onBlockedPackagesEnabledChanged(value: Boolean) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateBlockedPackagesEnabled(value)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onBlockAppsAlwaysChanged(value: Boolean) {
    if (value &&
        container.settingsRepository.settings.value.expert.blockedLanePackages().none(String::isNotBlank)
    ) {
        snackbars.tryEmit(warningBanner(R.string.blocked_apps_requires_apps))
        return
    }
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateBlockAppsAlways(value)
        container.connectionController.syncLocalGuard()
    }
}

private fun HomeViewModel.updateAppRoutingSettingAndPromptReconnect(
    requiresRuntimeWhenFull: Boolean = false,
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val previousMode = controlUiState.value.settings.expert.perAppRoutingMode
        val targetProfileId = activeRuntimeProfileIdForReload()
        val activeRuntime =
            targetProfileId != null &&
                container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
        if (activeRuntime) {
            markRuntimeReloadPending()
        }
        val updated =
            runCatching { updateAction() }
                .onFailure { error ->
                    container.diagnosticsLogger.recordFailure(
                        "settings",
                        "app routing update failed error=${error.javaClass.simpleName}",
                    )
                }.isSuccess
        if (!updated) {
            clearRuntimeReloadPending()
            snackbars.tryEmit(errorBanner(R.string.settings_secure_storage_failed))
            return@launch
        }
        val updatedMode = container.settingsRepository.current().expert.perAppRoutingMode
        val splitRulesAffectRuntime =
            requiresRuntimeWhenFull ||
                previousMode != PerAppRoutingMode.FULL_TUNNEL ||
                updatedMode != PerAppRoutingMode.FULL_TUNNEL
        if (!activeRuntime || !splitRulesAffectRuntime) {
            clearRuntimeReconnectRequired()
            clearRuntimeReloadPending()
            return@launch
        }
        val appliedFingerprint = container.connectionController.appliedRuntimeSignature.value
        val currentFingerprint = container.connectionController.currentRuntimeFingerprint()
        if (appliedFingerprint != currentFingerprint) {
            clearRuntimeReconnectRequired()
            val liveTorOnly = targetProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
            val routingChange =
                resolveRoutingChangeAction(
                    old = RoutingChangeState(
                        container.connectionController.snapshot.value.trafficMode,
                        torOnlyRuntime = liveTorOnly,
                    ),
                    new = RoutingChangeState(
                        container.settingsRepository.current().traffic.mode,
                        torOnlyRuntime = liveTorOnly,
                    ),
                )
            val reloadRequested =
                routingChange == RoutingChangeAction.HOT_RELOAD &&
                    container.connectionController.reload(requireNotNull(targetProfileId))
            container.diagnosticsLogger.recordStructured(
                "connection",
                "split config reapply",
                "mode=${previousMode.name.lowercase()}->${updatedMode.name.lowercase()}",
                "fingerprint_changed=true",
                "outcome=${if (reloadRequested) "reload_requested" else "reconnect_required"}",
                "profile_id=$targetProfileId",
            )
            if (reloadRequested) {
                scheduleDashboardRefreshAfterRuntimeReload()
            } else {
                markRuntimeReconnectRequired()
                clearRuntimeReloadPending()
                snackbars.emit(warningBanner(R.string.split_tunnel_reconnect_required))
            }
        } else {
            container.diagnosticsLogger.recordStructured(
                "connection",
                "split config reapply",
                "mode=${previousMode.name.lowercase()}->${updatedMode.name.lowercase()}",
                "fingerprint_changed=false",
                "outcome=no_op",
                "profile_id=$targetProfileId",
            )
            clearRuntimeReconnectRequired()
            clearRuntimeReloadPending()
        }
    }
}

internal fun HomeViewModel.updateRouteModeSettingAndPromptRestart(
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val currentSettings = container.settingsRepository.current()
        val targetProfileId = activeRuntimeProfileIdForReload()
        val activeRuntime =
            targetProfileId != null &&
                targetProfileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
                container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
        if (!activeRuntime) {
            updateAction()
            clearRouteModeRestartPrompt()
            clearRuntimeReconnectRequired()
            return@launch
        }
        val baseline =
            routeModeRestartBaseline
                ?: (currentSettings.traffic.mode to currentSettings.expert.perAppRoutingMode).also { routeModeRestartBaseline = it }
        updateAction()
        markRuntimeReconnectRequired()
        markProfileReconnectPromptWindow()
        scheduleRouteModeRestartRevert(baseline)
    }
}

private fun HomeViewModel.scheduleRouteModeRestartRevert(
    baseline: Pair<TrafficMode, PerAppRoutingMode>,
) {
    routeModeRestartPromptJob?.cancel()
    routeModeRestartPromptJob =
        viewModelScope.launch {
            delay(HomeViewModel.PROFILE_RECONNECT_PROMPT_WINDOW_MS)
            if (routeModeRestartBaseline == baseline && !reconnectInProgressMutable.value) {
                container.settingsRepository.updatePerAppRoutingMode(baseline.second)
                container.settingsRepository.updateTrafficMode(baseline.first)
                clearRuntimeReconnectRequired()
                profileReconnectPromptUntilMutable.value = 0L
                routeModeRestartBaseline = null
            }
            routeModeRestartPromptJob = null
        }
}

internal fun HomeViewModel.clearRouteModeRestartPrompt() {
    routeModeRestartPromptJob?.cancel()
    routeModeRestartPromptJob = null
    routeModeRestartBaseline = null
}
