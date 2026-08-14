package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink

/**
 * Orchestrates the signed SENTINEL threat-intel refresh, mirroring [DnsFilterUpdateRepository].
 *
 * The production endpoint is configured by [FOXHOLE_THREAT_INTEL_MANIFEST_URL]. An injected blank
 * endpoint deliberately disables remote refresh and leaves the bundled seed active.
 */
class ThreatIntelUpdateRepository(
    private val client: ThreatIntelUpdateClient,
    private val store: ThreatIntelStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    // Read per refresh — see TorBridgeUpdateClient: the repository may be redirected while this
    // object is already in the graph.
    private val manifestUrl: () -> String = { FOXHOLE_THREAT_INTEL_MANIFEST_URL },
) {
    /** `generated_at` of the installed feed, or null while only the bundled seed is present. */
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
