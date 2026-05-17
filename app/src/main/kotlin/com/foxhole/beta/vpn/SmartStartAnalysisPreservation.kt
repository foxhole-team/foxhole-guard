package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.TrafficMode

internal data class SmartStartAnalysisPreservation(
    val message: String?,
    val isSmartStartConnection: Boolean,
)

internal fun resolveSmartStartAnalysisPreservation(
    previousSnapshot: ConnectionSnapshot,
    analysisStatus: String,
    disconnectMessage: String?,
    preserveSmartStartAnalysis: Boolean,
): SmartStartAnalysisPreservation {
    val shouldPreserve =
        preserveSmartStartAnalysis &&
            disconnectMessage == null &&
            previousSnapshot.isSmartStartConnection &&
            previousSnapshot.message == analysisStatus
    return SmartStartAnalysisPreservation(
        message = analysisStatus.takeIf { shouldPreserve },
        isSmartStartConnection = shouldPreserve,
    )
}

internal fun shouldPublishAppOwnedIpInfoForSnapshot(
    snapshot: ConnectionSnapshot,
    analysisStatus: String,
): Boolean {
    if (snapshot.isSmartStartConnection || snapshot.message == analysisStatus) {
        return false
    }
    return snapshot.state !in ACTIVE_CONNECTION_STATES ||
        snapshot.trafficMode != TrafficMode.TUNNEL ||
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
}
