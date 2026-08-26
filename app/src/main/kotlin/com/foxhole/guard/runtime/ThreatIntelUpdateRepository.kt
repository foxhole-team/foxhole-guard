package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink

class ThreatIntelUpdateRepository(
    private val client: ThreatIntelUpdateClient,
    private val store: ThreatIntelStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,

    private val manifestUrl: () -> String = { FOXHOLE_THREAT_INTEL_MANIFEST_URL },
) {
    fun installedGeneratedAt(): String? = store.installedGeneratedAt()

    suspend fun refreshNow(
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): ThreatIntelUpdateResult {
        val endpoint = manifestUrl()
        val result =
            if (endpoint.isBlank()) {
                ThreatIntelUpdateResult(
                    status = ThreatIntelUpdateStatus.SKIPPED,
                    reason = "threat intel feed endpoint not configured",
                )
            } else {
                client.update(endpoint, store, onPhase, onProgress)
            }
        when (result.status) {
            ThreatIntelUpdateStatus.UPDATED ->
                diagnosticsLogger.record(
                    "sentinel",
                    "threat intel updated entries=${result.entryCount ?: 0} path=${result.installedPath.orEmpty()}",
                )
            ThreatIntelUpdateStatus.SKIPPED ->
                diagnosticsLogger.record("sentinel", "threat intel update skipped: ${result.reason.orEmpty()}")
            ThreatIntelUpdateStatus.FAILED ->
                diagnosticsLogger.recordFailure(
                    "sentinel",
                    "threat intel update failed retryable=${result.retryable} error=${result.reason.orEmpty()}",
                )
        }
        return result
    }
}
