package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ConnectivityHealthState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeHealthMetrics
import com.foxhole.core.runtime.RuntimeUpdatePolicy
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import kotlinx.coroutines.Dispatchers

internal fun FoxholeVpnService.startNotificationHealthMonitoring() {
    stopNotificationHealthMonitoring()
    updateNotificationConnectivityHealth(
        state = ConnectivityHealthState.CHECKING,
        resetFailures = true,
        force = true,
    )

    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_NOTIFICATION_HEALTH,
        fireImmediately = true,
        intervalMs = {
            RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(
                notificationConnectivityHealthState,
                consecutiveNotificationHealthFailures,
            )
        },
        runOn = Dispatchers.IO,
    ) {
        val session = activeSession
        val connectionState = FoxholeVpnRuntimeBridge.snapshot.value.state
        when {
            notificationHealthTransitionInFlight(
                validationActive = validationJob?.isActive == true,
                runtimeCommandRunning = runtimeSupervisor.queueSnapshot().running,
            ) -> {
                updateNotificationConnectivityHealth(
                    state = ConnectivityHealthState.CHECKING,
                    resetFailures = true,
                )
            }
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
    }
}

internal fun FoxholeVpnService.stopNotificationHealthMonitoring() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_NOTIFICATION_HEALTH)
    consecutiveNotificationHealthFailures = 0
    notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
}

internal fun notificationHealthTransitionInFlight(
    validationActive: Boolean,
    runtimeCommandRunning: Boolean,
): Boolean = validationActive || runtimeCommandRunning

private fun FoxholeVpnService.updateHealthAfterProbe(probeSucceeded: Boolean) {
    if (
        notificationHealthTransitionInFlight(
            validationActive = validationJob?.isActive == true,
            runtimeCommandRunning = runtimeSupervisor.queueSnapshot().running,
        )
    ) {
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.CHECKING,
            resetFailures = true,
        )
        return
    }
    val androidValidatedVpnNetwork = currentVpnNetworkOrNull()?.let(::isVpnNetworkValidated) == true
    val healthAccepted = probeSucceeded
    RuntimeHealthMetrics.recordHealthProbe(
        owner = "vpn",
        success = healthAccepted,
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    )
    if (healthAccepted) {
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.ONLINE,
            resetFailures = true,
        )
    } else {
        if (androidValidatedVpnNetwork) {
            container.diagnosticsLogger.recordFailure(
                "connection",
                "notification health probe failed despite android validated vpn network",
            )
        }
        consecutiveNotificationHealthFailures += 1
        if (consecutiveNotificationHealthFailures >= FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD) {
            if (publishHealthReconnectSnapshot(reason = "notification_health_failed")) {
                markNotificationConnectivityOffline()
                scheduleAutoReconnect(reason = "notification_health_failed")
            } else {
                updateNotificationConnectivityHealth(
                    state = ConnectivityHealthState.CHECKING,
                    resetFailures = true,
                )
            }
        } else {
            updateNotificationConnectivityHealth(ConnectivityHealthState.CHECKING)
        }
    }
}

private fun FoxholeVpnService.publishHealthReconnectSnapshot(reason: String): Boolean {
    val session = activeSession ?: return false
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.trafficMode != TrafficMode.TUNNEL || snapshot.state != ConnectionState.CONNECTED) {
        return false
    }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "active tunnel health failed; reconnecting",
        "reason=$reason",
        "sessionId=${session.correlationId}",
    )
    if (splitVpnRecoveryRequired(session)) {
        publishSplitVpnUnavailable(session = session, reason = reason)
    } else {
        bridgeWriter.update(
            snapshot.copy(
                state = ConnectionState.RECONNECTING,
                message = getString(R.string.status_reconnecting),
            ),
        )
        updateNotification()
    }
    return true
}
