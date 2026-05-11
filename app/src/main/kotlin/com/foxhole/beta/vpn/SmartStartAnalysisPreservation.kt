package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot

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
