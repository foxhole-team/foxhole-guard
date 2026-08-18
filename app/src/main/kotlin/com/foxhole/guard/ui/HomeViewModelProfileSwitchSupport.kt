package com.foxhole.guard.ui
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.isUdpTransport
import com.foxhole.guard.R
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.launch

internal fun HomeViewModel.onQuickSelectorSingleProfileSelected(profileId: Long) {
    applyProfileSelection(profileId)
}

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

internal fun HomeViewModel.confirmSwitchProfileWhileConnected(
    prompt: TorTransitionPrompt.SwitchProfileWhileConnected,
) {
    torTransitionPromptMutable.value = null
    activateAndConnectProfile(prompt.profileId)
}

private fun HomeViewModel.activateAndConnectProfile(profileId: Long) {
    if (controlUiState.value.settings.traffic.mode == TrafficMode.PROXY) {
        connect(profileId)
    } else {
        requestManualConnectPermissionOrConnect(profileId)
    }
}

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

internal fun HomeViewModel.confirmSwitchProtocolWhileConnected(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
) {
    torTransitionPromptMutable.value = null
    clearProtocolSwitchRevert()
    clearRuntimeReconnectRequired()
    viewModelScope.launch {
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
