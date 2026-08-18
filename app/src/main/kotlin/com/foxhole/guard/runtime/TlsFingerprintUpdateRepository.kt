package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TlsFingerprintUpdateRepository(
    private val client: TlsFingerprintUpdateClient,
    private val store: TlsFingerprintStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val manifestUrl: () -> String = { FOXHOLE_TLS_FINGERPRINT_MANIFEST_URL },
    private val handOverToCore: () -> TlsFingerprintTableInstall = {
        TlsFingerprintTableInstaller(
            documentInEffect = store::installedDocumentBytes,
            isDownloaded = { store.installedDocumentBytes() != null },
        ).install()
    },
) {
    fun installedGeneratedAt(): String? = store.installedGeneratedAt()

    suspend fun refreshNow(
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): TlsFingerprintUpdateResult {
        val endpoint = manifestUrl()
        val result =
            if (endpoint.isBlank()) {
                TlsFingerprintUpdateResult(
                    status = TlsFingerprintUpdateStatus.SKIPPED,
                    reason = "tls fingerprint feed endpoint not configured",
                )
            } else {
                client.update(endpoint, store, onPhase, onProgress)
            }
        when (result.status) {
            TlsFingerprintUpdateStatus.UPDATED -> {
                val handOver = withContext(Dispatchers.IO) { runCatching(handOverToCore).getOrNull() }
                diagnosticsLogger.record(
                    "tls-fingerprints",
                    "tls fingerprint tables updated profiles=${result.profileCount ?: 0} " +
                        "path=${result.installedPath.orEmpty()} " +
                        "core=${handOver?.let { install -> "replaced=${install.profilesReplaced}" } ?: "unavailable"}" +
                        handOver?.reason?.let { reason -> " reason=$reason" }.orEmpty(),
                )
            }

            TlsFingerprintUpdateStatus.UP_TO_DATE ->
                diagnosticsLogger.record(
                    "tls-fingerprints",
                    "tls fingerprint tables are already current",
                )

            TlsFingerprintUpdateStatus.SKIPPED ->
                diagnosticsLogger.record(
                    "tls-fingerprints",
                    "tls fingerprint update skipped: ${result.reason.orEmpty()}",
                )

            TlsFingerprintUpdateStatus.FAILED ->
                diagnosticsLogger.recordFailure(
                    "tls-fingerprints",
                    "tls fingerprint update failed, built-in tables kept " +
                        "retryable=${result.retryable} error=${result.reason.orEmpty()}",
                )
        }
        return result
    }
}
