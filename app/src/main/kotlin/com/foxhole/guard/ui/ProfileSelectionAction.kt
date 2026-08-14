package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot

/** What picking a profile in a selector means. Picking is never starting. */
internal enum class ProfileSelectionAction {
    /** Remember the choice: activate the profile, start nothing. */
    REMEMBER,

    /** A live VPN is carrying another profile: raise the switch sheet and apply only on confirm. */
    CONFIRM_SWITCH,
}

/**
 * The single rule behind every profile pick, on the home screen and on the profiles screen alike.
 *
 * Picking a profile expresses "use this one next", not "connect now" — whatever the routing mode is.
 * With mode TOR that is the difference between remembering a choice and silently raising a VPN
 * (plus Tor on top of it) the user never asked for; with mode VPN / VPN+TOR it is the difference
 * between a selection and a connection. The ONLY case that touches the runtime is a change of
 * profile under a live VPN, and that one asks first.
 *
 * A Tor-only runtime is not a primary connection runtime, so it lands in
 * [ProfileSelectionAction.REMEMBER] like idle: nothing is torn down, nothing is raised.
 */
internal fun profileSelectionAction(
    connection: ConnectionSnapshot,
    profileId: Long,
): ProfileSelectionAction =
    if (connection.isPrimaryConnectionRuntime() && connection.profileId != profileId) {
        ProfileSelectionAction.CONFIRM_SWITCH
    } else {
        ProfileSelectionAction.REMEMBER
    }
