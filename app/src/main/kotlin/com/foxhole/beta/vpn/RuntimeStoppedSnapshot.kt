package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession

internal fun stoppedRuntimeSnapshot(
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

internal fun stoppedRuntimeSnapshot(
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
    profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

private fun ConnectionSnapshot.shouldRetainStoppedRuntimeIdentity(): Boolean =
    profileId != null &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID
