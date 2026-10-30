package com.foxhole.guard

import com.foxhole.guard.core.sentinel.sentinelAttempt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun FoxholeApplication.startSentinelStaticPolicySync() {
    appScope.launch {
        var automaticWasEnabled = false
        appGraph.settingsRepository.settings
            .map { it.anomaly.enabled to it.anomaly.apkScan }
            .distinctUntilChanged()
            .collect { (enabled, scan) ->
                val automaticEnabled = enabled && scan.dexEnabled && scan.automaticNewUpdatedApps
                sentinelAttempt {
                    appGraph.sentinelScanGateway.refreshPolicy()
                    if (automaticWasEnabled && !automaticEnabled) appGraph.sentinelScanGateway.clearAutomaticQueue()
                }.onFailure {
                    appGraph.diagnosticsLogger.recordFailure("sentinel", "Static scanner policy update unavailable")
                }
                automaticWasEnabled = automaticEnabled
            }
    }
}
