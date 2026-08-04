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
    // Dispatched, not inline: the egress probe blocks for seconds and must never delay the fast
    // telemetry tasks sharing the ticker. The interval lambda is re-read after each probe, so the
    // adaptive cadence (10s CHECKING / 60s ONLINE / exponential OFFLINE backoff) sees the state
    // the probe just produced — same ordering as the old probe-then-delay loop.
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

private fun FoxholeVpnService.updateHealthAfterProbe(probeSucceeded: Boolean) {
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
            container.diagnosticsLogger.record(
                "connection",
                "notification health probe failed despite android validated vpn network",
            )
        }
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
}
