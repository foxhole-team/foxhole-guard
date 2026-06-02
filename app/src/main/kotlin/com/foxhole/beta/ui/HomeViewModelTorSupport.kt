package com.foxhole.beta.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TorRoutePlacement
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.vpn.ACTIVE_CONNECTION_STATES
import com.foxhole.beta.vpn.FoxholeVpnService
import kotlinx.coroutines.launch

internal fun HomeViewModel.onSelectActiveProtocolOptionRequestedInternal(optionId: String) {
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

internal fun HomeViewModel.onEnableDirectTorQuickStartInternal() {
    viewModelScope.launch {
        runCatching {
            torTransitionPromptMutable.value = null
            clearRuntimeReconnectRequired()
            torIpInfoMutable.value = null
            markTorOperation(HomeTorOperationKind.CONNECTING)
            container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
            container.settingsRepository.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
            container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(true)
            connectNow(
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                statusMessage = getApplication<Application>().getString(R.string.notification_status_connecting),
            )
        }.onFailure { error ->
            clearTorOperation()
            emitError(error.message ?: getApplication<Application>().getString(R.string.error_runtime_stopped))
        }
    }
}

internal fun shouldPromptDisableTorForUdpProtocol(
    torEnabledOrRuntimeActive: Boolean,
    switchingToUdp: Boolean,
): Boolean = torEnabledOrRuntimeActive && switchingToUdp

internal fun HomeViewModel.selectProtocolOptionAndMaybeReconnectInternal(
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
                markRuntimeReloadPending()
                clearProfileLatencyRefresh()
            }
        }.onFailure {
            emitError(it.message ?: getApplication<Application>().getString(R.string.profile_update_failed))
        }
    }
}

internal fun HomeViewModel.confirmDisableTorForUdpProtocolInternal(
    prompt: TorTransitionPrompt.DisableTorForUdpProtocol,
) {
    viewModelScope.launch {
        torTransitionPromptMutable.value = null
        container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
        clearTorOperation()
        torIpInfoMutable.value = null
        container.profileRepository.selectProfileProtocolOption(
            profileId = prompt.profileId,
            optionId = prompt.protocolOptionId,
        )
        val snapshot = container.connectionController.snapshot.value
        if (snapshot.state in ACTIVE_CONNECTION_STATES && snapshot.profileId == prompt.profileId) {
            requestReconnect(prompt.profileId)
        }
    }
}

internal fun HomeViewModel.confirmMoveTorIntoVpnInternal(
    prompt: TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive,
) {
    viewModelScope.launch {
        torTransitionPromptMutable.value = null
        container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(false)
        requestManualConnectPermissionOrConnect(prompt.profileId, prompt.protocolOptionId)
    }
}

internal fun HomeViewModel.confirmKeepTorOnDeviceAndStartVpnInternal(
    prompt: TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive,
) {
    viewModelScope.launch {
        torTransitionPromptMutable.value = null
        container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
        container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(true)
        requestManualConnectPermissionOrConnect(prompt.profileId, prompt.protocolOptionId)
    }
}

internal fun HomeViewModel.dismissTorTransitionPromptInternal() {
    torTransitionPromptMutable.value = null
}

internal fun HomeViewModel.maybePromptStartTcpVpnWhileTorOnlyActive(
    profile: Profile,
    protocolOptionId: String?,
): Boolean {
    val snapshot = container.connectionController.snapshot.value
    val targetProtocolHint = profile.protocolOptionOrDefault(protocolOptionId)?.protocolHint ?: profile.protocolHint
    val shouldPrompt = isTorOnlyRuntimeActive(snapshot) && !targetProtocolHint.isUdpTransport()
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
