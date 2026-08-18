package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot

internal enum class ProfileSelectionAction {
    REMEMBER,

    CONFIRM_SWITCH,
}

internal fun profileSelectionAction(
    connection: ConnectionSnapshot,
    profileId: Long,
): ProfileSelectionAction =
    if (connection.isPrimaryConnectionRuntime() && connection.profileId != profileId) {
        ProfileSelectionAction.CONFIRM_SWITCH
    } else {
        ProfileSelectionAction.REMEMBER
    }
