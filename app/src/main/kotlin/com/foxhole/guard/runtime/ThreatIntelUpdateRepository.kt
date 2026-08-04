package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink

/**
 * Orchestrates the signed SENTINEL threat-intel refresh, mirroring [DnsFilterUpdateRepository].
 *
 * Ready-to-activate scaffolding: the refresh is gated on a configured manifest URL, so while
 * [FOXHOLE_THREAT_INTEL_MANIFEST_URL] is blank it skips cleanly and only the bundled seed is used.
 * Set the URL (and the pinned key) to bring the remote feed online without any further wiring.
 */
class ThreatIntelUpdateRepository(
    private val client: ThreatIntelUpdateClient,
    private val store: ThreatIntelStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val manifestUrl: String = FOXHOLE_THREAT_INTEL_MANIFEST_URL,
) {
    suspend fun refreshNow(): ThreatIntelUpdateResult {
        val result =
            if (manifestUrl.isBlank()) {
                ThreatIntelUpdateResult(
                    status = ThreatIntelUpdateStatus.SKIPPED,
                    reason = "threat intel feed endpoint not configured",
                )
            } else {
                client.update(manifestUrl, store)
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
                diagnosticsLogger.record(
                    "sentinel",
                    "threat intel update failed retryable=${result.retryable} error=${result.reason.orEmpty()}",
                )
        }
        return result
    }
}
