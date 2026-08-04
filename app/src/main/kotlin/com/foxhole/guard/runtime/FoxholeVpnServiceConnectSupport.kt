package com.foxhole.guard.runtime

import android.app.Service.STOP_FOREGROUND_REMOVE
import android.os.SystemClock
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.PrivateDnsState
import com.foxhole.core.runtime.RuntimeHealthMetrics
import com.foxhole.core.runtime.RuntimeIpRefreshReason
import com.foxhole.core.runtime.VpnHealthProbeTarget
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.isSupportedForTunnelMode
import com.foxhole.core.runtime.runtimeStartTimeoutMsForSession
import com.foxhole.core.runtime.startFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.core.data.getSession
import com.foxhole.guard.core.data.getTorOnlySession
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.diagnosticFailureLabel
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session connect orchestration for [FoxholeVpnService]: starting the runtime with health metrics,
 * private-DNS validation, foreground-service handoff, and the main connect() entry point. Extracted
 * from the service body in the Phase B split by responsibility.
 */

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
            timeoutMessage = getString(R.string.error_runtime_start_timeout),
            timeoutMs = runtimeStartTimeoutMs,
        )
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

private suspend fun FoxholeVpnService.handleRuntimeStartResult(
    result: Result<Unit>,
    session: VpnSession,
    trafficMode: TrafficMode,
    tcpReadinessTarget: VpnHealthProbeTarget?,
    previousVpnNetworkHandle: Long?,
    commandStartId: Int,
    transitionGeneration: Long,
) {
    if (!isCurrentRuntimeTransition(transitionGeneration, "runtime_start_result")) {
        return
    }
    if (result.isSuccess) {
        container.connectionController.markCurrentRuntimeApplied()
        requestPostHandoffRuntimeNetworkReset(previousVpnNetworkHandle)
        requestTcpRuntimeNetworkReset(tcpReadinessTarget)
        when (trafficMode) {
            TrafficMode.TUNNEL -> {
                container.diagnosticsLogger.record("connection", "runtime started, tunnel validation required")
                scheduleValidation(
                    session = session,
                    failOnFailure = true,
                    expectedFreshVpnNetworkHandle = previousVpnNetworkHandle,
                    onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
                )
            }
            TrafficMode.PROXY -> {
                container.diagnosticsLogger.record("connection", "proxy runtime started")
                onConnectionStarted(session, trafficMode)
            }
        }
    } else {
        val error = result.exceptionOrNull()
        fail(
            userFacingErrorMessage(error, R.string.error_runtime_missing),
            commandStartId
        )
    }
}

/**
 * BUG 2 (2d): `I2pNetworkPhase.CONNECTED` has exactly one writer — the authenticated SOCKS probe
 * inside `I2pdProcessManager.awaitReady()` — and nothing in production ever called it, so the I2P
 * panel could never leave "starting" no matter how healthy i2pd was.
 *
 * Deliberately launched on the service scope instead of awaited inline: readiness of the I2P lane
 * is NOT a precondition of the tunnel (a failed i2pd start does not fail the session either, see
 * ProfileSessionFactory.startI2pProxyPortOrNull), so the connect path must not spend a single
 * millisecond waiting for it. The probe cancels with the scope, and `awaitReady` returns false at
 * once when no i2pd process is published.
 */
private fun FoxholeVpnService.startI2pdReadinessProbe(settings: Settings) {
    if (!settings.i2pRuntimeActive()) {
        return
    }
    // The probe negotiates a real SOCKS session against the loopback proxy: blocking IO.
    scope.launch(Dispatchers.IO) {
        container.i2pdManager.awaitReady(I2PD_READY_TIMEOUT_MS)
    }
}

// The SOCKS listener is up within seconds of the child starting; this only has to outlast a cold
// start on a slow device. Sized like TOR_VALIDATION_READY_TIMEOUT_MS's role for Tor — generous
// because nothing waits on it, and a timeout only journals "i2pd proxy wait failed".
internal const val I2PD_READY_TIMEOUT_MS = 60_000L

// Builds the session for connect(); null means the failure was already published via fail().
// Cancellation is rethrown so a user stop never reads as an error.
private suspend fun FoxholeVpnService.buildConnectSession(
    torOnlyConnect: Boolean,
    resolvedProfileId: Long,
    protocolOptionIdOverride: String?,
    privateDnsState: PrivateDnsState?,
    deferTorRoute: Boolean,
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
            )
        }
    }.getOrElse {
        // A user stop/kill preempting this connect cancels the command mid-session-build; that is
        // not a profile failure. Rethrow so the actor records a plain cancellation instead of
        // fail() publishing a red "StandaloneCoroutine was cancelled" error state right after the
        // user pressed Stop.
        if (it is CancellationException) {
            throw it
        }
        // The localized message is written for the person holding the phone and says the same
        // thing for every cause. Without this line the journal recorded that sentence and nothing
        // else, so a profile rejected over one unsupported field looked identical to a corrupt
        // one — and neither could be told apart from a decryption failure.
        container.diagnosticsLogger.record(
            "connection",
            "session build failed: ${diagnosticFailureLabel(it)}",
        )
        fail(userFacingErrorMessage(it, R.string.error_profile_invalid), commandStartId)
        null
    }

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.connect(
    profileId: Long,
    commandStartId: Int,
    protocolOptionIdOverride: String? = null,
    previousVpnNetworkHandle: Long? = null,
) {
    val start = resolveVpnStartOrReject(profileId, protocolOptionIdOverride, commandStartId) ?: return
    val settings = start.settings
    val connectProfileId = start.profileId
    val torOnlyConnect = start.torOnly
    val trafficMode = if (torOnlyConnect) TrafficMode.TUNNEL else settings.traffic.mode
    if (trafficMode != TrafficMode.TUNNEL) {
        delegateConnectToForegroundService(
            profileId = connectProfileId,
            trafficMode = trafficMode,
            commandStartId = commandStartId,
            protocolOptionIdOverride = protocolOptionIdOverride,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
        return
    }
    val privateDnsState = PrivateDnsSettings.currentState(this)
    if (!validatePrivateDnsState(privateDnsState, commandStartId)) {
        return
    }
    val transitionGeneration = beginRuntimeTransition("connect")
    FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = trafficMode)
    // BUG 1 (startup ordering): an in-tunnel Tor route is deferred out of the FIRST config so the
    // VPN comes up and validates alone; scheduleDeferredTorRouteUpgrade() engages Tor afterwards.
    val deferTorRoute =
        shouldDeferTorRouteForVpnFirstStartup(
            settings = settings,
            torOnlyConnect = torOnlyConnect,
            trafficMode = trafficMode,
        )
    val session =
        buildConnectSession(
            torOnlyConnect = torOnlyConnect,
            resolvedProfileId = connectProfileId,
            protocolOptionIdOverride = protocolOptionIdOverride,
            privateDnsState = privateDnsState,
            deferTorRoute = deferTorRoute,
            commandStartId = commandStartId,
        ) ?: return
    currentCoroutineContext().ensureActive()
    if (!isCurrentRuntimeTransition(transitionGeneration, "connect_session_loaded")) {
        return
    }
    val localGuardVpnNetworkHandle = stopActiveLocalGuardBeforeTunnelConnect()
    val validationExcludedVpnNetworkHandle = previousVpnNetworkHandle ?: localGuardVpnNetworkHandle
    // Arm the deferred Tor upgrade AFTER the local-guard handoff, never before: the handoff runs
    // closeRuntimeSession(), which clears pendingTorRouteUpgradeSessionId as a session-scoped
    // leftover. Setting it first meant any live local guard (I2P's transparent tun, the firewall,
    // any guard session) silently disarmed the upgrade — VPN+TOR then came up as VPN-ONLY while the
    // UI and notification still claimed Tor was engaged.
    pendingTorRouteUpgradeSessionId = if (deferTorRoute) session.correlationId else null
    // A new session needs a new bridge ownership claim: any IDLE/ERROR published on the way here
    // (the stop half of a profile switch or reconnect) fenced the previous writer for good, and this
    // service reuses one instance across sessions — without renewing, every publish below is
    // rejected as stale and the UI freezes on "connecting" while the tunnel is actually up.
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
            fail(getString(R.string.error_tcp_runtime_readiness_failed), commandStartId)
            return
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
    startI2pdReadinessProbe(settings)
    val result = startRuntimeWithHealthMetrics(session = session, owner = "vpn")
    if (!currentCoroutineContext().isActive) {
        container.diagnosticsLogger.record("connection", "runtime start cancelled after native return")
        return
    }
    handleRuntimeStartResult(
        result = result,
        session = session,
        trafficMode = trafficMode,
        tcpReadinessTarget = tcpReadinessTarget,
        previousVpnNetworkHandle = validationExcludedVpnNetworkHandle,
        commandStartId = commandStartId,
        transitionGeneration = transitionGeneration,
    )
}
