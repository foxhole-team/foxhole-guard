package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RevokeOutcome
import com.foxhole.core.runtime.RevokeTarget
import com.foxhole.core.runtime.RuntimeIpRefreshReason
import com.foxhole.core.runtime.RuntimeStopPolicy
import com.foxhole.core.runtime.appliedTorRouteOrNull
import com.foxhole.core.runtime.describeVpnRuntimeFailure
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.nativeForceStopOutcomeOrNull
import com.foxhole.core.runtime.reloadFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.core.data.getSession
import com.foxhole.guard.core.data.getTorOnlySession
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.core.sentinel.anomaly.sentinelTrafficWindowCollectionEnabled
import com.foxhole.guard.diagnosticFailureLabel
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.reload(profileIdHint: Long) {
    reloadRuntime(profileIdHint = profileIdHint, requestedQuarantineRevision = null)
}

internal suspend fun FoxholeVpnService.enforceQuarantine(requestedRevision: Long) {
    val persistedRevision = container.settingsRepository.current().expert.quarantinePolicyRevision
    if (persistedRevision < requestedRevision) {
        return
    }
    val localGuardMode = activeLocalGuardMode
    if (localGuardMode != null) {
        reloadLocalGuardRuntime(localGuardMode, requestedRevision)
        return
    }
    val session = activeSession ?: return
    reloadRuntime(session.profileId, requestedRevision)
}

private suspend fun FoxholeVpnService.reloadRuntime(
    profileIdHint: Long,
    requestedQuarantineRevision: Long?,
) {
    val localGuardMode = activeLocalGuardMode
    if (profileIdHint == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID && localGuardMode != null) {
        reloadLocalGuardRuntime(localGuardMode, requestedQuarantineRevision)
        return
    }
    val preparation = prepareRuntimeReload(profileIdHint, requestedQuarantineRevision) ?: return
    val previousSession = preparation.previousSession
    val snapshot = preparation.snapshot
    val session = preparation.session
    val transitionGeneration = beginRuntimeTransition("reload")
    val reconnectingMessage = getString(R.string.status_reconnecting)
    val coldRestartRequired = requiresColdRestartForTorRouteApply(previousSession, session)
    val pendingSnapshot =
        runtimeReloadPendingSnapshot(
            snapshot = snapshot,
            reconnectingMessage = reconnectingMessage,
            inPlaceRuntimeReload = !coldRestartRequired,
        )
    bridgeWriter.update(
        pendingSnapshot,

        refreshLastChangeAt = coldRestartRequired,
    )
    updateNotification()
    if (coldRestartRequired) {
        val restarted =
            recoverRuntimeAfterReloadFailure(
                session = session,
                previousSession = previousSession,
                previousSnapshot = snapshot,
                message = "Tor route TUN plan changed; cold restart required",
                transitionGeneration = transitionGeneration,
            )
        if (!restarted) {
            fail(getString(R.string.error_runtime_stopped))
        }
        return
    }
    val result =
        runtime.reloadFailClosed(
            session = session,
            host = this,
            owner = "vpn",
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
        )
    if (
        handleNativeForceStopPoison(result) { outcome ->
            terminateProcessIfNativeForceStopPoisoned(outcome, "vpn_reload")
        } ||
        !isCurrentRuntimeTransition(transitionGeneration, "reload_result")
    ) {
        return
    }
    if (result.isSuccess) {
        activeSession = session
        bridgeWriter.updateActiveServerPingTarget(activeServerPingTarget(session))
        container.connectionController.markRuntimeApplied(session, transitionGeneration)
        container.diagnosticsLogger.record("connection", "runtime reloaded, tunnel validation required")
        bridgeWriter.update(
            runtimeReloadPendingSnapshot(
                snapshot = pendingSnapshot,
                reconnectingMessage = reconnectingMessage,
                session = session,
                inPlaceRuntimeReload = true,
            ),
            refreshLastChangeAt = false,
        )
        torProbeOwnerEnabled = session.torActive
        scope.launch(Dispatchers.IO) { syncTorProbeProxy() }
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        revokeBlockedAppFlows()
        reregisterGatedSessionTasks()
        updateNotification()
        scheduleValidation(
            session = session,
            failOnFailure = true,
            onSuccess = { vpnNetwork ->
                onTunnelValidated(session, vpnNetwork)
                finalizeSuccessfulTorDetach(previousSession, session)
            },
        )
    } else {
        handleRuntimeReloadFailure(
            error = result.exceptionOrNull(),
            previousSession = previousSession,
            session = session,
            snapshot = snapshot,
            transitionGeneration = transitionGeneration,
        )
    }
}

internal fun FoxholeVpnService.reregisterGatedSessionTasks() {
    val settings = container.settingsRepository.settings.value
    restartGatedSessionTask(
        id = FoxholeVpnService.TICKER_TASK_CHILD_WATCHDOG,
        gateOpen = childWatchdogFallbackPollNeeded(settings),
    ) { startChildProcessWatchdog() }
    restartGatedSessionTask(
        id = FoxholeVpnService.TICKER_TASK_LAN_PROXY,
        gateOpen = proxySurfaceTickerNeeded(settings),
    ) { startLanProxyUpdates() }
    val taskScope =
        reloadedSessionTaskScope(
            trafficJobRegistered = sessionTicker.isRegistered(FoxholeVpnService.TICKER_TASK_TRAFFIC),
            dnsGuardActive = activeLocalGuardMode == LocalGuardMode.DNS,
        )
    when (taskScope) {
        ReloadedSessionTaskScope.TUNNEL_TELEMETRY -> {
            restartGatedSessionTask(
                id = FoxholeVpnService.TICKER_TASK_I2P_TRAFFIC,
                gateOpen = i2pTrafficSamplingPossible(settings),
            ) { startI2pTrafficStatsUpdates() }
            restartGatedSessionTask(
                id = FoxholeVpnService.TICKER_TASK_APP_TRAFFIC,
                gateOpen = appTrafficStatsRuntimeEnabled(settings),
            ) { startAppTrafficStatsUpdates() }
        }
        ReloadedSessionTaskScope.DNS_GUARD_WINDOW ->
            restartGatedSessionTask(
                id = FoxholeVpnService.TICKER_TASK_DNS_GUARD_WINDOW,
                gateOpen = sentinelTrafficWindowCollectionEnabled(settings),
            ) { startDnsGuardWindowUpdates() }
        ReloadedSessionTaskScope.WATCHDOG_ONLY -> Unit
    }
}

private fun FoxholeVpnService.restartGatedSessionTask(
    id: String,
    gateOpen: Boolean,
    start: () -> Unit,
) {
    if (!gatedSessionTaskNeedsRestart(registered = sessionTicker.isRegistered(id), gateOpen = gateOpen)) {
        return
    }
    start()
    container.diagnosticsLogger.record(
        "connection",
        "session task re-evaluated after reload id=$id enabled=$gateOpen",
    )
}

internal enum class ReloadedSessionTaskScope { TUNNEL_TELEMETRY, DNS_GUARD_WINDOW, WATCHDOG_ONLY }

internal fun reloadedSessionTaskScope(
    trafficJobRegistered: Boolean,
    dnsGuardActive: Boolean,
): ReloadedSessionTaskScope =
    when {
        trafficJobRegistered -> ReloadedSessionTaskScope.TUNNEL_TELEMETRY
        dnsGuardActive -> ReloadedSessionTaskScope.DNS_GUARD_WINDOW
        else -> ReloadedSessionTaskScope.WATCHDOG_ONLY
    }

internal fun gatedSessionTaskNeedsRestart(registered: Boolean, gateOpen: Boolean): Boolean =
    registered != gateOpen

internal fun runtimeReloadPendingSnapshot(
    snapshot: ConnectionSnapshot,
    reconnectingMessage: String,
    session: VpnSession? = null,
    inPlaceRuntimeReload: Boolean,
): ConnectionSnapshot {
    val pending =
        if (inPlaceRuntimeReload && snapshot.state == ConnectionState.CONNECTED) {
            snapshot.copy(inPlaceRuntimeReload = true)
        } else {
            snapshot.copy(
                state = ConnectionState.RECONNECTING,
                message = reconnectingMessage,
                inPlaceRuntimeReload = inPlaceRuntimeReload,
            )
        }
    return if (
        session == null ||

        (inPlaceRuntimeReload && snapshot.state == ConnectionState.CONNECTED)
    ) {
        pending
    } else {
        pending.copy(
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
            protocolOptionId = session.protocolOptionId,
            torActive = session.torActive,
            appliedTorRoute = session.appliedTorRoute,
        )
    }
}

private data class RuntimeReloadPreparation(
    val previousSession: VpnSession?,
    val snapshot: ConnectionSnapshot,
    val session: VpnSession,
)

private suspend fun FoxholeVpnService.prepareRuntimeReload(
    profileIdHint: Long,
    requestedQuarantineRevision: Long?,
): RuntimeReloadPreparation? {
    val previousSession = activeSession
    val targetProfileId =
        previousSession?.profileId
            ?: profileIdHint.takeIf { it > 0L || it == FoxholeVpnService.TOR_ONLY_PROFILE_ID }
            ?: return null
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (!snapshot.state.acceptsRuntimeReload()) {
        return null
    }
    val session =
        loadReloadSessionOrFail(targetProfileId)
            ?.takeIf { loaded -> loaded.includesQuarantineRevision(requestedQuarantineRevision) }
            ?.takeIf { loaded ->
                val matchesCurrentIntent =
                    reloadSessionMatchesCurrentTorIntent(
                        loaded,
                        container.settingsRepository.current(),
                    )
                if (!matchesCurrentIntent) {
                    container.diagnosticsLogger.record(
                        "connection",
                        "runtime reload discarded: Tor intent changed while session was built",
                    )
                }
                matchesCurrentIntent
            }
            ?: return null
    return RuntimeReloadPreparation(
        previousSession = previousSession,
        snapshot = snapshot,
        session = session,
    )
}

private fun ConnectionState.acceptsRuntimeReload(): Boolean =
    this == ConnectionState.CONNECTING ||
        this == ConnectionState.CONNECTED ||
        this == ConnectionState.RECONNECTING

private fun VpnSession.includesQuarantineRevision(requestedRevision: Long?): Boolean =
    requestedRevision == null || quarantinePolicyRevision >= requestedRevision

internal fun reloadSessionMatchesCurrentTorIntent(
    session: VpnSession,
    settings: com.foxhole.core.model.Settings,
): Boolean {
    val desiredRoute = settings.appliedTorRouteOrNull(session.protocolHint)
    return session.torActive == (desiredRoute != null) && session.appliedTorRoute == desiredRoute
}

internal fun FoxholeVpnService.revokeBlockedAppFlows() {
    val blocked = container.settingsRepository.settings.value.expert.blockedLanePackages()
    if (blocked.isEmpty()) {
        return
    }
    val runtime = container.runtimeInstanceStore.current() ?: return
    val revoked =
        blocked.sumOf { packageName ->
            when (val outcome = runtime.revokeFlows(RevokeTarget.Package(packageName))) {
                is RevokeOutcome.Revoked -> outcome.flows
                else -> 0
            }
        }
    if (revoked > 0) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "blocked apps cut off",
            "packages=${blocked.size}",
            "flows=$revoked",
        )
    }
}

internal fun requiresColdRestartAfterTorRemoval(
    previousSession: VpnSession?,
    nextSession: VpnSession,
): Boolean =
    previousSession.carriesTorRuntime() &&
        !nextSession.carriesTorRuntime() &&
        previousSession?.foxCoreConfig?.tunPlan != nextSession.foxCoreConfig?.tunPlan

internal fun requiresColdRestartForTorRouteApply(
    previousSession: VpnSession?,
    nextSession: VpnSession,
): Boolean {
    if (
        previousSession == null ||
        (!previousSession.carriesTorRuntime() && !nextSession.carriesTorRuntime())
    ) {
        return false
    }
    return previousSession.foxCoreConfig?.tunPlan != nextSession.foxCoreConfig?.tunPlan
}

internal fun shouldFinalizeSuccessfulTorDetach(
    previousSession: VpnSession?,
    nextSession: VpnSession,
    appliedSnapshot: ConnectionSnapshot,
    nextSessionIsActive: Boolean,
): Boolean =
    previousSession.carriesTorRuntime() &&
        !nextSession.carriesTorRuntime() &&
        nextSessionIsActive &&
        appliedSnapshot.state == ConnectionState.CONNECTED &&
        !appliedSnapshot.inPlaceRuntimeReload &&
        !appliedSnapshot.torActive &&
        appliedSnapshot.appliedTorRoute == null

private fun FoxholeVpnService.finalizeSuccessfulTorDetach(
    previousSession: VpnSession?,
    nextSession: VpnSession,
) {
    val applied = FoxholeVpnRuntimeBridge.snapshot.value
    if (
        !shouldFinalizeSuccessfulTorDetach(
            previousSession = previousSession,
            nextSession = nextSession,
            appliedSnapshot = applied,
            nextSessionIsActive = activeSession.matchesRuntimeValidationSession(nextSession),
        )
    ) {
        return
    }
    bridgeWriter.updateTorRouteIpInfo(null)
    val reaped = reapTorTransportOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "tor detach replacement finalized",
        "transport_orphans_reaped=$reaped",
    )
}

private suspend fun FoxholeVpnService.loadReloadSessionOrFail(targetProfileId: Long): VpnSession? =
    runCatching {
        if (targetProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
            container.profileRepository.getTorOnlySession(
                privateDnsState = PrivateDnsSettings.currentState(this),
            )
        } else {
            container.profileRepository.getSession(
                profileId = targetProfileId,
                privateDnsState = PrivateDnsSettings.currentState(this),
            )
        }
    }.getOrElse { error ->

        if (error is CancellationException) {
            throw error
        }

        container.diagnosticsLogger.recordFailure(
            "connection",
            "runtime reload session failed: ${diagnosticFailureLabel(error)}",
        )
        val message = userFacingErrorMessage(error, R.string.error_profile_invalid)
        val desiredSettings = container.settingsRepository.current()
        val authoritativeTorReconcile = requiresAuthoritativeTorReconcile(activeSession, desiredSettings)
        when (
            reloadSessionFailureAction(
                activeSession = activeSession,
                connectionState = FoxholeVpnRuntimeBridge.snapshot.value.state,
                authoritativeTorReconcile = authoritativeTorReconcile,
            )
        ) {
            ReloadSessionFailureAction.KEEP_LIVE_ROUTE -> keepLiveRuntimeAfterReloadSessionFailure(message)
            ReloadSessionFailureAction.FAIL_CLOSED -> fail(message)
        }
        null
    }

internal enum class ReloadSessionFailureAction {
    KEEP_LIVE_ROUTE,

    FAIL_CLOSED,
}

internal fun reloadSessionFailureAction(
    activeSession: VpnSession?,
    connectionState: ConnectionState,
    authoritativeTorReconcile: Boolean = false,
): ReloadSessionFailureAction =
    if (authoritativeTorReconcile) {
        ReloadSessionFailureAction.FAIL_CLOSED
    } else if (activeSession != null && connectionState in LIVE_ROUTE_STATES) {
        ReloadSessionFailureAction.KEEP_LIVE_ROUTE
    } else {
        ReloadSessionFailureAction.FAIL_CLOSED
    }

internal fun requiresAuthoritativeTorReconcile(
    activeSession: VpnSession?,
    desiredSettings: com.foxhole.core.model.Settings,
): Boolean {
    val desiredRoute = desiredSettings.appliedTorRouteOrNull(activeSession?.protocolHint)
    val activeTor = activeSession?.torActive == true
    if (activeTor != (desiredRoute != null)) {
        return true
    }
    return activeTor && activeSession.appliedTorRoute != desiredRoute
}

private val LIVE_ROUTE_STATES =
    setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)

private fun FoxholeVpnService.keepLiveRuntimeAfterReloadSessionFailure(message: String) {
    container.diagnosticsLogger.recordFailure(
        "connection",
        "runtime reload refused, previous route kept: $message",
    )
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    bridgeWriter.update(snapshot.copy(message = message))
    updateNotification()
}

@Suppress("ReturnCount")
private suspend fun FoxholeVpnService.reloadLocalGuardRuntime(
    mode: LocalGuardMode,
    requestedQuarantineRevision: Long? = null,
) {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID ||
        snapshot.state !in setOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        )
    ) {
        return
    }
    val settings = container.settingsRepository.current()
    if (settings.localGuardModeOrNull() != mode) {
        return
    }
    val session =
        runCatchingUnlessCancelled {
            buildLocalGuardSession(mode = mode, settings = settings)
        }.getOrElse { error ->
            container.i2pdManager.markCarrierUnavailable()
            container.i2pdManager.stop()
            container.diagnosticsLogger.recordFailure(
                "connection",
                "local guard reload session failed: ${diagnosticFailureLabel(error)}",
            )
            bridgeWriter.update(
                snapshot.copy(message = userFacingErrorMessage(error, R.string.error_runtime_start_failed)),
                refreshLastChangeAt = false,
            )
            updateNotification()
            return
        }
    if (requestedQuarantineRevision != null && session.quarantinePolicyRevision < requestedQuarantineRevision) {
        return
    }
    val transitionGeneration = beginRuntimeTransition("local_guard_reload:${mode.name.lowercase()}")
    val result =
        runtime.reloadFailClosed(
            session = session,
            host = this,
            owner = "local_guard",
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
        )
    if (
        handleNativeForceStopPoison(result) { outcome ->
            terminateProcessIfNativeForceStopPoisoned(outcome, "local_guard_reload")
        }
    ) {
        return
    }
    if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_reload_result")) {
        return
    }
    if (result.isSuccess) {
        container.connectionController.markRuntimeApplied(session, transitionGeneration, mode)
        currentVpnNetworkOrNull()?.let { vpnNetwork ->
            startI2pdReadinessProbe(
                settings = settings,
                session = session,
                vpnNetwork = vpnNetwork,
            )
        }

        startRuntimeConnectionStatsUpdates(settings)
        reregisterGatedSessionTasks()
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        updateNotification()
        container.diagnosticsLogger.record("connection", "local guard reloaded mode=${mode.name.lowercase()}")
    } else {
        container.diagnosticsLogger.recordFailure(
            "connection",
            "local guard reload failed, scheduling restart: ${result.exceptionOrNull()?.message.orEmpty()}",
        )
        scheduleLocalGuardHeal(mode, "reload_failed")
    }
}

private suspend fun FoxholeVpnService.handleRuntimeReloadFailure(
    error: Throwable?,
    previousSession: VpnSession?,
    session: VpnSession,
    snapshot: ConnectionSnapshot,
    transitionGeneration: Long,
) {
    if (error is CancellationException) {
        throw error
    }
    if (!currentCoroutineContext().isActive) {
        container.diagnosticsLogger.recordFailure(
            "connection",
            "runtime reload cancelled by preempt; skipping failure handling",
        )
        return
    }
    val diagnosticMessage = error?.let(::describeVpnRuntimeFailure) ?: "unknown"
    container.diagnosticsLogger.recordFailure("connection", "runtime reload failed: $diagnosticMessage")
    val message = userFacingErrorMessage(error, R.string.error_runtime_stopped)
    if (requiresAuthoritativeTorReconcile(previousSession, container.settingsRepository.current())) {
        fail(message)
        return
    }
    if (
        restorePreviousRuntimeAfterReloadFailure(
            previousSession = previousSession,
            failedSession = session,
            previousSnapshot = snapshot,
            message = message,
            transitionGeneration = transitionGeneration,
        )
    ) {
        return
    }
    if (!recoverRuntimeAfterReloadFailure(
            session = session,
            previousSession = previousSession,
            previousSnapshot = snapshot,
            message = message,
            transitionGeneration = transitionGeneration,
        )
    ) {
        fail(message)
    }
}

private suspend fun FoxholeVpnService.restorePreviousRuntimeAfterReloadFailure(
    previousSession: VpnSession?,
    failedSession: VpnSession,
    previousSnapshot: ConnectionSnapshot,
    message: String,
    transitionGeneration: Long,
): Boolean {
    var restored = false
    val restoreSession = previousSession
    if (shouldAttemptRuntimeReloadRestore(previousSession, failedSession) && restoreSession != null) {
        restored =
            restorePreviousRuntimeConfigAfterReloadFailure(
                restoreSession = restoreSession,
                previousSnapshot = previousSnapshot,
                message = message,
                transitionGeneration = transitionGeneration,
            )
        if (restored && shouldReapTorHelpersAfterReloadRestore(previousSession, failedSession)) {
            val reaped = reapTorTransportOrphans()
            container.diagnosticsLogger.recordStructured(
                "runtime",
                "arti transport helpers reaped after failed attach",
                "count=$reaped",
            )
        }
    }
    return restored
}

private suspend fun FoxholeVpnService.restorePreviousRuntimeConfigAfterReloadFailure(
    restoreSession: VpnSession,
    previousSnapshot: ConnectionSnapshot,
    message: String,
    transitionGeneration: Long,
): Boolean {
    var restored = true
    if (isCurrentRuntimeTransition(transitionGeneration, "reload_restore")) {
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload restore previous config requested after: $message",
        )
        val restoreResult =
            runtime.reloadFailClosed(
                session = restoreSession,
                host = this,
                owner = "vpn_restore",
                diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
            )
        if (
            handleNativeForceStopPoison(restoreResult) { outcome ->
                terminateProcessIfNativeForceStopPoisoned(outcome, "vpn_reload_restore")
            }
        ) {
            return true
        }
        val staleRestore = !isCurrentRuntimeTransition(transitionGeneration, "reload_restore_result")
        restored =
            staleRestore ||
            handlePreviousRuntimeConfigRestoreResult(restoreResult, restoreSession, previousSnapshot)
    }
    return restored
}

private fun FoxholeVpnService.handlePreviousRuntimeConfigRestoreResult(
    restoreResult: Result<Unit>,
    restoreSession: VpnSession,
    previousSnapshot: ConnectionSnapshot,
): Boolean {
    var restored = false
    if (restoreResult.isFailure) {
        val restoreMessage = restoreResult.exceptionOrNull()?.let(::describeVpnRuntimeFailure) ?: "unknown"
        container.diagnosticsLogger.recordFailure(
            "connection",
            "runtime reload restore previous config failed: $restoreMessage",
        )
    } else {
        publishPreviousRuntimeConfigRestoreSuccess(restoreSession, previousSnapshot)
        restored = true
    }
    return restored
}

private fun FoxholeVpnService.publishPreviousRuntimeConfigRestoreSuccess(
    restoreSession: VpnSession,
    previousSnapshot: ConnectionSnapshot,
) {
    activeSession = restoreSession
    bridgeWriter.updateActiveServerPingTarget(activeServerPingTarget(restoreSession))
    container.diagnosticsLogger.record(
        "connection",
        "runtime reload restored previous config, tunnel validation required",
    )
    FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
    bridgeWriter.update(
        previousSnapshot.copy(
            state = ConnectionState.RECONNECTING,
            profileId = restoreSession.profileId,
            profileName = restoreSession.profileName,
            protocolHint = restoreSession.protocolHint,
            protocolOptionId = restoreSession.protocolOptionId,
            torActive = restoreSession.torActive,
            appliedTorRoute = restoreSession.appliedTorRoute,
            message = getString(R.string.status_reconnecting),
        ),
    )
    updateNotification()
    scheduleValidation(
        session = restoreSession,
        failOnFailure = true,
        onSuccess = { vpnNetwork -> onTunnelValidated(restoreSession, vpnNetwork) },
    )
}

private suspend fun FoxholeVpnService.recoverRuntimeAfterReloadFailure(
    session: VpnSession,
    previousSession: VpnSession?,
    previousSnapshot: ConnectionSnapshot,
    message: String,
    transitionGeneration: Long,
): Boolean {
    var recovered = true
    if (isCurrentRuntimeTransition(transitionGeneration, "reload_recovery")) {
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload recovery restart requested after: $message",
        )
        invalidateValidationEpoch("reload_recovery")
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopRuntimeFailClosed(
            reason = "reload_recovery",
            policy = PLANNED_RELOAD_STOP_POLICY,
        )
        if (shouldReapTorHelpersAfterReloadStop(previousSession, session)) {
            val reaped = reapTorTransportOrphans()
            container.diagnosticsLogger.recordStructured(
                "runtime",
                "arti transport helpers reaped before reload restart",
                "count=$reaped",
            )
        }
        activeVpnNetworkHandle = null
        activeSession = null
        bridgeWriter.updateActiveServerPingTarget(null)
        bridgeWriter.updateTraffic(trafficSampler.reset())
        bridgeWriter.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_UPDATE)
        bridgeWriter.update(
            previousSnapshot.detachedRuntimeReconnectSnapshot(getString(R.string.status_reconnecting)).copy(
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                protocolOptionId = session.protocolOptionId,
            ),
        )
        updateNotification()
        val restartResult =
            startRuntimeWithHealthMetrics(
                session = session,
                owner = "vpn_reload_recovery",
            )
        if (restartResult.nativeForceStopOutcomeOrNull() != null) {
            return true
        }
        val staleRecovery = !isCurrentRuntimeTransition(transitionGeneration, "reload_recovery_start_result")
        recovered =
            staleRecovery ||
            handleRuntimeReloadRecoveryRestartResult(restartResult, session, transitionGeneration)
    }
    return recovered
}

internal fun shouldReapTorHelpersAfterReloadStop(
    previousSession: VpnSession?,
    nextSession: VpnSession,
): Boolean = previousSession.carriesTorRuntime() || nextSession.carriesTorRuntime()

internal fun shouldReapTorHelpersAfterReloadRestore(
    restoredSession: VpnSession?,
    failedSession: VpnSession,
): Boolean =
    restoredSession != null &&
        !restoredSession.carriesTorRuntime() &&
        failedSession.carriesTorRuntime()

private fun VpnSession?.carriesTorRuntime(): Boolean =
    this?.torActive == true || this?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID

private val PLANNED_RELOAD_STOP_POLICY =
    RuntimeStopPolicy(
        closeTunFdImmediately = true,
        closeServiceTimeoutMs = 5_000L,
        closeServerTimeoutMs = 2_000L,
        totalGracefulTimeoutMs = 8_000L,
        forceKillAfterTimeout = true,
    )

private suspend fun FoxholeVpnService.handleRuntimeReloadRecoveryRestartResult(
    restartResult: Result<Unit>,
    session: VpnSession,
    transitionGeneration: Long,
): Boolean {
    if (restartResult.isFailure) {
        val restartMessage = restartResult.exceptionOrNull()?.let(::describeVpnRuntimeFailure) ?: "unknown"
        container.diagnosticsLogger.recordFailure(
            "connection",
            "runtime reload recovery restart failed: $restartMessage",
        )
        return false
    }
    activeSession = session
    bridgeWriter.updateActiveServerPingTarget(activeServerPingTarget(session))
    container.connectionController.markRuntimeApplied(session, transitionGeneration)
    bridgeWriter.update(
        FoxholeVpnRuntimeBridge.snapshot.value.copy(
            torActive = session.torActive,
            appliedTorRoute = session.appliedTorRoute,
        ),
    )
    updateNotification()
    container.diagnosticsLogger.record(
        "connection",
        "runtime reload recovery restarted, tunnel validation required",
    )
    FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
    scheduleValidation(
        session = session,
        failOnFailure = true,
        onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
    )
    return true
}
