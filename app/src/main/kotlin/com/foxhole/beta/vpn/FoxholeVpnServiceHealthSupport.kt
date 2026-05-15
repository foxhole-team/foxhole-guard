package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectivityHealthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun FoxholeVpnService.startNotificationHealthMonitoring() {
    stopNotificationHealthMonitoring()
    updateNotificationConnectivityHealth(
        state = ConnectivityHealthState.CHECKING,
        resetFailures = true,
        force = true,
    )
    notificationHealthJob =
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val session = activeSession
                val connectionState = FoxholeVpnRuntimeBridge.snapshot.value.state
                when {
                    session == null || connectionState !in FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_STATES -> {
                        updateNotificationConnectivityHealth(
                            state = ConnectivityHealthState.CHECKING,
                            resetFailures = true,
                        )
                    }
                    !defaultNetworkAvailable -> markNotificationConnectivityOffline()
                    else -> updateHealthAfterProbe(runNotificationConnectivityProbe())
                }
                delay(RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(notificationConnectivityHealthState))
            }
        }
}

internal fun FoxholeVpnService.stopNotificationHealthMonitoring() {
    notificationHealthJob?.cancel()
    notificationHealthJob = null
    consecutiveNotificationHealthFailures = 0
    notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
}

private fun FoxholeVpnService.updateHealthAfterProbe(probeSucceeded: Boolean) {
    RuntimeHealthMetrics.recordHealthProbe(
        owner = "vpn",
        success = probeSucceeded,
        diagnosticsLogger = container.diagnosticsLogger,
    )
    if (probeSucceeded) {
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.ONLINE,
            resetFailures = true,
        )
    } else {
        consecutiveNotificationHealthFailures += 1
        if (consecutiveNotificationHealthFailures >= FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD) {
            markNotificationConnectivityOffline()
            scheduleAutoReconnect(reason = "notification_health_failed")
        } else if (notificationConnectivityHealthState != ConnectivityHealthState.ONLINE) {
            updateNotificationConnectivityHealth(ConnectivityHealthState.CHECKING)
        }
    }
}
