package com.foxhole.beta.vpn

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.TrafficMode
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
                    currentVpnNetworkOrNull() == null -> {
                        markNotificationConnectivityOffline()
                        handleVpnNetworkLost(lostHandle = -1L, reason = "vpn_network_missing")
                    }
                    else -> updateHealthAfterProbe(runNotificationConnectivityProbe())
                }
                delay(
                    RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(
                        notificationConnectivityHealthState,
                        consecutiveNotificationHealthFailures,
                    ),
                )
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
    val androidValidatedVpnNetwork = currentVpnNetworkOrNull()?.let(::isVpnNetworkValidated) == true
    val healthAccepted = probeSucceeded || androidValidatedVpnNetwork
    RuntimeHealthMetrics.recordHealthProbe(
        owner = "vpn",
        success = healthAccepted,
        diagnosticsLogger = container.diagnosticsLogger,
    )
    if (healthAccepted) {
        if (!probeSucceeded && androidValidatedVpnNetwork) {
            container.diagnosticsLogger.record(
                "connection",
                "notification health probe failed but android validated vpn network is online",
            )
        }
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.ONLINE,
            resetFailures = true,
        )
    } else {
        consecutiveNotificationHealthFailures += 1
        if (consecutiveNotificationHealthFailures >= FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD) {
            publishHealthReconnectSnapshot(reason = "notification_health_failed")
            markNotificationConnectivityOffline()
            scheduleAutoReconnect(reason = "notification_health_failed")
        } else {
            updateNotificationConnectivityHealth(ConnectivityHealthState.CHECKING)
        }
    }
}

private fun FoxholeVpnService.publishHealthReconnectSnapshot(reason: String) {
    val session = activeSession ?: return
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.trafficMode != TrafficMode.TUNNEL || snapshot.state != ConnectionState.CONNECTED) {
        return
    }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "active tunnel health failed; reconnecting",
        "reason=$reason",
        "sessionId=${session.correlationId}",
    )
    FoxholeVpnRuntimeBridge.update(
        snapshot.copy(
            state = ConnectionState.RECONNECTING,
            message = getString(R.string.status_reconnecting),
        ),
    )
    updateNotification()
}
