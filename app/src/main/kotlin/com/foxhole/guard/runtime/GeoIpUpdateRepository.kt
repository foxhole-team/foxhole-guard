package com.foxhole.guard.runtime

import com.foxhole.core.runtime.GeoIpDatabaseInfo
import com.foxhole.core.runtime.GeoIpDatabaseStore
import com.foxhole.core.runtime.RuntimeDiagnosticsSink

class GeoIpUpdateRepository(
    private val client: GeoIpUpdateClient,
    private val store: GeoIpDatabaseStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    fun currentInfo(): GeoIpDatabaseInfo = store.currentInfo()

    fun hasDownloadedDatabase(): Boolean = store.currentInfo().installed != null

    fun isUpdateCheckDue(nowMs: Long = nowProvider()): Boolean {
        val lastChecked = store.readLastCheckedAtMs() ?: return true
        return nowMs - lastChecked >= GEOIP_CHECK_INTERVAL_MS
    }

    suspend fun checkForUpdate(): Boolean? {
        val available = client.checkForUpdate(store)
        if (available != null) {
            store.markChecked()
        }
        return available
    }

    fun clearDownloaded(): Boolean {
        val cleared = store.clearOverride()
        diagnosticsLogger.record("ip", "geoip downloaded database cleared=$cleared")
        return cleared
    }

    suspend fun refreshNow(
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): GeoIpUpdateResult {
        val result = client.update(store, onPhase, onProgress)
        when (result.status) {
            GeoIpUpdateStatus.UPDATED -> {
                store.markChecked()
                diagnosticsLogger.record(
                    "ip",
                    "geoip database updated version=${result.metadata?.version.orEmpty()} " +
                        "ipv4=${result.metadata?.ipv4Ranges ?: 0} ipv6=${result.metadata?.ipv6Ranges ?: 0}",
                )
            }
            GeoIpUpdateStatus.UP_TO_DATE -> {
                store.markChecked()
                diagnosticsLogger.record("ip", "geoip database already up to date")
            }
            GeoIpUpdateStatus.FAILED ->
                diagnosticsLogger.recordFailure("ip", "geoip database update failed: ${result.reason.orEmpty()}")
        }
        return result
    }

    companion object {
        const val GEOIP_CHECK_INTERVAL_MS = 72L * 60L * 60L * 1000L
    }
}
