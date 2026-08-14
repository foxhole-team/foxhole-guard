package com.foxhole.guard.ui
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.isUdpTransport
import com.foxhole.guard.R
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.launch

/**
 * Profile/protocol switching that must confirm before touching a live tunnel (the yes/no modals
 * B2 and B3). All three entry points — the home quick-selector tap, the profile-detail "activate"
 * button, and a smart-profile protocol tap — funnel through here so the confirm decision lives in
 * one place and the rendered [TorTransitionPrompt] variants stay authoritative.
 */

/**
 * Home quick-selector tap on a SINGLE (non-smart) profile. Picking never starts a connection: the
 * choice is activated and remembered, and only a change of profile under a live VPN raises the
 * switch sheet first. (Smart profiles do not reach here: the selector expands their inline protocol
 * picker instead.)
 */
internal fun HomeViewModel.onQuickSelectorSingleProfileSelected(profileId: Long) {
    applyProfileSelection(profileId)
}

/**
 * Profile-detail "activate": switching to a different profile while a tunnel is live confirms via
 * B2 (then reconnects onto it); when idle it keeps the plain activation the dialog always had.
 */
internal fun HomeViewModel.onActivateProfileRequested(profileId: Long) {
    applyProfileSelection(profileId)
}

private fun HomeViewModel.applyProfileSelection(profileId: Long) {
    when (profileSelectionAction(controlUiState.value.connection, profileId)) {
        ProfileSelectionAction.CONFIRM_SWITCH -> promptSwitchProfileWhileConnected(profileId)
        ProfileSelectionAction.REMEMBER -> onSelectProfile(profileId)
    }
}

private fun HomeViewModel.promptSwitchProfileWhileConnected(profileId: Long) {
    val profile = controlUiState.value.profiles.firstOrNull { it.id == profileId }
    torTransitionPromptMutable.value =
        TorTransitionPrompt.SwitchProfileWhileConnected(
            profileId = profileId,
            profileName = profile?.name.orEmpty(),
        )
}

/** Confirm ("да") for B2: swap the active profile and reconnect the tunnel onto it. */
internal fun HomeViewModel.confirmSwitchProfileWhileConnected(
    prompt: TorTransitionPrompt.SwitchProfileWhileConnected,
) {
    torTransitionPromptMutable.value = null
    activateAndConnectProfile(prompt.profileId)
}

// The ONLY path from a profile pick to a running tunnel, and it is reachable exclusively from the
// B2 confirm above: swap the live tunnel onto the chosen profile through the same permission /
// proxy handling the Start button uses (connect() itself sets the active profile). A plain pick
// must never land here — that is what started VPN+TOR from a TOR-mode profile tap.
private fun HomeViewModel.activateAndConnectProfile(profileId: Long) {
    if (controlUiState.value.settings.traffic.mode == TrafficMode.PROXY) {
        connect(profileId)
    } else {
        requestManualConnectPermissionOrConnect(profileId)
    }
}

/**
 * Smart-profile protocol tap. On the live active profile, a switch to a different running option
 * asks B3 before applying; every other case keeps the existing select-and-maybe-reconnect flow
 * (idle probe, or the reconnect-offer carousel when the id can't be resolved to a live option).
 */
internal fun HomeViewModel.onSelectProfileProtocolOptionRequested(
    profileId: Long,
    optionId: String,
) {
    val state = controlUiState.value
    val liveActive =
        state.connection.isPrimaryConnectionRuntime() && state.connection.profileId == profileId
    val runningOptionId =
        state.connection.protocolOptionId
            ?: runningOptionIdFromProtocolHint(state.activeProfile, state.connection)
    if (liveActive && runningOptionId != null && runningOptionId != optionId) {
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: state.activeProfile
        val option = profile?.protocolOptionOrDefault(optionId)
        // Switching to a UDP protocol while TOR rides the live tunnel (VPN+TOR): TOR cannot run over
        // UDP, so surface P2 — dropping TOR and continuing as plain VPN — instead of the plain B3
        // reconnect. A TCP target keeps the whole chain and falls through to B3, which reconnects
        // from the persisted settings (TOR/I2P intact).
        if (option != null && option.protocolHint.isUdpTransport() && state.connection.torActive) {
            torTransitionPromptMutable.value =
                TorTransitionPrompt.DisableTorForUdpProtocol(
                    profileId = profileId,
                    protocolOptionId = optionId,
                    protocolName = option.displayName,
                )
            return
        }
        torTransitionPromptMutable.value =
            TorTransitionPrompt.SwitchProtocolWhileConnected(
                profileId = profileId,
                protocolOptionId = optionId,
                protocolName = option?.displayName.orEmpty(),
            )
        return
    }
    selectProtocolOptionAndMaybeReconnect(profileId, optionId)
}

/** Confirm ("да") for B3: persist the chosen protocol option and reconnect onto it. */
internal fun HomeViewModel.confirmSwitchProtocolWhileConnected(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
) {
    torTransitionPromptMutable.value = null
    clearProtocolSwitchRevert()
    clearRuntimeReconnectRequired()
    viewModelScope.launch {
        // selectProfileProtocolOption throws on an unknown/unselectable option (and on missing
        // insecure-TLS consent). Swallowing that and reconnecting anyway would silently bring the
        // tunnel back up on the OLD protocol with no error shown — surface it and keep the tunnel.
        val selected =
            runCatching {
                container.profileRepository.selectProfileProtocolOption(
                    prompt.profileId,
                    prompt.protocolOptionId,
                )
            }.onFailure { error ->
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        error,
                        R.string.profile_update_failed,
                    ),
                )
            }
        val updated = selected.getOrNull() ?: return@launch
        if (updated.isActive) {
            startupActiveProfileMutable.value = updated
        }
        requestReconnect(prompt.profileId)
    }
}
