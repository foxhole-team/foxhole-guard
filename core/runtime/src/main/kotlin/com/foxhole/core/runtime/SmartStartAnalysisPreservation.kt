package com.foxhole.core.runtime
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TrafficMode

data class SmartStartAnalysisPreservation(
    val message: String?,
    val isSmartStartConnection: Boolean,
)

fun resolveSmartStartAnalysisPreservation(
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

fun shouldPublishAppOwnedIpInfoForSnapshot(
    snapshot: ConnectionSnapshot,
    analysisStatus: String,
): Boolean {
    if (snapshot.isSmartStartConnection || snapshot.message == analysisStatus) {
        return false
    }
    return snapshot.state !in ACTIVE_CONNECTION_STATES ||
        snapshot.trafficMode != TrafficMode.TUNNEL ||
        snapshot.profileId == LOCAL_GUARD_PROFILE_ID
}
