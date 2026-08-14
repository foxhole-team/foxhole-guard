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
import com.foxhole.guard.RUNTIME_START_TIMEOUT_MARKER
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
            timeoutMessage = RUNTIME_START_TIMEOUT_MARKER,
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

/**
 * Confirms the two independent halves of an I2P connection: an authenticated i2pd endpoint and
 * the exact service-owned, applied Android VPN network carrying the session that embeds it.
 */
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
    scope.launch(Dispatchers.IO) {
        if (!container.i2pdManager.awaitReady(I2PD_READY_TIMEOUT_MS)) {
            return@launch
        }
        val currentI2pdGeneration = container.i2pdManager.snapshot().endpoints?.generation
        val currentVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle
        val sessionOwned = ownsI2pCarrierSession(session)
        val carrierCurrent =
            isI2pCarrierConfirmationCurrent(
                expectedEndpointGeneration = expectedEndpointGeneration,
                currentEndpointGeneration = currentI2pdGeneration,
                expectedVpnNetworkHandle = expectedVpnNetworkHandle,
                activeVpnNetworkHandle = activeVpnNetworkHandle,
                currentVpnNetworkHandle = currentVpnNetworkHandle,
                expectedRuntimeFingerprint = session.runtimeConfigFingerprint,
                appliedRuntimeFingerprint = container.connectionController.appliedRuntimeSignature.value,
                sessionOwned = sessionOwned,
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
    // NOT a gate. `strictRoute` means "route everything into the tunnel", which this app does on
    // its own — it is passed to the TUN as `strict_route`. Android's lockdown ("block connections
    // without VPN") is a SYSTEM setting the app cannot switch on, and it only adds what happens
    // while no tunnel exists at all.
    //
    // Refusing to connect because the user has not visited system settings blocked the primary
    // flow on every fresh install — the switch defaults to on — and reported it by printing the
    // kill-switch HELP ARTICLE as the connection error, which is where "errors everywhere" came
    // from. The honest behaviour is to connect and not claim a guarantee we do not have; the
    // status surface already withholds the "strict protection" label unless lockdown is really on.
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
    // BUG 1 (startup ordering): an in-tunnel Tor route is deferred out of the FIRST config so the
    // VPN comes up and validates alone; scheduleDeferredTorRouteUpgrade() engages Tor afterwards.
    val deferTorRoute =
        shouldDeferTorRouteForVpnFirstStartup(
            settings = settings,
            torOnlyConnect = torOnlyConnect,
            trafficMode = trafficMode,
        )
    // The old worker must release FoxCore's process lease before the new start. Its master TUN
    // stays open until Android installs the replacement, so traffic is blocked during the handoff.
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

/**
 * Everything from building the session to the native start, run while the retired local guard still
 * owns the live interface. Split out of [connect] so the whole span — including its failure exits —
 * sits inside the make-before-break window: the old TUN outlives every one of these returns.
 */
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
