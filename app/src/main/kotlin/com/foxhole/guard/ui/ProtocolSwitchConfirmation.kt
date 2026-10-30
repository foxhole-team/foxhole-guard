package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import kotlinx.coroutines.flow.MutableStateFlow

internal fun protocolOptionSelectionAction(
    connection: ConnectionSnapshot,
    profile: Profile?,
    optionId: String,
): ProtocolOptionSelectionAction {
    val currentProfile = profile ?: return ProtocolOptionSelectionAction.IGNORE
    val option = currentProfile.protocolOptions.firstOrNull { it.id == optionId && it.enabled }
        ?: return ProtocolOptionSelectionAction.IGNORE
    val selectedId = currentProfile.selectedProtocolOptionId
        ?: currentProfile.protocolOptions.firstOrNull(ProfileProtocolOption::isSelected)?.id
    val active = connection.isPrimaryConnectionRuntime() && connection.profileId == currentProfile.id
    val currentId = if (active) {
        connection.protocolOptionId ?: runningOptionIdFromProtocolHint(currentProfile, connection) ?: selectedId
    } else {
        selectedId
    }
    return when {
        currentId == option.id -> ProtocolOptionSelectionAction.IGNORE
        active -> ProtocolOptionSelectionAction.CONFIRM
        else -> ProtocolOptionSelectionAction.SELECT
    }
}

internal fun protocolSwitchPrompt(
    profile: Profile,
    optionId: String,
    connection: ConnectionSnapshot,
): TorTransitionPrompt.SwitchProtocolWhileConnected = TorTransitionPrompt.SwitchProtocolWhileConnected(
    profileId = profile.id,
    protocolOptionId = optionId,
    protocolName = profile.protocolOptions.firstOrNull { it.id == optionId }?.displayName.orEmpty(),
    expectedProfile = profile,
    expectedConnection = connection,
)

internal fun protocolSwitchConfirmationIsCurrent(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
    connection: ConnectionSnapshot,
    profile: Profile?,
): Boolean = prompt.expectedConnection == connection && prompt.expectedProfile == profile && profile != null &&
    protocolOptionSelectionAction(connection, profile, prompt.protocolOptionId) == ProtocolOptionSelectionAction.CONFIRM

internal fun MutableStateFlow<TorTransitionPrompt?>.consumeProtocolSwitchConfirmation(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
): Boolean = compareAndSet(prompt, null)

internal suspend fun applyConfirmedProtocolSwitch(
    prompt: TorTransitionPrompt.SwitchProtocolWhileConnected,
    currentConnection: () -> ConnectionSnapshot,
    readProfile: suspend () -> Profile?,
    select: suspend () -> Profile,
    onCommitted: (Profile) -> Unit,
) {
    val profile = readProfile()
    if (!protocolSwitchConfirmationIsCurrent(prompt, currentConnection(), profile)) return
    val updated = select()
    val selectionCommitted = updated.isActive && updated.id == prompt.profileId &&
        updated.selectedProtocolOptionId == prompt.protocolOptionId
    if (selectionCommitted && prompt.expectedConnection == currentConnection()) {
        onCommitted(updated)
    }
}
