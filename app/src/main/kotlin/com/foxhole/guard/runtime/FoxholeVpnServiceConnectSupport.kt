package com.foxhole.guard.runtime

import android.app.Service.STOP_FOREGROUND_REMOVE
import android.net.Network
import android.os.SystemClock
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.I2pTunnelTransition
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.PrivateDnsState
import com.foxhole.core.runtime.RuntimeHealthMetrics
import com.foxhole.core.runtime.RuntimeIpRefreshReason
import com.foxhole.core.runtime.VpnHealthProbeTarget
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.isSupportedForTunnelMode
import com.foxhole.core.runtime.nativeForceStopOutcomeOrNull
import com.foxhole.core.runtime.runtimeStartTimeoutMsForSession
import com.foxhole.core.runtime.startFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.RUNTIME_START_TIMEOUT_MARKER
import com.foxhole.guard.core.data.getSession
import com.foxhole.guard.core.data.getTorOnlySession
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.diagnosticFailureLabel
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal suspend fun FoxholeVpnService.startRuntimeWithHealthMetrics(
    session: VpnSession,
    owner: String,
): Result<Unit> {
    val runtimeStartAtMs = SystemClock.elapsedRealtime()
    val runtimeStartTimeoutMs = runtimeStartTimeoutMsForSession(session)
    val result =
        runtime.startFailClosed(
            session = session,
            host = this,
            owner = owner,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
            timeoutMessage = RUNTIME_START_TIMEOUT_MARKER,
            timeoutMs = runtimeStartTimeoutMs,
        )
    handleNativeForceStopPoison(result) { outcome ->
        terminateProcessIfNativeForceStopPoisoned(
            forceStopOutcome = outcome,
            reason = "${owner}_start",
        )
    }
    RuntimeHealthMetrics.recordStart(
        owner = owner,
        success = result.isSuccess,
        elapsedMs = SystemClock.elapsedRealtime() - runtimeStartAtMs,
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    )
    recordRuntimeResourceSnapshot(
        event = if (result.isSuccess) "start_success" else "start_failure",
    )
    return result
}

internal fun FoxholeVpnService.recordRuntimeResourceSnapshot(
    event: String,
    async: Boolean = true,
) {
    val runtimeGeneration = runtimeSupervisor.currentGeneration()
    val commandQueue = runtimeSupervisor.queueSnapshot()
    val nativeSnapshot = runtimeInstanceStore.nativeSnapshot()
    val activeNetworkCallbacks = activeNetworkCallbackCount()
    if (async) {
        RuntimeHealthMetrics.recordResourceSnapshotAsync(
            scope = scope,
            dispatcher = Dispatchers.IO,
            owner = "vpn",
            event = event,
            runtimeGeneration = runtimeGeneration,
            commandQueue = commandQueue,
            nativeSnapshot = nativeSnapshot,
            activeNetworkCallbacks = activeNetworkCallbacks,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
        )
    } else {
        RuntimeHealthMetrics.recordResourceSnapshot(
            owner = "vpn",
            event = event,
            runtimeGeneration = runtimeGeneration,
            commandQueue = commandQueue,
            nativeSnapshot = nativeSnapshot,
            activeNetworkCallbacks = activeNetworkCallbacks,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
        )
    }
}

private fun FoxholeVpnService.activeNetworkCallbackCount(): Int =
    listOf(
        networkCallbackRegistered,
        vpnNetworkCallbackRegistered,
        defaultNetworkCallbackRegistered,
    ).count { registered -> registered }

private fun FoxholeVpnService.delegateConnectToForegroundService(
    profileId: Long,
    trafficMode: TrafficMode,
    commandStartId: Int,
    protocolOptionIdOverride: String?,
    previousVpnNetworkHandle: Long?,
) {
    FoxholeConnectionServiceContract.startForegroundService(
        context = this,
        mode = trafficMode,
        action = FoxholeConnectionServiceContract.ACTION_CONNECT,
        profileId = profileId,
        protocolOptionId = protocolOptionIdOverride,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopService(commandStartId)
}

private suspend fun FoxholeVpnService.validatePrivateDnsState(
    privateDnsState: PrivateDnsState?,
    commandStartId: Int,
): Boolean {
    val privateDnsMode = privateDnsState?.mode
    val supported = privateDnsMode?.isSupportedForTunnelMode() != false
    if (!supported) {
        container.diagnosticsLogger.record("dns", "unsupported android private dns state: $privateDnsState")
        fail(getString(R.string.error_private_dns_unknown_unsupported), commandStartId)
    } else if (privateDnsState != null) {
        container.diagnosticsLogger.record(
            "dns",
            "android private dns mode: ${privateDnsState.mode} hostname=${privateDnsState.hostname.orEmpty()}",
        )
    }
    return supported
}

private data class PendingTunnelValidation(
    val session: VpnSession,
    val expectedFreshVpnNetworkHandle: Long?,
    val expectedFreshVpnInterfaceName: String?,
)

private suspend fun FoxholeVpnService.handleRuntimeStartResult(
    result: Result<Unit>,
    session: VpnSession,
    trafficMode: TrafficMode,
    tcpReadinessTarget: VpnHealthProbeTarget?,
    previousVpnNetworkHandle: Long?,
    previousVpnInterfaceName: String?,
    commandStartId: Int,
    transitionGeneration: Long,
): PendingTunnelValidation? {
    if (!isCurrentRuntimeTransition(transitionGeneration, "runtime_start_result")) {
        return null
    }
    if (result.isSuccess) {
        container.connectionController.markRuntimeApplied(session, transitionGeneration)
        requestPostHandoffRuntimeNetworkReset(previousVpnNetworkHandle)
        requestTcpRuntimeNetworkReset(tcpReadinessTarget)
        when (trafficMode) {
            TrafficMode.TUNNEL -> {
                applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_UP)
                container.diagnosticsLogger.record(
                    "connection",
                    "runtime started, tunnel validation waits for interface handoff",
                )
                return PendingTunnelValidation(
                    session = session,
                    expectedFreshVpnNetworkHandle = previousVpnNetworkHandle,
                    expectedFreshVpnInterfaceName = previousVpnInterfaceName,
                )
            }
            TrafficMode.PROXY -> {
                container.diagnosticsLogger.record("connection", "proxy runtime started")
                onConnectionStarted(session, trafficMode)
            }
        }
    } else {
        val error = result.exceptionOrNull()
        container.i2pdManager.markCarrierUnavailable()
        container.diagnosticsLogger.recordFailure(
            "connection",
            "runtime start failed: ${diagnosticFailureLabel(error)}",
        )
        fail(
            userFacingErrorMessage(error, R.string.error_runtime_start_failed),
            commandStartId
        )
    }
    return null
}

private fun FoxholeVpnService.scheduleTunnelValidationAfterHandoff(
    pending: PendingTunnelValidation,
    transitionGeneration: Long,
) {
    if (!isCurrentRuntimeTransition(transitionGeneration, "interface_handoff_complete")) {
        return
    }
    container.diagnosticsLogger.record(
        "connection",
        "interface handoff complete, tunnel validation started",
    )
    scheduleValidation(
        session = pending.session,
        failOnFailure = true,
        expectedFreshVpnNetworkHandle = pending.expectedFreshVpnNetworkHandle,
        expectedFreshVpnInterfaceName = pending.expectedFreshVpnInterfaceName,
        onSuccess = { vpnNetwork -> onTunnelValidated(pending.session, vpnNetwork) },
    )
}

internal fun FoxholeVpnService.startI2pdReadinessProbe(
    settings: Settings,
    session: VpnSession,
    vpnNetwork: Network,
) {
    val expectedEndpointGeneration = session.i2pEndpointGeneration
    if (!settings.i2pRuntimeActive() || expectedEndpointGeneration == null) {
        return
    }
    val expectedVpnNetworkHandle = vpnNetwork.networkHandle
    val expectedRuntimeGeneration = runtimeSupervisor.currentGeneration()
    scope.launch(Dispatchers.IO) {
        fun probeOwned(): Boolean =
            isI2pReadinessProbeOwned(
                expectedRuntimeGeneration = expectedRuntimeGeneration,
                currentRuntimeGeneration = runtimeSupervisor.currentGeneration(),
                expectedEndpointGeneration = expectedEndpointGeneration,
                currentEndpointGeneration = container.i2pdManager.snapshot().endpoints?.generation,
                expectedVpnNetworkHandle = expectedVpnNetworkHandle,
                activeVpnNetworkHandle = activeVpnNetworkHandle,
                expectedRuntimeFingerprint = session.runtimeConfigFingerprint,
                appliedRuntimeFingerprint = container.connectionController.appliedRuntimeSignature.value,
                sessionOwned = ownsI2pCarrierSession(session),
            )

        val deadline = SystemClock.elapsedRealtime() + I2PD_COLD_READY_TOTAL_BUDGET_MS
        var outcome = I2pdReadinessOutcome.RETRY
        while (SystemClock.elapsedRealtime() < deadline && outcome == I2pdReadinessOutcome.RETRY) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            outcome =
                if (remainingMs <= 0L) {
                    I2pdReadinessOutcome.EXHAUSTED
                } else {
                    val attemptBudgetMs = remainingMs.coerceAtMost(I2PD_READY_ATTEMPT_TIMEOUT_MS)
                    awaitI2pdReadyWhileOwned(
                        timeoutMs = attemptBudgetMs,
                        probeOwned = ::probeOwned,
                        awaitReady = container.i2pdManager::awaitReady,
                    ).toI2pdReadinessOutcome()
                }
        }
        when (outcome) {
            I2pdReadinessOutcome.READY -> {
                val carrierCurrent =
                    isI2pCarrierConfirmationCurrent(
                        expectedEndpointGeneration = expectedEndpointGeneration,
                        currentEndpointGeneration = container.i2pdManager.snapshot().endpoints?.generation,
                        expectedVpnNetworkHandle = expectedVpnNetworkHandle,
                        activeVpnNetworkHandle = activeVpnNetworkHandle,
                        currentVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle,
                        expectedRuntimeFingerprint = session.runtimeConfigFingerprint,
                        appliedRuntimeFingerprint = container.connectionController.appliedRuntimeSignature.value,
                        sessionOwned = ownsI2pCarrierSession(session),
                    )
                if (!carrierCurrent || !container.i2pdManager.confirmCarrierReady(expectedEndpointGeneration)) {
                    container.diagnosticsLogger.recordStructured(
                        "i2pd",
                        "i2pd connected publication refused: carrier is not current",
                        "sessionId=${session.correlationId}",
                        "vpn_handle=$expectedVpnNetworkHandle",
                    )
                }
            }
            I2pdReadinessOutcome.RETRY,
            I2pdReadinessOutcome.EXHAUSTED,
            -> if (probeOwned()) {
                container.diagnosticsLogger.recordStructured(
                    "i2pd",
                    "i2pd readiness budget exhausted",
                    "sessionId=${session.correlationId}",
                    "budget_ms=$I2PD_COLD_READY_TOTAL_BUDGET_MS",
                )
            }
            I2pdReadinessOutcome.STALE -> Unit
        }
    }
}

private enum class I2pdReadinessOutcome {
    RETRY,
    READY,
    STALE,
    EXHAUSTED,
}

private fun Boolean?.toI2pdReadinessOutcome(): I2pdReadinessOutcome =
    when (this) {
        true -> I2pdReadinessOutcome.READY
        false -> I2pdReadinessOutcome.RETRY
        null -> I2pdReadinessOutcome.STALE
    }

private suspend fun awaitI2pdReadyWhileOwned(
    timeoutMs: Long,
    probeOwned: () -> Boolean,
    awaitReady: suspend (Long) -> Boolean,
): Boolean? =
    coroutineScope {
        val readiness = async { awaitReady(timeoutMs) }
        while (readiness.isActive) {
            if (!probeOwned()) {
                readiness.cancelAndJoin()
                return@coroutineScope null
            }
            delay(I2PD_READY_OWNERSHIP_POLL_MS)
        }
        readiness.await()
    }

private fun FoxholeVpnService.ownsI2pCarrierSession(session: VpnSession): Boolean {
    if (activeSession.matchesRuntimeValidationSession(session)) {
        return true
    }
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    return activeSession == null &&
        activeLocalGuardMode != null &&
        session.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        snapshot.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)
}

@Suppress("LongParameterList")
internal fun isI2pCarrierConfirmationCurrent(
    expectedEndpointGeneration: Long?,
    currentEndpointGeneration: Long?,
    expectedVpnNetworkHandle: Long,
    activeVpnNetworkHandle: Long?,
    currentVpnNetworkHandle: Long?,
    expectedRuntimeFingerprint: Int?,
    appliedRuntimeFingerprint: Int?,
    sessionOwned: Boolean,
): Boolean =
    sessionOwned &&
        expectedEndpointGeneration != null &&
        expectedEndpointGeneration == currentEndpointGeneration &&
        expectedVpnNetworkHandle == activeVpnNetworkHandle &&
        expectedVpnNetworkHandle == currentVpnNetworkHandle &&
        expectedRuntimeFingerprint != null &&
        expectedRuntimeFingerprint == appliedRuntimeFingerprint

@Suppress("LongParameterList")
internal fun isI2pReadinessProbeOwned(
    expectedRuntimeGeneration: Long,
    currentRuntimeGeneration: Long,
    expectedEndpointGeneration: Long,
    currentEndpointGeneration: Long?,
    expectedVpnNetworkHandle: Long,
    activeVpnNetworkHandle: Long?,
    expectedRuntimeFingerprint: Int?,
    appliedRuntimeFingerprint: Int?,
    sessionOwned: Boolean,
): Boolean =
    sessionOwned &&
        expectedRuntimeGeneration == currentRuntimeGeneration &&
        expectedEndpointGeneration == currentEndpointGeneration &&
        expectedVpnNetworkHandle == activeVpnNetworkHandle &&
        expectedRuntimeFingerprint != null &&
        expectedRuntimeFingerprint == appliedRuntimeFingerprint

internal const val I2PD_READY_ATTEMPT_TIMEOUT_MS = 60_000L
internal const val I2PD_COLD_READY_TOTAL_BUDGET_MS = 420_000L
private const val I2PD_READY_OWNERSHIP_POLL_MS = 250L

private suspend fun FoxholeVpnService.buildConnectSession(
    torOnlyConnect: Boolean,
    resolvedProfileId: Long,
    protocolOptionIdOverride: String?,
    privateDnsState: PrivateDnsState?,
    deferTorRoute: Boolean,
    protocolTestTrafficFreeze: Boolean,
    commandStartId: Int,
): VpnSession? =
    runCatching {
        if (torOnlyConnect) {
            container.profileRepository.getTorOnlySession(privateDnsState = privateDnsState)
        } else {
            container.profileRepository.getSession(
                profileId = resolvedProfileId,
                protocolOptionIdOverride = protocolOptionIdOverride,
                privateDnsState = privateDnsState,
                deferTorRoute = deferTorRoute,
                protocolTestTrafficFreeze = protocolTestTrafficFreeze,
            )
        }
    }.getOrElse {
        if (it is CancellationException) {
            throw it
        }

        container.diagnosticsLogger.recordFailure(
            "connection",
            "session build failed: ${diagnosticFailureLabel(it)}",
        )
        container.i2pdManager.markCarrierUnavailable()
        fail(userFacingErrorMessage(it, R.string.error_profile_invalid), commandStartId)
        null
    }

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.connect(
    profileId: Long,
    commandStartId: Int,
    protocolOptionIdOverride: String? = null,
    previousVpnNetworkHandle: Long? = null,
    subscriptionRefreshPrepared: Boolean = false,
    protocolTestTrafficFreeze: Boolean = false,
    replaceActiveTunnel: Boolean = false,
) {
    val start =
        resolveVpnStartOrReject(
            requestedProfileId = profileId,
            protocolOptionId = protocolOptionIdOverride,
            commandStartId = commandStartId,
            subscriptionRefreshPrepared = subscriptionRefreshPrepared,
            protocolTestTrafficFreeze = protocolTestTrafficFreeze,
        ) ?: return
    val settings = start.settings
    val connectProfileId = start.profileId
    val resolvedProtocolOptionId = start.protocolOptionId
    val torOnlyConnect = start.torOnly
    val trafficMode = if (torOnlyConnect) TrafficMode.TUNNEL else settings.traffic.mode
    if (trafficMode != TrafficMode.TUNNEL) {
        delegateConnectToForegroundService(
            profileId = connectProfileId,
            trafficMode = trafficMode,
            commandStartId = commandStartId,
            protocolOptionIdOverride = resolvedProtocolOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
        return
    }

    if (settings.expert.strictRoute && !isSystemVpnLockdownActive()) {
        container.diagnosticsLogger.record(
            "connection",
            "strict route without Android VPN lockdown: tunnel carries everything, no system-level kill switch",
        )
    }
    val privateDnsState = PrivateDnsSettings.currentState(this)
    if (!validatePrivateDnsState(privateDnsState, commandStartId)) {
        return
    }
    val transitionGeneration = beginRuntimeTransition("connect")

    val deferTorRoute =
        shouldDeferTorRouteForVpnFirstStartup(
            settings = settings,
            torOnlyConnect = torOnlyConnect,
            trafficMode = trafficMode,
        )
    applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_STARTING, settings)

    val handover =
        if (replaceActiveTunnel && activeSession != null) {
            retireActiveTunnelForTunnelHandover()
        } else {
            retireActiveLocalGuardForTunnelHandover()
        }
    if (!handover.ready) {
        fail(getString(R.string.error_runtime_start_failed), commandStartId)
        return
    }
    // Always closes the retired master TUN, including failure and cancellation.
    val establishNextInterface: suspend () -> PendingTunnelValidation? = {
        val validationExcludedVpnNetworkHandle =
            previousVpnNetworkHandle ?: handover.previousVpnNetworkHandle
        val validationExcludedVpnInterfaceName =
            handover.previousVpnInterfaceName.takeIf {
                handover.previousVpnNetworkHandle == validationExcludedVpnNetworkHandle
            }
        establishTunnelSession(
            torOnlyConnect = torOnlyConnect,
            connectProfileId = connectProfileId,
            protocolOptionIdOverride = resolvedProtocolOptionId,
            privateDnsState = privateDnsState,
            deferTorRoute = deferTorRoute,
            protocolTestTrafficFreeze = protocolTestTrafficFreeze,
            trafficMode = trafficMode,
            commandStartId = commandStartId,
            transitionGeneration = transitionGeneration,
            validationExcludedVpnNetworkHandle = validationExcludedVpnNetworkHandle,
            validationExcludedVpnInterfaceName = validationExcludedVpnInterfaceName,
        )
    }
    val pendingTunnelValidation =
        if (protocolTestTrafficFreeze) {
            establishProtocolTestInterfaceThenStopRetiredOnSuccess(
                reason = "protocol_test_handoff",
                establishNextInterface = establishNextInterface,
            )
        } else {
            establishNextInterfaceThenStopRetiredRuntimes(
                reason = "local_guard_handoff",
                establishNextInterface = establishNextInterface,
            )
        }
    pendingTunnelValidation?.let { pending ->
        scheduleTunnelValidationAfterHandoff(pending, transitionGeneration)
    }
}

@Suppress("ReturnCount", "LongParameterList")
private suspend fun FoxholeVpnService.establishTunnelSession(
    torOnlyConnect: Boolean,
    connectProfileId: Long,
    protocolOptionIdOverride: String?,
    privateDnsState: PrivateDnsState?,
    deferTorRoute: Boolean,
    protocolTestTrafficFreeze: Boolean,
    trafficMode: TrafficMode,
    commandStartId: Int,
    transitionGeneration: Long,
    validationExcludedVpnNetworkHandle: Long?,
    validationExcludedVpnInterfaceName: String?,
): PendingTunnelValidation? {
    val session =
        buildConnectSession(
            torOnlyConnect = torOnlyConnect,
            resolvedProfileId = connectProfileId,
            protocolOptionIdOverride = protocolOptionIdOverride,
            privateDnsState = privateDnsState,
            deferTorRoute = deferTorRoute,
            protocolTestTrafficFreeze = protocolTestTrafficFreeze,
            commandStartId = commandStartId,
        ) ?: return null
    currentCoroutineContext().ensureActive()
    if (!isCurrentRuntimeTransition(transitionGeneration, "connect_session_loaded")) {
        return null
    }
    rememberI2pRelayNetworkClass()

    pendingTorRouteUpgradeSessionId = if (deferTorRoute) session.correlationId else null

    renewBridgeWriter(trafficMode)
    activeSession = session
    activeLocalGuardMode = null
    container.diagnosticsLogger.recordStructured(
        "connection",
        "session started",
        "sessionId=${session.correlationId}",
        "mode=${trafficMode.name.lowercase()}",
    )
    val tcpReadinessTarget = tcpRuntimeReadinessTarget(session)
    val tcpReadiness = prepareTcpRuntimeReadiness(tcpReadinessTarget)
        .onFailure {
            container.i2pdManager.markCarrierUnavailable()
            fail(getString(R.string.error_tcp_runtime_readiness_failed), commandStartId)
            return null
        }
        .getOrNull()
    bridgeWriter.updateActiveServerPingTarget(
        activeServerPingTarget(session, tcpReadinessTarget, tcpReadiness),
    )
    bridgeWriter.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)
    bridgeWriter.update(
        ConnectionSnapshot(
            state = ConnectionState.CONNECTING,
            trafficMode = trafficMode,
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
            protocolOptionId = session.protocolOptionId,
            torActive = session.torActive,
            appliedTorRoute = session.appliedTorRoute,
            message = FoxholeVpnRuntimeBridge.snapshot.value.message,
            isSmartStartConnection = FoxholeVpnRuntimeBridge.snapshot.value.isSmartStartConnection,
        ),
    )
    updateNotification()
    if (trafficMode == TrafficMode.TUNNEL) {
        registerNetworkCallbackIfNeeded()
        registerVpnNetworkCallbackIfNeeded()
    }
    registerDefaultNetworkCallbackIfNeeded()
    acquireRuntimeWakeLock()
    startNotificationHealthMonitoring()
    startChildProcessWatchdog()
    if (!isI2pEndpointLeaseCurrent(session)) {
        container.i2pdManager.markCarrierUnavailable()
        container.diagnosticsLogger.record(
            "i2pd",
            "native start refused: assembled I2P endpoint lease is stale",
        )
        fail(getString(R.string.status_child_component_degraded, "I2P"), commandStartId)
        return null
    }
    val result = startRuntimeWithHealthMetrics(session = session, owner = "vpn")
    if (result.nativeForceStopOutcomeOrNull() != null) {
        return null
    }
    if (!currentCoroutineContext().isActive) {
        container.diagnosticsLogger.record("connection", "runtime start cancelled after native return")
        return null
    }
    return handleRuntimeStartResult(
        result = result,
        session = session,
        trafficMode = trafficMode,
        tcpReadinessTarget = tcpReadinessTarget,
        previousVpnNetworkHandle = validationExcludedVpnNetworkHandle,
        previousVpnInterfaceName = validationExcludedVpnInterfaceName,
        commandStartId = commandStartId,
        transitionGeneration = transitionGeneration,
    )
}
