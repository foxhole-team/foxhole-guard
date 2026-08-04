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
import com.foxhole.core.model.packages
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.resolveQuarantinedApp
import com.foxhole.guard.core.settings.splitTunnelTrafficMode
import com.foxhole.guard.core.settings.updateAppLane
import com.foxhole.guard.core.settings.updateBlockAppsAlways
import com.foxhole.guard.core.settings.updateBlockedPackages
import com.foxhole.guard.core.settings.updateBlockedPackagesEnabled
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updatePerAppRoutingMode
import com.foxhole.guard.core.settings.updatePrivacyRouteBlockAppsWhenTorUnavailable
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updatePrivacyRouteSelectedPackages
import com.foxhole.guard.core.settings.updateSelectedPackages
import com.foxhole.guard.core.settings.updateTrafficMode
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Per-app routing, privacy-route (Tor) and blocked-apps settings handlers for HomeViewModel.
// Extracted from HomeViewModelSettingsSupport (file split by domain); extension functions only.

internal fun HomeViewModel.onPerAppRoutingModeSelected(value: PerAppRoutingMode) {
    val settings = container.settingsRepository.settings.value
    val current = settings.expert.perAppRoutingMode
    if (current == value) {
        return
    }
    if (value != PerAppRoutingMode.FULL_TUNNEL && settings.expert.tunnelSelectedPackages().none(String::isNotBlank)) {
        // Enabling the split with no picked apps: answer with the guidance banner instead of a
        // restart prompt (there is nothing to apply yet); the switch simply stays off.
        snackbars.tryEmit(warningBanner(R.string.split_tunnel_requires_apps))
        return
    }
    val liveSnapshot = container.connectionController.snapshot.value
    val targetProfileId = activeRuntimeProfileIdForReload()
    val activeRuntime = targetProfileId != null && liveSnapshot.state in ACTIVE_CONNECTION_STATES
    val liveTorOnly = targetProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    val routingChange =
        resolveRoutingChangeAction(
            old = RoutingChangeState(liveSnapshot.trafficMode, torOnlyRuntime = liveTorOnly),
            new = RoutingChangeState(
                splitTunnelTrafficMode(settings.traffic.mode, value),
                torOnlyRuntime = liveTorOnly,
            ),
        )
    if (activeRuntime && routingChange == RoutingChangeAction.FULL_SWITCH) {
        // The live runtime serves the OTHER traffic mode (e.g. a PROXY runtime while the user
        // picks Split, which implies TUNNEL): a hot reload would assemble a config the running
        // service cannot host (the proxy runtime has no tun), and that reload failure used to
        // tear the whole VPN down. Route through the restart prompt instead — the pair applies
        // when the user confirms the restart, and the dropdown reverts if the offer expires.
        updateRouteModeSettingAndPromptRestart {
            container.settingsRepository.updatePerAppRoutingMode(value)
        }
        return
    }
    // Same-mode changes — enabling/disabling the split or moving between include/exclude on a
    // live tunnel — apply seamlessly through the hot-reload path below; no manual off/on needed.
    updateAppRoutingSettingAndPromptReconnect {
        container.settingsRepository.updatePerAppRoutingMode(value)
    }
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

/**
 * The new Apps screen's per-app dropdown: pin [packageName] to [lane], or remove it from every
 * lane when [lane] is null. A live tunnel hot-reloads the new membership (matrix R5).
 */
internal fun HomeViewModel.onAppLaneChanged(
    packageName: String,
    lane: com.foxhole.core.model.AppTunnelLane?,
) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.updateAppLane(packageName, lane)
        container.connectionController.syncLocalGuard()
    }
}

/**
 * The app-picker's multi-select confirm: pin every [packageNames] entry to [lane] in one pass.
 * Each assignment is an atomic settings write (lane derivations — firewall arming, split mode,
 * safe-mode exit — follow exactly as the single [onAppLaneChanged] path), but the whole batch
 * shares a single reconnect-prompt / hot-reload cycle and one local-guard sync at the end.
 */
internal fun HomeViewModel.onAppsAddedToLane(
    packageNames: List<String>,
    lane: com.foxhole.core.model.AppTunnelLane,
) {
    if (packageNames.isEmpty()) {
        return
    }
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        packageNames.forEach { packageName ->
            container.settingsRepository.updateAppLane(packageName, lane)
        }
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onQuarantinedAppResolved(
    packageName: String,
    keepBlocked: Boolean,
) {
    updateAppRoutingSettingAndPromptReconnect(requiresRuntimeWhenFull = true) {
        container.settingsRepository.resolveQuarantinedApp(packageName, keepBlocked)
        container.connectionController.syncLocalGuard()
    }
}

internal fun HomeViewModel.onPrivacyRouteModeSelected(value: PrivacyRouteMode) {
    val settings = container.settingsRepository.settings.value
    val privacyRoute = settings.privacyRoute
    if (value == PrivacyRouteMode.TOR_OVER_VPN && !settings.torScopeRunnable()) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
        return
    }
    if (value == PrivacyRouteMode.TOR_OVER_VPN && shouldBlockTorOverUdpVpnEnable()) {
        torTransitionPromptMutable.value =
            TorTransitionPrompt.UdpVpnProtocolNotSupported(
                protocolName = controlUiState.value.activeProfile?.protocolOptionOrDefault(null)?.displayName,
            )
        return
    }
    val snapshot = container.connectionController.snapshot.value
    // Turning Tor OFF must fully stop a Tor-only session in *any* engaged state, not just the
    // ACTIVE set: a pending/bootstrapping start can still have profileId unset in the snapshot
    // (service start race) or sit in ERROR — the hot-reload path leaves that session running, so
    // the modal kept showing the yellow pending pill and the IP skeleton after "Turn off".
    val torOnlyEngaged =
        snapshot.profileId == com.foxhole.guard.runtime.FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            snapshot.state != ConnectionState.IDLE
    val torStartPendingWithoutPrimaryRuntime =
        controlUiState.value.torOperation.active && !snapshot.isPrimaryConnectionRuntime()
    val mustStopEngagedTorOnly = torOnlyEngaged || torStartPendingWithoutPrimaryRuntime
    val routingChange =
        resolveRoutingChangeAction(
            old = RoutingChangeState(snapshot.trafficMode, torOnlyRuntime = mustStopEngagedTorOnly),
            new = RoutingChangeState(
                snapshot.trafficMode,
                torOnlyRuntime = mustStopEngagedTorOnly && value != PrivacyRouteMode.OFF,
            ),
        )
    if (routingChange == RoutingChangeAction.FULL_SWITCH) {
        viewModelScope.launch {
            clearTorOperation()
            torIpInfoMutable.value = null
            container.settingsRepository.updatePrivacyRouteMode(value)
            container.connectionController.disconnect(suppressLocalGuard = false)
            // The dashboard must fall back to the CURRENT device identity once the Tor-only
            // session is gone: wait for the runtime to settle, then refresh silently.
            withTimeoutOrNull(HomeViewModel.STOP_VPN_KEEP_TOR_SETTLE_TIMEOUT_MS) {
                container.connectionController.snapshot.first { it.state !in ACTIVE_CONNECTION_STATES }
            }
            refreshIpInfoSilently()
        }
        return
    }
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
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteMode(value)
        val settings = container.settingsRepository.settings.value
        if (value == PrivacyRouteMode.TOR_OVER_VPN && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
            snackbars.tryEmit(warningBanner(R.string.privacy_route_all_apps_start_warning))
        }
    }
}

internal fun HomeViewModel.onPrivacyRouteModeConfigured(value: PrivacyRouteMode) {
    viewModelScope.launch {
        val settings = container.settingsRepository.settings.value
        if (value == PrivacyRouteMode.TOR_OVER_VPN && !settings.torScopeRunnable()) {
            snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
            return@launch
        }
        container.settingsRepository.updatePrivacyRouteMode(value)
        if (value == PrivacyRouteMode.TOR_OVER_VPN && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
            snackbars.tryEmit(warningBanner(R.string.privacy_route_all_apps_start_warning))
        }
    }
}

private fun HomeViewModel.shouldBlockTorOverUdpVpnEnable(): Boolean {
    val state = controlUiState.value
    val snapshot = state.connection
    if (
        state.settings.privacyRoute.bypassVpnTunnel ||
        snapshot.state !in ACTIVE_CONNECTION_STATES ||
        snapshot.profileId == com.foxhole.guard.runtime.FoxholeVpnService.TOR_ONLY_PROFILE_ID
    ) {
        return false
    }
    val protocolHint =
        snapshot.protocolHint ?: state.activeProfile?.protocolOptionOrDefault(null)?.protocolHint
    return protocolHint?.isUdpTransport() == true
}

/**
 * A scope change with NO running Tor: persist it and say so in green — nothing to restart, the
 * next start simply picks the new scope up. Picking "selected apps" with nothing selected is
 * still refused outright (there would be nothing to route).
 *
 * @return false when the change was refused, so the caller keeps its dropdown where it was.
 */
internal fun HomeViewModel.onPrivacyRouteScopeSelected(value: PrivacyRouteScope): Boolean {
    if (!privacyRouteScopeSelectable(value)) {
        return false
    }
    viewModelScope.launch {
        container.settingsRepository.updatePrivacyRouteScope(value)
        snackbars.tryEmit(successBanner(R.string.privacy_route_settings_saved))
    }
    return true
}

/** «Selected apps» with no app picked routes nothing: refuse and say which step is missing. */
internal fun HomeViewModel.privacyRouteScopeSelectable(value: PrivacyRouteScope): Boolean {
    val settings = container.settingsRepository.settings.value
    if (
        value == PrivacyRouteScope.SELECTED_APPS &&
        settings.expert.packages(AppTunnelLane.TOR).isEmpty()
    ) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
        return false
    }
    return true
}

/**
 * The same scope change while Tor IS engaged, with the user's answer to "restart now?": either
 * hot-reload the live route immediately, or persist and let the next start pick it up.
 */
internal fun HomeViewModel.applyPrivacyRouteScopeChange(
    value: PrivacyRouteScope,
    restartNow: Boolean,
) {
    if (!privacyRouteScopeSelectable(value)) {
        return
    }
    if (!restartNow) {
        viewModelScope.launch {
            container.settingsRepository.updatePrivacyRouteScope(value)
            snackbars.tryEmit(warningBanner(R.string.privacy_route_settings_saved_deferred))
        }
        return
    }
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteScope(value)
        if (value == PrivacyRouteScope.ALL_APPS && container.settingsRepository.current().privacyRoute.enabled) {
            snackbars.tryEmit(warningBanner(R.string.privacy_route_all_apps_start_warning))
        }
    }
}

internal fun HomeViewModel.onPrivacyRouteScopeConfigured(value: PrivacyRouteScope) {
    // The dashboard/profile entry points still change the scope with an immediate hot reload of a
    // live route; the settings screen asks first (see [applyPrivacyRouteScopeChange]).
    applyPrivacyRouteScopeChange(value, restartNow = true)
}

internal fun HomeViewModel.onPrivacyRouteBypassVpnTunnelChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(value)
    }
}

internal fun HomeViewModel.onPrivacyRouteBypassVpnTunnelConfigured(value: Boolean) {
    // Toggling the "bypass VPN tunnel" option restructures the Tor route, so reconnect immediately
    // via the hot-reload path when Tor is running.
    onPrivacyRouteBypassVpnTunnelChanged(value)
}

internal fun HomeViewModel.onPrivacyRouteBlockAppsWhenTorUnavailableChanged(value: Boolean) {
    // Fail-closed guard for the Tor lane: reload a live tunnel so the reject rule for Tor apps
    // arms/disarms immediately instead of waiting for the next connect.
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteBlockAppsWhenTorUnavailable(value)
    }
}

internal fun HomeViewModel.onPrivacyRouteSelectedPackagesChanged(value: List<String>) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteSelectedPackages(
            value.filterNot { it == getApplication<Application>().packageName },
        )
    }
}

internal fun HomeViewModel.onPrivacyRouteSelectedPackagesConfigured(value: List<String>) {
    // Editing the Tor app list must reconnect a running Tor route so the new per-app scope applies.
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
        // Same contract as the split switch: flipping "always block" with an empty app list
        // answers with the guidance banner; nothing to arm yet.
        snackbars.tryEmit(warningBanner(R.string.blocked_apps_requires_apps))
        return
    }
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
        val previousMode = controlUiState.value.settings.expert.perAppRoutingMode
        val targetProfileId = activeRuntimeProfileIdForReload()
        val activeRuntime =
            targetProfileId != null &&
                container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
        if (activeRuntime) {
            markRuntimeReloadPending()
        }
        updateAction()
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
            // Never hot-reload across Android interface shapes. A change to the TUN address or app
            // split needs a reconnect because the existing VpnService interface cannot host it.
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
        // Tor beside the tunnel is its own runtime and ignores traffic.mode entirely: switching
        // tunnel<->proxy while only Tor-only is up must apply silently, never hold the switch
        // hostage behind a "restart Tor" prompt.
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
