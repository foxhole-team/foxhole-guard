package com.foxhole.guard.ui
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.isUdpTransport
import com.foxhole.guard.R
import com.foxhole.guard.core.data.requireProfile
import com.foxhole.guard.runtime.runCatchingUnlessCancelled
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
    val connection = container.connectionController.snapshot.value
    val profile = state.profiles.firstOrNull { it.id == profileId }
        ?: state.activeProfile?.takeIf { it.id == profileId }
    when (protocolOptionSelectionAction(connection, profile, optionId)) {
        ProtocolOptionSelectionAction.IGNORE -> Unit
        ProtocolOptionSelectionAction.SELECT -> selectProtocolOptionAndMaybeReconnect(profileId, optionId)
        ProtocolOptionSelectionAction.CONFIRM -> {
            val selectedProfile = profile ?: return
            val option = selectedProfile.protocolOptions.first { it.id == optionId }
            torTransitionPromptMutable.value = if (option.protocolHint.isUdpTransport() && connection.torActive) {
                TorTransitionPrompt.DisableTorForUdpProtocol(
                    profileId = profileId,
                    protocolOptionId = optionId,
                    protocolName = option.displayName,
                )
            } else {
                protocolSwitchPrompt(selectedProfile, optionId, connection)
            }
        }
    }
}

internal fun HomeViewModel.confirmSwitchProtocolWhileConnected(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
) {
    if (!torTransitionPromptMutable.consumeProtocolSwitchConfirmation(prompt)) return
    viewModelScope.launch {
        runCatchingUnlessCancelled {
            val repository = container.profileRepository
            repository.withProfileMutation { owner ->
                owner.secrets(repository.secretMutationMutex) {
                    applyConfirmedProtocolSwitch(
                        prompt = prompt,
                        currentConnection = { container.connectionController.snapshot.value },
                        readProfile = { repository.requireProfile(prompt.profileId) },
                        select = {
                            repository.selectProfileProtocolOptionLocked(
                                prompt.profileId,
                                prompt.protocolOptionId,
                                owner,
                            )
                        },
                        onCommitted = { updated ->
                            clearProtocolSwitchRevert()
                            clearRuntimeReconnectRequired()
                            startupActiveProfileMutable.value = updated
                            requestReconnect(prompt.profileId)
                        },
                    )
                }
            }
        }.onFailure { error ->
            emitError(
                getApplication<Application>().userFacingErrorMessage(error, R.string.profile_update_failed),
            )
        }
    }
}

internal fun HomeViewModel.dismissProtocolSwitchConfirmation(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
) {
    torTransitionPromptMutable.consumeProtocolSwitchConfirmation(prompt)
}
