package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.TorBridgeStore
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.markTorBridgesChecked
import com.foxhole.guard.core.settings.markTorBridgesUpdated

class TorBridgeUpdateRepository(
    private val settingsRepository: SettingsRepository,
    private val client: TorBridgeUpdateClient,
    private val store: TorBridgeStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    /** Deletes the downloaded bridge list; the bundled list takes over on the next Tor start. */
    fun clearDownloaded(): Boolean {
        val cleared = store.clearOverride()
        diagnosticsLogger.record("tor", "downloaded bridge list cleared=$cleared")
        return cleared
    }

    suspend fun refreshNow(
        useFoxholeSourceOverride: Boolean? = null,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): TorBridgeUpdateResult {
        val useFoxholeSource =
            useFoxholeSourceOverride ?: settingsRepository.current().privacyRoute.bridgesUseFoxholeSource
        val result = client.update(store, useFoxholeSource, onPhase, onProgress)
        when (result.status) {
            TorBridgeUpdateStatus.UPDATED -> {
                settingsRepository.markTorBridgesUpdated()
                diagnosticsLogger.record(
                    "tor",
                    "bridge list updated source=${result.metadata?.source.orEmpty()} count=${result.metadata?.bridgeCount ?: 0}",
                )
            }
            TorBridgeUpdateStatus.UP_TO_DATE -> {
                settingsRepository.markTorBridgesChecked(success = true)
                diagnosticsLogger.record("tor", "bridge list already up to date")
            }
            TorBridgeUpdateStatus.FAILED -> {
                settingsRepository.markTorBridgesChecked(success = false)
                diagnosticsLogger.recordFailure("tor", "bridge list update failed: ${result.reason.orEmpty()}")
            }
        }
        return result
    }
}
