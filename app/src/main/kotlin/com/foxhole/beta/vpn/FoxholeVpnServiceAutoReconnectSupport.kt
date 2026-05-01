package com.foxhole.beta.vpn

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap

private data class VpnAutoReconnectState(
    var job: Job? = null,
    var attempts: Int = 0,
)

private val vpnAutoReconnectStates = WeakHashMap<FoxholeVpnService, VpnAutoReconnectState>()

internal fun FoxholeVpnService.scheduleAutoReconnect(reason: String) {
    val session = activeSession
    val reconnectState = autoReconnectState()
    if (reconnectState.job?.isActive == true || session == null || !defaultNetworkAvailable) {
        return
    }
    val nextAttempt = reconnectState.attempts + 1
    val autoReconnectEnabled = container.settingsRepository.settings.value.connection.autoReconnect
    if (RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled, nextAttempt)) {
        scheduleAutoReconnectAttempt(reconnectState, session, reason, nextAttempt)
    } else if (autoReconnectEnabled && reconnectState.attempts == RuntimeAutoReconnectPolicy.MAX_ATTEMPTS) {
        reconnectState.attempts += 1
        container.diagnosticsLogger.record(
            "connection",
            "auto reconnect exhausted reason=$reason attempts=${RuntimeAutoReconnectPolicy.MAX_ATTEMPTS}",
        )
    }
}

internal fun FoxholeVpnService.resetAutoReconnectState() {
    cancelScheduledAutoReconnect(resetAttempts = true)
}

internal fun FoxholeVpnService.cancelScheduledAutoReconnect(resetAttempts: Boolean) {
    val reconnectState = autoReconnectState()
    reconnectState.job?.cancel()
    reconnectState.job = null
    if (resetAttempts) {
        reconnectState.attempts = 0
    }
}

private fun FoxholeVpnService.scheduleAutoReconnectAttempt(
    reconnectState: VpnAutoReconnectState,
    session: VpnSession,
    reason: String,
    nextAttempt: Int,
) {
    reconnectState.attempts = nextAttempt
    val delayMs = RuntimeAutoReconnectPolicy.backoffDelayMs(nextAttempt)
    container.diagnosticsLogger.recordStructured(
        "connection",
        "auto reconnect scheduled",
        "reason=$reason",
        "attempt=$nextAttempt",
        "delay_ms=$delayMs",
        "sessionId=${session.correlationId}",
    )
    reconnectState.job =
        scope.launch(Dispatchers.Default) {
            delay(delayMs)
            launchCommand {
                val current = activeSession
                if (current?.correlationId == session.correlationId) {
                    reconnectIfStillEnabled(session, reason, nextAttempt)
                }
            }
        }
}

private fun FoxholeVpnService.autoReconnectState(): VpnAutoReconnectState =
    synchronized(vpnAutoReconnectStates) {
        vpnAutoReconnectStates.getOrPut(this) { VpnAutoReconnectState() }
    }

private suspend fun FoxholeVpnService.reconnectIfStillEnabled(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    if (!container.settingsRepository.current().connection.autoReconnect) {
        container.diagnosticsLogger.record("connection", "auto reconnect skipped because setting is disabled")
        return
    }
    reconnectActiveRuntime(session, reason, attempt)
}

private suspend fun FoxholeVpnService.reconnectActiveRuntime(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    val previousVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle
    stopActiveRuntimeForReconnect(session = session, reason = reason, attempt = attempt)
    connect(
        profileId = session.profileId,
        commandStartId = 0,
        protocolOptionIdOverride = session.protocolOptionId,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
}

private suspend fun FoxholeVpnService.stopActiveRuntimeForReconnect(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    persistProfileTraffic(session, trafficSampler.sample())
    stopTrafficUpdates()
    stopGeoRefresh()
    stopNotificationHealthMonitoring()
    validationJob?.cancel()
    validationJob = null
    container.diagnosticsLogger.recordStructured(
        "connection",
        "runtime restarting",
        "reason=$reason",
        "attempt=$attempt",
        "sessionId=${session.correlationId}",
    )
    runtime.stop()
    activeSession = null
    container.connectionController.clearAppliedRuntime()
    FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
    FoxholeVpnRuntimeBridge.update(
        FoxholeVpnRuntimeBridge.snapshot.value.copy(
            state = ConnectionState.RECONNECTING,
            message = getString(R.string.status_reconnecting),
        ),
    )
    updateNotification()
}
