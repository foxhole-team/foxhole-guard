package com.foxhole.guard.runtime
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.network.scopedByNetworkRules
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeAutoReconnectPolicy
import com.foxhole.core.runtime.RuntimeHealthMetrics
import com.foxhole.core.runtime.reloadFailClosed
import com.foxhole.core.runtime.runtimeTransportProtocol
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.core.settings.accumulateProfileTraffic
import com.foxhole.guard.core.settings.recordSmartProfileProbeResult
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
    // job/attempts are touched by the IO health loop and Default-dispatcher commands at once;
    // the whole decide+mutate must be atomic, not just the WeakHashMap getOrPut.
    synchronized(reconnectState) {
        if (reconnectState.job?.isActive == true || session == null || !defaultNetworkAvailable) {
            return
        }
        val nextAttempt = reconnectState.attempts + 1
        val connectionSettings = container.settingsRepository.settings.value.connection
        val splitRecovery = splitVpnRecoveryRequired(session)
        val autoReconnectEnabled = connectionSettings.autoReconnect || splitRecovery
        val maxAttempts = RuntimeAutoReconnectPolicy.MAX_ATTEMPTS
        if (RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled, nextAttempt, maxAttempts)) {
            scheduleAutoReconnectAttempt(reconnectState, session, reason, nextAttempt)
        } else if (splitRecovery && reconnectState.attempts == maxAttempts) {
            schedulePersistentSplitRecovery(reconnectState, session, reason)
        } else if (autoReconnectEnabled && reconnectState.attempts == maxAttempts) {
            reconnectState.attempts += 1
            container.diagnosticsLogger.record(
                "connection",
                "auto reconnect exhausted reason=$reason attempts=$maxAttempts",
            )
            reconnectState.job =
                scope.launch(Dispatchers.Default) {
                    recordSmartStartProtocolDownAfterReconnectExhausted(session, reason, maxAttempts)
                }
        }
    }
}

// Callers hold synchronized(reconnectState).
private fun FoxholeVpnService.schedulePersistentSplitRecovery(
    reconnectState: VpnAutoReconnectState,
    session: VpnSession,
    reason: String,
) {
    reconnectState.attempts = 0
    container.diagnosticsLogger.recordStructured(
        "connection",
        "split VPN recovery continues",
        "reason=$reason",
        "delay_ms=$SPLIT_RECOVERY_PERIOD_MS",
        "sessionId=${session.correlationId}",
    )
    reconnectState.job =
        scope.launch(Dispatchers.Default) {
            delay(SPLIT_RECOVERY_PERIOD_MS)
            launchCommand("split_vpn_recovery:${session.profileId}") {
                if (activeSession?.correlationId == session.correlationId) {
                    reconnectIfStillEnabled(session, reason, attempt = 1)
                }
            }
        }
}

internal fun FoxholeVpnService.resetAutoReconnectState() {
    cancelScheduledAutoReconnect(resetAttempts = true)
}

internal fun FoxholeVpnService.cancelScheduledAutoReconnect(resetAttempts: Boolean) {
    val reconnectState = autoReconnectState()
    synchronized(reconnectState) {
        reconnectState.job?.cancel()
        reconnectState.job = null
        if (resetAttempts) {
            reconnectState.attempts = 0
        }
    }
}

// Callers hold synchronized(reconnectState).
private fun FoxholeVpnService.scheduleAutoReconnectAttempt(
    reconnectState: VpnAutoReconnectState,
    session: VpnSession,
    reason: String,
    nextAttempt: Int,
) {
    reconnectState.attempts = nextAttempt
    val delayMs = RuntimeAutoReconnectPolicy.jitteredBackoffDelayMs(nextAttempt)
    RuntimeHealthMetrics.recordReconnectScheduled(
        owner = "vpn",
        attempt = nextAttempt,
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    )
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
            launchCommand("auto_reconnect:${session.profileId}:$nextAttempt") {
                val current = activeSession
                if (current?.correlationId == session.correlationId) {
                    reconnectIfStillEnabled(session, reason, nextAttempt)
                }
            }
        }
}

private suspend fun FoxholeVpnService.recordSmartStartProtocolDownAfterReconnectExhausted(
    session: VpnSession,
    reason: String,
    exhaustedAttempts: Int,
) {
    recordSmartStartProtocolDown(
        session = session,
        reasonCode = smartStartReconnectReasonCode(reason),
        headline = "auto reconnect exhausted protocol marked down",
        detail = "reason=$reason attempts=$exhaustedAttempts",
    )
}

internal suspend fun FoxholeVpnService.recordSmartStartProtocolDown(
    session: VpnSession,
    reasonCode: AutoConnectReasonCode,
    headline: String,
    detail: String? = null,
) {
    val optionId = session.protocolOptionId?.trim()?.takeIf(String::isNotBlank)
    val profile = optionId?.let { container.profileRepository.getProfile(session.profileId) }
    val supportedOptionIds =
        profile
            ?.let(MultiProtocolProfileSupport::supportedOptions)
            .orEmpty()
            .map { option -> option.id }
            .toSet()
    if (optionId == null || optionId !in supportedOptionIds) {
        return
    }
    val recordedAt = System.currentTimeMillis()
    val settings = container.settingsRepository.current()
    val networkFingerprint =
        container.networkFingerprintProvider
            .currentFingerprint()
            ?.scopedByNetworkRules(settings.networkRules)
    container.settingsRepository.recordSmartProfileProbeResult(
        profileId = session.profileId,
        optionId = optionId,
        latencyMs = null,
        success = false,
        reasonCode = reasonCode,
        markAsLastKnownGood = false,
        networkFingerprint = networkFingerprint?.key,
        recordedAt = recordedAt,
        connectDurationMs = null,
        validatedAt = null,
        trafficObservedAt = null,
        countTowardOutcomeHistory = true,
    )
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        headline,
        "profile_id=${session.profileId}",
        "option=$optionId",
        "protocol=${session.protocolHint.name.lowercase()}",
        "outcome=down",
        "reason=${reasonCode.wireCode}",
        detail,
    )
}

private fun smartStartReconnectReasonCode(reason: String): AutoConnectReasonCode =
    when {
        reason.contains("validation", ignoreCase = true) ||
            reason.contains("health", ignoreCase = true) -> AutoConnectReasonCode.VALIDATION_TIMEOUT
        else -> AutoConnectReasonCode.CONNECT_ERROR
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
    val settings = container.settingsRepository.current()
    if (
        !settings.connection.autoReconnect &&
        !splitVpnRecoveryRequired(
            session = session,
            mode = settings.expert.perAppRoutingMode,
            selectedPackages = settings.expert.tunnelSelectedPackages(),
        )
    ) {
        container.diagnosticsLogger.record("connection", "auto reconnect skipped because setting is disabled")
        return
    }
    reconnectActiveRuntime(session, reason, attempt)
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun FoxholeVpnService.reconnectActiveRuntime(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    if (splitVpnRecoveryRequired(session)) {
        reloadSplitVpnRuntime(session, reason, attempt)
        return
    }
    val previousVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle
    try {
        stopActiveRuntimeForReconnect(session = session, reason = reason, attempt = attempt)
        connect(
            profileId = session.profileId,
            commandStartId = 0,
            protocolOptionIdOverride = session.protocolOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
    } catch (error: Throwable) {
        clearDetachedReconnectSnapshot("auto reconnect failed: ${error.javaClass.simpleName}")
        throw error
    }
}

private suspend fun FoxholeVpnService.reloadSplitVpnRuntime(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    val transitionGeneration = beginRuntimeTransition("split_vpn_recovery")
    publishSplitVpnUnavailable(session, reason, attempt)
    val result =
        runtime.reloadFailClosed(
            session = session,
            host = this,
            owner = "split_vpn_recovery",
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
        )
    if (!isCurrentRuntimeTransition(transitionGeneration, "split_vpn_recovery_result")) return
    if (result.isFailure) {
        container.diagnosticsLogger.recordFailure(
            "connection",
            "split VPN hot reload failed: ${result.exceptionOrNull()?.message.orEmpty()}",
        )
        scheduleAutoReconnect(reason = "split_vpn_reload_failed")
        return
    }
    activeSession = session
    container.connectionController.markRuntimeApplied(session, transitionGeneration)
    FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
    scheduleValidation(
        session = session,
        failOnFailure = false,
        onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
    )
}

internal fun FoxholeVpnService.publishSplitVpnUnavailable(
    session: VpnSession,
    reason: String,
    attempt: Int? = null,
) {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    bridgeWriter.update(
        snapshot.copy(
            state = ConnectionState.RECONNECTING,
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
            protocolOptionId = session.protocolOptionId,
            message = getString(R.string.split_vpn_temporarily_unavailable),
        ),
    )
    container.diagnosticsLogger.recordStructured(
        "connection",
        "split VPN unavailable; direct lane preserved",
        "reason=$reason",
        attempt?.let { "attempt=$it" },
        "sessionId=${session.correlationId}",
    )
    updateNotification()
}

internal fun FoxholeVpnService.splitVpnRecoveryRequired(session: VpnSession): Boolean {
    val expert = container.settingsRepository.settings.value.expert
    return splitVpnRecoveryRequired(session, expert.perAppRoutingMode, expert.tunnelSelectedPackages())
}

internal fun splitVpnRecoveryRequired(
    session: VpnSession?,
    mode: PerAppRoutingMode,
    selectedPackages: List<String>,
): Boolean =
    session != null &&
        session.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
        mode != PerAppRoutingMode.FULL_TUNNEL &&
        selectedPackages.any(String::isNotBlank)

private const val SPLIT_RECOVERY_PERIOD_MS = 30_000L

internal suspend fun FoxholeVpnService.persistProfileTrafficInternal(
    session: VpnSession,
    traffic: TrafficSnapshot,
) {
    if (!traffic.available && traffic.rxTotalBytes <= 0L && traffic.txTotalBytes <= 0L) {
        return
    }
    container.settingsRepository.accumulateProfileTraffic(
        profileId = session.profileId,
        profileName = session.profileName,
        protocolHint = session.protocolHint,
        protocolOptionId = session.protocolOptionId,
        transport = session.runtimeTransportProtocol(),
        rxBytes = traffic.rxTotalBytes,
        txBytes = traffic.txTotalBytes,
        updatedAt = System.currentTimeMillis(),
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
    stopChildProcessWatchdog()
    invalidateValidationEpoch("reconnect_stop")
    val messageRes = R.string.status_reconnecting
    container.diagnosticsLogger.recordStructured(
        "connection",
        "runtime restarting",
        "reason=$reason",
        "attempt=$attempt",
        "sessionId=${session.correlationId}",
    )
    stopRuntimeFailClosed(reason = "reconnect")
    activeSession = null
    activeVpnNetworkHandle = null
    container.connectionController.clearAppliedRuntime()
    bridgeWriter.updateTraffic(trafficSampler.reset())
    bridgeWriter.update(
        FoxholeVpnRuntimeBridge.snapshot.value.copy(
            state = ConnectionState.RECONNECTING,
            message = getString(messageRes),
        ),
    )
    updateNotification()
}

private fun FoxholeVpnService.clearDetachedReconnectSnapshot(reason: String) {
    val currentSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (activeSession != null || currentSnapshot.state != ConnectionState.RECONNECTING) {
        return
    }
    container.diagnosticsLogger.record("connection", "clearing detached vpn reconnect snapshot: $reason")
    bridgeWriter.clearTransientState()
    bridgeWriter.update(
        ConnectionSnapshot(
            trafficMode = container.settingsRepository.settings.value.traffic.mode,
        ),
    )
    removeForegroundNotification()
}
