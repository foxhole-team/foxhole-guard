package com.foxhole.core.runtime

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession

fun stoppedRuntimeSnapshot(
    session: VpnSession?,
    state: ConnectionState,
    trafficMode: TrafficMode,
    message: String? = null,
    reasonCode: AutoConnectReasonCode? = null,
    isSmartStartConnection: Boolean = false,
): ConnectionSnapshot {
    val profileSession = session?.takeIf(VpnSession::shouldRetainStoppedRuntimeIdentity)
    return ConnectionSnapshot(
        state = state,
        teardownPhase = null,
        trafficMode = trafficMode,
        profileId = profileSession?.profileId,
        profileName = profileSession?.profileName,
        protocolHint = profileSession?.protocolHint,
        protocolOptionId = profileSession?.protocolOptionId,
        message = message,
        reasonCode = reasonCode,
        isSmartStartConnection = isSmartStartConnection,
    )
}

fun stoppedRuntimeSnapshot(
    previous: ConnectionSnapshot,
    trafficMode: TrafficMode,
): ConnectionSnapshot {
    val retainIdentity = previous.shouldRetainStoppedRuntimeIdentity()
    return ConnectionSnapshot(
        trafficMode = trafficMode,
        profileId = previous.profileId.takeIf { retainIdentity },
        profileName = previous.profileName.takeIf { retainIdentity },
        protocolHint = previous.protocolHint.takeIf { retainIdentity },
        protocolOptionId = previous.protocolOptionId.takeIf { retainIdentity },
        isSmartStartConnection = previous.isSmartStartConnection && retainIdentity,
    )
}

private fun VpnSession.shouldRetainStoppedRuntimeIdentity(): Boolean =
    profileId != LOCAL_GUARD_PROFILE_ID &&
        profileId != TOR_ONLY_PROFILE_ID

private fun ConnectionSnapshot.shouldRetainStoppedRuntimeIdentity(): Boolean =
    profileId != null &&
        profileId != LOCAL_GUARD_PROFILE_ID &&
        profileId != TOR_ONLY_PROFILE_ID
