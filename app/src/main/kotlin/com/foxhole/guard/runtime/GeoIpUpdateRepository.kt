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

    /**
     * Whether the user has downloaded the geo database. The traffic map is gated on this —
     * enabling the map with no downloaded DB is what triggers the download prompt.
     */
    fun hasDownloadedDatabase(): Boolean = store.currentInfo().installed != null

    /**
     * Whether the periodic freshness probe is due (>= [GEOIP_CHECK_INTERVAL_MS] since the
     * last successful check, or never checked). The new UI's map worker calls this instead of
     * probing GitHub on every open; a `true` from [checkForUpdate] surfaces the "update database"
     * button at the bottom of the map.
     */
    fun isUpdateCheckDue(nowMs: Long = nowProvider()): Boolean {
        val lastChecked = store.readLastCheckedAtMs() ?: return true
        return nowMs - lastChecked >= GEOIP_CHECK_INTERVAL_MS
    }

    /** Check-only probe for the "updates available" indicator; null when the source is unreachable. */
    suspend fun checkForUpdate(): Boolean? {
        val available = client.checkForUpdate(store)
        if (available != null) {
            store.markChecked()
        }
        return available
    }

    /** Deletes the downloaded database override; the bundled dataset takes over again. */
    fun clearDownloaded(): Boolean {
        val cleared = store.clearOverride()
        diagnosticsLogger.record("ip", "geoip downloaded database cleared=$cleared")
        return cleared
    }

    suspend fun refreshNow(onPhase: (RemoteUpdatePhase) -> Unit = {}): GeoIpUpdateResult {
        val result = client.update(store, onPhase)
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
                diagnosticsLogger.record("ip", "geoip database update failed: ${result.reason.orEmpty()}")
        }
        return result
    }

    companion object {
        // Minimum spacing between automatic freshness probes. 72h — the geo dataset moves
        // slowly, so a rarer cadence is fine; the manual "check now" path ignores this gate.
        const val GEOIP_CHECK_INTERVAL_MS = 72L * 60L * 60L * 1000L
    }
}
