package com.foxhole.guard.ui
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorRoutePlacement
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.activeRoutingModePreset
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun HomeViewModel.onSelectActiveProtocolOptionRequested(optionId: String) {
    val state = controlUiState.value
    val profile = state.activeProfile
    val option = profile?.protocolOptionOrDefault(optionId)
    if (profile != null && option != null) {
        if (
            shouldPromptDisableTorForUdpProtocol(
                torEnabledOrRuntimeActive = state.settings.privacyRoute.enabled || isTorOnlyRuntimeActive(state.connection),
                switchingToUdp = option.protocolHint.isUdpTransport(),
            )
        ) {
            torTransitionPromptMutable.value =
                TorTransitionPrompt.DisableTorForUdpProtocol(
                    profileId = profile.id,
                    protocolOptionId = option.id,
                    protocolName = option.displayName,
                )
        } else {
            selectProtocolOptionAndMaybeReconnect(profile.id, option.id)
        }
    }
}

/**
 * The TOR switch controls the runtime, not just the persisted mode: enabling with a live VPN
 * hot-reloads Tor into/beside it, with no VPN starts the standalone Tor-only runtime (with
 * consent); disabling fully stops whatever Tor is engaged. Every entry point reuses these paths.
 */
internal fun HomeViewModel.onTorRuntimeToggledInternal(enabled: Boolean) {
    if (!enabled) {
        onPrivacyRouteModeSelected(PrivacyRouteMode.OFF)
        return
    }
    val snapshot = container.connectionController.snapshot.value
    val settings = container.settingsRepository.settings.value
    if (refusesTorEnable(snapshot, settings)) {
        return
    }
    val activeProtocolIsUdp =
        (
            snapshot.protocolHint
                ?: controlUiState.value.activeProfile?.protocolOptionOrDefault(null)?.protocolHint
            )?.isUdpTransport() == true
    if (
        snapshot.isPrimaryConnectionRuntime() &&
        !settings.privacyRoute.bypassVpnTunnel &&
        activeProtocolIsUdp
    ) {
        // A live UDP tunnel cannot carry Tor inside it — offer to run Tor beside the VPN instead.
        // All-apps scope cannot ride beside a tunnel either, so guide to the scope setting first.
        if (settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
            snackbars.tryEmit(errorBanner(R.string.privacy_route_all_apps_requires_no_vpn))
        } else {
            torTransitionPromptMutable.value = TorTransitionPrompt.StartTorBesideUdpVpn
        }
        return
    }
    if (snapshot.isPrimaryConnectionRuntime()) {
        onPrivacyRouteModeSelected(PrivacyRouteMode.TOR_OVER_VPN)
    } else if (shouldWarnStartTorFromDeviceWithoutVpn()) {
        torTransitionPromptMutable.value = TorTransitionPrompt.StartTorFromDeviceWithoutVpn
    } else {
        onEnableDirectTorQuickStart()
    }
}

// Hard refusals for enabling Tor, mirrored from the quick-access modal so every entry point
// agrees; true means a banner was raised and the toggle must not proceed.
private fun HomeViewModel.refusesTorEnable(
    snapshot: com.foxhole.core.model.ConnectionSnapshot,
    settings: com.foxhole.core.model.Settings,
): Boolean {
    if (!settings.privacyRoute.permitted) {
        // The controls are hidden/disabled without the permission, but guard the path anyway
        // (stale UI state, races) — a forbidden core must never start.
        snackbars.tryEmit(errorBanner(R.string.privacy_route_core_forbidden))
        return true
    }
    if (
        snapshot.isPrimaryConnectionRuntime() &&
        settings.traffic.mode == com.foxhole.core.model.TrafficMode.PROXY &&
        !settings.privacyRoute.bypassVpnTunnel
    ) {
        // Proxy mode has no tunnel to carry Tor: the dashboard button must refuse exactly like the
        // modal does, not hot-reload a config that silently cannot include the Tor route.
        snackbars.tryEmit(errorBanner(R.string.privacy_route_proxy_mode_requires_bypass))
        return true
    }
    if (
        snapshot.isPrimaryConnectionRuntime() &&
        settings.privacyRoute.bypassVpnTunnel &&
        settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS
    ) {
        // All-apps Tor beside a live VPN would raise a second device-wide tunnel next to the VPN's:
        // refuse and point at the scope setting — only selected apps may ride beside the tunnel.
        snackbars.tryEmit(errorBanner(R.string.privacy_route_all_apps_requires_no_vpn))
        return true
    }
    return false
}

/**
 * Confirm for [TorTransitionPrompt.StartTorBesideUdpVpn]: flip placement to bypass and start Tor
 * via the ordinary hot-reload — the UDP tunnel keeps running untouched.
 */
internal fun HomeViewModel.confirmStartTorBesideUdpVpn() {
    torTransitionPromptMutable.value = null
    viewModelScope.launch {
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(true)
        onPrivacyRouteModeSelected(PrivacyRouteMode.TOR_OVER_VPN)
    }
}

/**
 * Tor placed inside the tunnel (bypass off) with a saved VPN profile but no running VPN: the
 * start would silently fall back to direct-from-device Tor, contradicting the chosen placement —
 * warn instead. With bypass on, device-route Tor is exactly what the user configured.
 */
private fun HomeViewModel.shouldWarnStartTorFromDeviceWithoutVpn(): Boolean =
    !container.settingsRepository.settings.value.privacyRoute.bypassVpnTunnel &&
        controlUiState.value.activeProfile != null

@Suppress("ReturnCount")
internal fun HomeViewModel.onEnableDirectTorQuickStart() {
    val quickStartSettings = container.settingsRepository.settings.value
    val privacyRoute = quickStartSettings.privacyRoute
    if (!privacyRoute.permitted) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_core_forbidden))
        return
    }
    if (!quickStartSettings.torScopeRunnable()) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
        return
    }
    torTransitionPromptMutable.value = null
    clearRuntimeReconnectRequired()
    torIpInfoMutable.value = null
    markTorOperation(HomeTorOperationKind.CONNECTING)
    val snapshot = container.connectionController.snapshot.value
    if (snapshot.isActivePrimaryVpnProfileRuntime()) {
        // All-traffic TOR over an active VPN would raise a second nested tunnel (and crashes);
        // with a VPN up, only selected apps may ride through TOR.
        if (privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
            clearTorOperation()
            snackbars.tryEmit(errorBanner(R.string.privacy_route_all_apps_requires_no_vpn))
            return
        }
        updateRuntimeSettingAndMaybeReload {
            container.diagnosticsLogger.record("tor", "tor quick start hot-reload active vpn runtime")
            // Never touch bypassVpnTunnel: that toggle is owned by the user in routing settings.
            container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
        }
        return
    }
    viewModelScope.launch {
        runCatching {
            container.diagnosticsLogger.record("tor", "tor quick start standalone tor-only runtime")
            // Keep bypassVpnTunnel exactly as the user configured it; quick start only arms the route.
            container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
            if (privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
                snackbars.tryEmit(infoBanner(R.string.privacy_route_all_apps_start_warning))
            }
            check(container.settingsRepository.current().privacyRoute.enabled) { "TOR route is disabled" }
            requestManualConnectPermissionOrConnect(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
        }.onFailure { error ->
            clearTorOperation()
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    error,
                    R.string.error_runtime_stopped,
                ),
            )
        }
    }
}

private fun ConnectionSnapshot.isActivePrimaryVpnProfileRuntime(): Boolean =
    state in ACTIVE_CONNECTION_STATES &&
        (profileId ?: 0L) > 0L

internal fun shouldPromptDisableTorForUdpProtocol(
    torEnabledOrRuntimeActive: Boolean,
    switchingToUdp: Boolean,
): Boolean = torEnabledOrRuntimeActive && switchingToUdp

// A protocol switch on the live profile must surface the Reconnect affordance: nothing
// dispatches a hot reload for a protocol change (the old informational flag silently timed out —
// the tunnel kept the old protocol, no Restart ever appeared). Cycling back clears it.
internal fun shouldRequireReconnectAfterProtocolSwitch(
    runningOptionId: String?,
    selectedOptionId: String,
): Boolean = runningOptionId == null || runningOptionId != selectedOptionId

internal fun HomeViewModel.selectProtocolOptionAndMaybeReconnect(
    profileId: Long,
    optionId: String,
) {
    cancelAutoConnect(clearUiOnly = true)
    viewModelScope.launch {
        runCatching {
            val updated = container.profileRepository.selectProfileProtocolOption(profileId, optionId)
            if (updated.isActive) {
                startupActiveProfileMutable.value = updated
                markProfileReconnectPromptWindow()
            }
            if (controlUiState.value.activeProfile?.id == profileId && controlUiState.value.connection.state in ACTIVE_CONNECTION_STATES) {
                // Same hint-fallback as isProfileReconnectRequired: sessions without an option id
                // must still let the carousel cancel the Restart by cycling back.
                val runningOptionId =
                    controlUiState.value.connection.protocolOptionId
                        ?: runningOptionIdFromProtocolHint(
                            controlUiState.value.activeProfile,
                            controlUiState.value.connection,
                        )
                if (
                    shouldRequireReconnectAfterProtocolSwitch(
                        runningOptionId = runningOptionId,
                        selectedOptionId = optionId,
                    )
                ) {
                    markRuntimeReconnectRequired()
                    scheduleProtocolSwitchRevert(
                        profileId = profileId,
                        baselineOptionId = runningOptionId,
                        selectedOptionId = optionId,
                    )
                } else {
                    clearRuntimeReconnectRequired()
                    clearProtocolSwitchRevert()
                }
                clearProfileLatencyRefresh()
            } else if (updated.isActive) {
                // Not connected: probe the new protocol right away so the latency pill and icon
                // color track the selection instead of the previous option's stale value.
                scheduleActiveProfileLatencyRefresh(
                    showLoading = false,
                    refreshImmediately = true,
                )
            } else {
                // The quick selector exposes options of every smart profile, not only the active
                // one. Persisting an option in an inactive profile and then closing the selector
                // left the dashboard on the old profile, so the tap appeared to do nothing. Route
                // the chosen smart profile through the same safe selection path as a single
                // profile: idle remembers it; a live different profile raises the switch sheet.
                onQuickSelectorSingleProfileSelected(profileId)
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

// Same contract as the route-mode dropdown: an unused reconnect offer expires and the dropdown
// returns to the protocol that actually runs, not a selection the runtime never applied.
private fun HomeViewModel.scheduleProtocolSwitchRevert(
    profileId: Long,
    baselineOptionId: String?,
    selectedOptionId: String,
) {
    protocolSwitchRevertJob?.cancel()
    if (baselineOptionId == null || baselineOptionId == selectedOptionId) {
        protocolSwitchRevertJob = null
        return
    }
    protocolSwitchRevertJob =
        viewModelScope.launch {
            kotlinx.coroutines.delay(HomeViewModel.PROFILE_RECONNECT_PROMPT_WINDOW_MS)
            protocolSwitchRevertJob = null
            if (reconnectInProgressMutable.value) {
                return@launch
            }
            val state = controlUiState.value
            val stillPendingSameSwitch =
                state.activeProfile?.id == profileId &&
                    state.connection.state in ACTIVE_CONNECTION_STATES &&
                    state.connection.protocolOptionId == baselineOptionId &&
                    state.activeProfile.selectedProtocolOptionId == selectedOptionId
            if (!stillPendingSameSwitch) {
                return@launch
            }
            runCatching {
                val reverted = container.profileRepository.selectProfileProtocolOption(profileId, baselineOptionId)
                if (reverted.isActive) {
                    startupActiveProfileMutable.value = reverted
                }
            }
            clearRuntimeReconnectRequired()
        }
}

internal fun HomeViewModel.clearProtocolSwitchRevert() {
    protocolSwitchRevertJob?.cancel()
    protocolSwitchRevertJob = null
}

internal fun HomeViewModel.confirmDisableTorForUdpProtocol(
    prompt: TorTransitionPrompt.DisableTorForUdpProtocol,
) {
    viewModelScope.launch {
        torTransitionPromptMutable.value = null
        container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
        clearTorOperation()
        torIpInfoMutable.value = null
        // selectProfileProtocolOption throws on an unknown/unselectable option; unwrapped, that
        // throw in viewModelScope took the app down. Surface the usual error banner and leave
        // the tunnel alone instead of reconnecting onto nothing.
        val selected =
            runCatching {
                container.profileRepository.selectProfileProtocolOption(
                    profileId = prompt.profileId,
                    optionId = prompt.protocolOptionId,
                )
            }.onFailure { error ->
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        error,
                        R.string.profile_update_failed,
                    ),
                )
            }
        if (selected.isFailure) {
            return@launch
        }
        val snapshot = container.connectionController.snapshot.value
        if (snapshot.state in ACTIVE_CONNECTION_STATES && snapshot.profileId == prompt.profileId) {
            requestReconnect(prompt.profileId)
        }
    }
}

internal fun HomeViewModel.confirmMoveTorIntoVpn(
    prompt: TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive,
) {
    viewModelScope.launch {
        torTransitionPromptMutable.value = null
        container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(false)
        requestManualConnectPermissionOrConnect(prompt.profileId, prompt.protocolOptionId)
    }
}

internal fun HomeViewModel.confirmKeepTorOnDeviceAndStartVpn(
    prompt: TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive,
) {
    viewModelScope.launch {
        torTransitionPromptMutable.value = null
        container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(true)
        requestManualConnectPermissionOrConnect(prompt.profileId, prompt.protocolOptionId)
    }
}

internal fun HomeViewModel.dismissTorTransitionPrompt() {
    // Also stops a live mode-switch countdown so "no" (or a timeout supersede) reverts immediately.
    liveModeSwitchCountdownJob?.cancel()
    liveModeSwitchCountdownJob = null
    torTransitionPromptMutable.value = null
}

/**
 * MODE-button entry point. With atomic application off, every accepted mode change is parked in
 * the shared current→future sheet. Otherwise, a Tor-involving live change keeps its safety
 * countdown; idle, tor-only and Tor-neutral changes apply directly.
 * See [liveModeSwitchKind].
 *
 * The explicit result keeps terminal narration honest: a parked confirmation is not an applied
 * mode and must not print a command until the user confirms it.
 */
internal enum class ConnectModeSwitchRequestResult {
    REJECTED,
    APPLIED,
    DEFERRED,
}

internal fun HomeViewModel.onConnectModeSwitchRequested(
    target: com.foxhole.core.model.RoutingModePreset,
    scope: PrivacyRouteScope,
): ConnectModeSwitchRequestResult {
    val snapshot = container.connectionController.snapshot.value
    if (!routingModePresetSelectable(target, scope)) {
        return ConnectModeSwitchRequestResult.REJECTED
    }
    if (!snapshot.isPrimaryConnectionRuntime()) {
        return requestOrApplyOperatingModeChange(target, scope)
    }
    if (
        target == com.foxhole.core.model.RoutingModePreset.VPN_TOR &&
        // VPN_TOR always writes Tor inside the VPN, even if the current route has Tor beside it.
        // Judge the target placement, not the setting we are about to replace.
        shouldBlockTorOverUdpVpnEnable(bypassVpnTunnel = false)
    ) {
        refuseUdpVpnTorModeSwitch(snapshot)
        return ConnectModeSwitchRequestResult.REJECTED
    }
    val current = container.settingsRepository.settings.value.activeRoutingModePreset()
    return requestOrApplyLiveOperatingModeChange(current, target, scope)
}

private fun HomeViewModel.refuseUdpVpnTorModeSwitch(snapshot: ConnectionSnapshot) {
    val protocolName =
        controlUiState.value.activeProfile
            ?.protocolOptionOrDefault(snapshot.protocolOptionId)
            ?.displayName
    torTransitionPromptMutable.value =
        TorTransitionPrompt.UdpVpnProtocolNotSupported(protocolName = protocolName)
}

private fun HomeViewModel.requestOrApplyLiveOperatingModeChange(
    current: com.foxhole.core.model.RoutingModePreset,
    target: com.foxhole.core.model.RoutingModePreset,
    scope: PrivacyRouteScope,
): ConnectModeSwitchRequestResult {
    if (
        requiresApplyConfirmation(
            AtomicApplyScope.MODE,
            container.settingsRepository.settings.value.connection.atomicConnection,
        )
    ) {
        pendingRoutingScenarioConfirmationMutable.value =
            PendingRoutingScenarioChange.OperatingMode(
                current = current,
                target = target,
                scope = scope,
            )
        return ConnectModeSwitchRequestResult.DEFERRED
    }
    val kind = liveModeSwitchKind(current, target)
    return if (kind == null) {
        // Tor-neutral reshape (split↔split) on a live tunnel: hot-reload straight through.
        if (onRoutingModePresetSelected(target, scope)) {
            ConnectModeSwitchRequestResult.APPLIED
        } else {
            ConnectModeSwitchRequestResult.REJECTED
        }
    } else {
        startLiveModeSwitchPrompt(target, scope, kind)
        ConnectModeSwitchRequestResult.DEFERRED
    }
}

/** Idle mode changes use the same atomic-application preference as live ones. */
private fun HomeViewModel.requestOrApplyOperatingModeChange(
    target: com.foxhole.core.model.RoutingModePreset,
    scope: PrivacyRouteScope,
): ConnectModeSwitchRequestResult {
    val settings = container.settingsRepository.settings.value
    if (requiresApplyConfirmation(AtomicApplyScope.MODE, settings.connection.atomicConnection)) {
        pendingRoutingScenarioConfirmationMutable.value =
            PendingRoutingScenarioChange.OperatingMode(
                current = settings.activeRoutingModePreset(),
                target = target,
                scope = scope,
            )
        return ConnectModeSwitchRequestResult.DEFERRED
    }
    // Keep the same confirmed-transition dispatcher for idle and standalone-Tor ownership. The
    // latter must do a typed stop -> IDLE -> start, never fall through to the reload sentinel.
    applyConfirmedConnectModeSwitch(target, scope)
    return ConnectModeSwitchRequestResult.APPLIED
}

private fun HomeViewModel.startLiveModeSwitchPrompt(
    target: com.foxhole.core.model.RoutingModePreset,
    scope: PrivacyRouteScope,
    kind: LiveModeSwitchKind,
) {
    liveModeSwitchCountdownJob?.cancel()
    torTransitionPromptMutable.value =
        TorTransitionPrompt.LiveModeSwitch(
            target = target,
            scope = scope,
            kind = kind,
            secondsLeft = LIVE_MODE_SWITCH_COUNTDOWN_SECONDS,
        )
    liveModeSwitchCountdownJob =
        viewModelScope.launch {
            var secondsLeft = LIVE_MODE_SWITCH_COUNTDOWN_SECONDS
            while (secondsLeft > 0) {
                kotlinx.coroutines.delay(1_000L)
                secondsLeft -= 1
                val current = torTransitionPromptMutable.value
                if (current !is TorTransitionPrompt.LiveModeSwitch || current.kind != kind || current.target != target) {
                    // Superseded by another prompt or dismissed: stop ticking, touch nothing.
                    return@launch
                }
                torTransitionPromptMutable.value = current.copy(secondsLeft = secondsLeft)
            }
            // Timed out: drop the modal, apply nothing — the settings were never changed.
            if (torTransitionPromptMutable.value is TorTransitionPrompt.LiveModeSwitch) {
                torTransitionPromptMutable.value = null
            }
        }
}

/** Confirm for a [TorTransitionPrompt.LiveModeSwitch]: apply the requested mode change. */
internal fun HomeViewModel.confirmLiveModeSwitch(prompt: TorTransitionPrompt.LiveModeSwitch) {
    liveModeSwitchCountdownJob?.cancel()
    liveModeSwitchCountdownJob = null
    torTransitionPromptMutable.value = null
    applyConfirmedConnectModeSwitch(prompt.target, prompt.scope, prompt.kind)
}

/** Applies a mode change after either the atomic-off sheet or the live-switch countdown. */
internal fun HomeViewModel.applyConfirmedConnectModeSwitch(
    target: com.foxhole.core.model.RoutingModePreset,
    scope: PrivacyRouteScope,
) {
    if (!routingModePresetSelectable(target, scope)) return
    val snapshot = container.connectionController.snapshot.value
    val current = container.settingsRepository.settings.value.activeRoutingModePreset()
    if (isTorOnlyRuntimeActive(snapshot) && target != com.foxhole.core.model.RoutingModePreset.TOR) {
        // A standalone Tor session has different runtime ownership from a VPN-backed preset. It
        // cannot be reshaped through ACTION_RELOAD: stop it completely, then persist/start the
        // confirmed target. Until IDLE, settings and UI remain on the actually running Tor mode.
        viewModelScope.launch {
            container.connectionController.disconnectTorOnly(userInitiated = false)
            if (awaitConfirmedModeHandoffIdle()) {
                startRoutingMode(target, scope)
            }
        }
        return
    }
    val kind = snapshot.takeIf { it.isPrimaryConnectionRuntime() }
        ?.let { liveModeSwitchKind(current, target) }
    if (
        kind != null &&
        target == com.foxhole.core.model.RoutingModePreset.VPN_TOR &&
        shouldBlockTorOverUdpVpnEnable(bypassVpnTunnel = false)
    ) {
        val protocolName =
            controlUiState.value.activeProfile
                ?.protocolOptionOrDefault(snapshot.protocolOptionId)
                ?.displayName
        torTransitionPromptMutable.value =
            TorTransitionPrompt.UdpVpnProtocolNotSupported(protocolName = protocolName)
        return
    }
    if (kind == null) {
        onRoutingModePresetSelected(target, scope)
    } else {
        applyConfirmedConnectModeSwitch(target, scope, kind)
    }
}

private fun HomeViewModel.applyConfirmedConnectModeSwitch(
    target: com.foxhole.core.model.RoutingModePreset,
    scope: PrivacyRouteScope,
    kind: LiveModeSwitchKind,
) {
    when (kind) {
        // Attach/detach Tor on the running tunnel: seamless hot reload, tunnel stays up.
        LiveModeSwitchKind.ATTACH_TOR,
        LiveModeSwitchKind.DETACH_TOR,
        -> onRoutingModePresetSelected(target, scope)
        // Pure Tor: stop the VPN first, then bring up the standalone Tor-only runtime. Starting
        // without the teardown reaches `onEnableDirectTorQuickStart` while the tunnel is still up,
        // which refuses all-apps Tor beside a VPN — the prompt promised the opposite.
        LiveModeSwitchKind.TOR_STOPS_VPN ->
            viewModelScope.launch {
                // This is one runtime handoff, not a user Stop. An intermediate local guard would
                // take the Android VPN slot and prevent the Tor-only runtime from starting.
                container.connectionController.disconnect(
                    suppressLocalGuard = true,
                    userInitiated = false,
                )
                if (awaitConfirmedModeHandoffIdle()) {
                    startRoutingMode(target, scope)
                }
            }
    }
}

private suspend fun HomeViewModel.awaitConfirmedModeHandoffIdle(): Boolean {
    val terminalState =
        withTimeoutOrNull(HomeViewModel.STOP_VPN_KEEP_TOR_SETTLE_TIMEOUT_MS) {
            container.connectionController.snapshot.first { snapshot ->
                snapshot.state == ConnectionState.IDLE || snapshot.state == ConnectionState.ERROR
            }
        }
    if (terminalState?.state == ConnectionState.IDLE) return true
    emitError(getApplication<Application>().getString(R.string.error_runtime_stopped))
    return false
}

internal fun HomeViewModel.maybePromptStartTcpVpnWhileTorOnlyActive(
    profile: Profile,
    protocolOptionId: String?,
): Boolean {
    val snapshot = container.connectionController.snapshot.value
    val targetProtocolHint = profile.protocolOptionOrDefault(protocolOptionId)?.protocolHint ?: profile.protocolHint
    // The user already chose "Tor beside the tunnel" (bypass): starting the VPN keeps Tor on the
    // device route without re-asking, so the split Start-VPN button switches seamlessly.
    val bypassAlreadyChosen = container.settingsRepository.settings.value.privacyRoute.bypassVpnTunnel
    val shouldPrompt = isTorOnlyRuntimeActive(snapshot) && !targetProtocolHint.isUdpTransport() && !bypassAlreadyChosen
    if (shouldPrompt) {
        torTransitionPromptMutable.value =
            TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive(
                profileId = profile.id,
                protocolOptionId = protocolOptionId,
                profileName = profile.name,
            )
    }
    return shouldPrompt
}

internal fun shouldPromptStartTcpVpnWhileTorOnlyActive(
    torRoutePlacement: TorRoutePlacement,
    targetProtocolIsUdp: Boolean,
): Boolean = torRoutePlacement == TorRoutePlacement.TOR_ONLY_DEVICE && !targetProtocolIsUdp

internal fun resolveTorRoutePlacement(
    settings: Settings,
    connection: ConnectionSnapshot,
): TorRoutePlacement =
    when {
        isTorOnlyRuntimeActive(connection) -> TorRoutePlacement.TOR_ONLY_DEVICE
        settings.privacyRoute.mode == PrivacyRouteMode.OFF -> TorRoutePlacement.OFF
        settings.privacyRoute.bypassVpnTunnel -> TorRoutePlacement.TOR_ON_DEVICE_WITH_VPN
        else -> TorRoutePlacement.TOR_OVER_VPN
    }

internal fun isTorOnlyRuntimeActive(connection: ConnectionSnapshot): Boolean =
    connection.state in ACTIVE_CONNECTION_STATES &&
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun Profile.protocolOptionOrDefault(optionId: String?): ProfileProtocolOption? {
    val requestedOption =
        optionId
            ?.takeIf(String::isNotBlank)
            ?.let { requestedId -> protocolOptions.firstOrNull { option -> option.id == requestedId } }
    return requestedOption
        ?: selectedProtocolOptionId
            ?.takeIf(String::isNotBlank)
            ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
        ?: protocolOptions.firstOrNull()
}
