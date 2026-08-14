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
import com.foxhole.core.runtime.describeVpnRuntimeFailure
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.reloadFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.core.data.getSession
import com.foxhole.guard.core.data.getTorOnlySession
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.diagnosticFailureLabel
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runtime reload and post-reload-failure recovery for [FoxholeVpnService], extracted from the
 * service body in the Phase B split by responsibility. Hosts reload() and the restore/recover
 * helpers that roll back to the previous runtime or config when a reload fails.
 */

@Suppress("ReturnCount") // Reload has several distinct early-exit guards before the reload proper.
internal suspend fun FoxholeVpnService.reload(profileIdHint: Long) {
    reloadRuntime(profileIdHint = profileIdHint, requestedQuarantineRevision = null)
}

/** Service-owned durable quarantine command; intent dispatch alone is deliberately not an ack. */
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
    // The local guard has no profile session (activeSession stays null), so it needs its own hot
    // reload path: re-assemble the guard config and swap it in place, keeping the guard invariants.
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
    val coldRestartRequired = requiresColdRestartAfterTorRemoval(previousSession, session)
    val pendingSnapshot =
        runtimeReloadPendingSnapshot(
            snapshot = snapshot,
            reconnectingMessage = reconnectingMessage,
            inPlaceRuntimeReload = !coldRestartRequired,
        )
    bridgeWriter.update(
        pendingSnapshot,
        // A live policy replacement is not a route transition. Advancing this timestamp makes the
        // already-proven VPN IpInfo look stale and turns the VPN identity row back into a spinner.
        refreshLastChangeAt = coldRestartRequired,
    )
    updateNotification()
    if (coldRestartRequired) {
        // Removing the Tor lane must also tear down every native Tor resource. Use a cold native
        // restart; the existing recovery path fences generations, revalidates the new TUN and
        // fails closed on any restart error.
        val restarted =
            recoverRuntimeAfterReloadFailure(
                session = session,
                previousSnapshot = snapshot,
                message = "Tor route removed; cold restart required",
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
    if (!isCurrentRuntimeTransition(transitionGeneration, "reload_result")) {
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
        updateNotification()
        scheduleValidation(
            session = session,
            failOnFailure = true,
            onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
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

internal fun runtimeReloadPendingSnapshot(
    snapshot: ConnectionSnapshot,
    reconnectingMessage: String,
    session: VpnSession? = null,
    inPlaceRuntimeReload: Boolean,
): ConnectionSnapshot {
    val pending =
        if (inPlaceRuntimeReload && snapshot.state == ConnectionState.CONNECTED) {
            // A hot policy/Tor-lane replacement keeps the same Android tunnel and its validated
            // VPN identity. Expose the ownership marker, but never narrate a network reconnect.
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
        // The replacement is not applied UI state until its validation succeeds. In particular,
        // do not expose torActive=true here: that lets the Tor phase/identity outrun the VPN
        // identity barrier even though the old validated tunnel is still what the UI owns.
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

/**
 * Cuts the connections of every app in the BLOCK lane, right after the policy that blocks them
 * lands.
 *
 * A reload deliberately preserves flows that are already open — a routing change must not kill a
 * download — so on its own it only stops a blocked app from opening anything NEW. Everything the
 * app already had stayed up until its sockets happened to close, which is not what "blocked"
 * means to the person who pressed it.
 *
 * Every blocked package is revoked rather than only the one that just changed: the core reports
 * the count and revoking an app that is not talking is a no-op, so this needs no diff of the
 * previous assignments to be correct — and a diff is exactly the thing that would silently miss a
 * package blocked while the runtime was down.
 */
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
): Boolean = previousSession?.torActive == true && !nextSession.torActive

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
        // A preempting stop/kill cancels this command mid-session-load; that is not a profile
        // failure. Rethrow so the actor records cancellation instead of failing the whole runtime.
        if (error is CancellationException) {
            throw error
        }
        // Was `error.message`, which is the one place a remote host or a provider fragment could
        // reach an exportable journal verbatim. The label carries the message only for failures
        // this repository writes, and the class name for everything else.
        container.diagnosticsLogger.recordFailure(
            "connection",
            "runtime reload session failed: ${diagnosticFailureLabel(error)}",
        )
        val message = userFacingErrorMessage(error, R.string.error_profile_invalid)
        when (reloadSessionFailureAction(activeSession, FoxholeVpnRuntimeBridge.snapshot.value.state)) {
            ReloadSessionFailureAction.KEEP_LIVE_ROUTE -> keepLiveRuntimeAfterReloadSessionFailure(message)
            ReloadSessionFailureAction.FAIL_CLOSED -> fail(message)
        }
        null
    }

/** What a reload does when the session for the NEW config could not be built. */
internal enum class ReloadSessionFailureAction {
    /**
     * Keep the route that is already up.
     *
     * Make before break: at the moment the session build fails nothing has been replaced — the old
     * runtime, its TUN and its routes are all still live and still correct. Tearing them down was
     * how pressing the MODE button on a healthy tunnel put the device on the open network under
     * its real address (Pixel, 2026-08-10 21:59): a config that could not be BUILT took down the
     * one that was already RUNNING, and the person who pressed one button ended up unprotected.
     */
    KEEP_LIVE_ROUTE,

    /**
     * Fail closed.
     *
     * Nothing is running that this failure could preserve, so the fail-closed teardown of a failed
     * start is still the right answer — there is no route to keep, only a service to shut down.
     */
    FAIL_CLOSED,
}

internal fun reloadSessionFailureAction(
    activeSession: VpnSession?,
    connectionState: ConnectionState,
): ReloadSessionFailureAction =
    if (activeSession != null && connectionState in LIVE_ROUTE_STATES) {
        ReloadSessionFailureAction.KEEP_LIVE_ROUTE
    } else {
        ReloadSessionFailureAction.FAIL_CLOSED
    }

private val LIVE_ROUTE_STATES =
    setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)

/**
 * Reports a refused reload without disturbing the runtime it refused to replace.
 *
 * The connection state is deliberately left alone — it still describes a tunnel that is up and
 * carrying traffic, and publishing ERROR here would be a lie the terminal, the notification and
 * the dashboard would all repeat. Only [ConnectionSnapshot.message] carries the reason, so the
 * refusal is visible where failures are read without any state machine believing the session
 * ended.
 */
private fun FoxholeVpnService.keepLiveRuntimeAfterReloadSessionFailure(message: String) {
    container.diagnosticsLogger.recordFailure(
        "connection",
        "runtime reload refused, previous route kept: $message",
    )
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    bridgeWriter.update(snapshot.copy(message = message))
    updateNotification()
}

/**
 * Hot-reload the live local guard in place: rebuild its prepared FoxCore policy for the SAME mode
 * and apply it to the running engine, so blocked-app / DNS-filter / activity-logging changes
 * take effect without tearing down the TUN (no CONNECTING flash). A mode change is rejected here and
 * left to the full START_LOCAL_GUARD restart, because FIREWALL and DNS use different TUN parameters.
 * Unlike a profile reload this keeps activeSession == null and re-marks the GUARD fingerprint.
 */
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
        // Mode switched out from under us between dispatch and here — the restart path owns it.
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
    if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_reload_result")) {
        return
    }
    if (result.isSuccess) {
        // Re-mark the guard fingerprint so the next syncLocalGuard sees the new rules as applied
        // instead of looping into a restart; keep activeSession null (the guard has no profile).
        container.connectionController.markRuntimeApplied(session, transitionGeneration, mode)
        currentVpnNetworkOrNull()?.let { vpnNetwork ->
            startI2pdReadinessProbe(
                settings = settings,
                session = session,
                vpnNetwork = vpnNetwork,
            )
        }
        // The stats/journal jobs are created from the settings that were live when the guard
        // STARTED, so a guard booted with the journal off never grew journal jobs and the toggle
        // stayed a no-op until a restart. Re-derive them from the settings we just applied
        // (start... cancels the previous jobs, so this is idempotent when nothing changed).
        startRuntimeConnectionStatsUpdates(settings)
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        updateNotification()
        container.diagnosticsLogger.record("connection", "local guard reloaded mode=${mode.name.lowercase()}")
    } else {
        // Rare — the config was already assembled and checkConfig'd. Recover with a bounded restart
        // so the new rules still take effect rather than silently keeping the stale config.
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
    // A reload preempted by a higher-priority command (user stop, kill) is not a runtime
    // failure: the preemptor owns the teardown/next state. Running restore/recovery/fail()
    // here raced the disconnect and flashed a spurious "session ended result=error".
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
    if (!recoverRuntimeAfterReloadFailure(session, snapshot, message, transitionGeneration)) {
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
        activeVpnNetworkHandle = null
        activeSession = session
        bridgeWriter.updateActiveServerPingTarget(activeServerPingTarget(session))
        bridgeWriter.updateTraffic(trafficSampler.reset())
        bridgeWriter.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_UPDATE)
        bridgeWriter.update(
            previousSnapshot.copy(
                state = ConnectionState.RECONNECTING,
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                protocolOptionId = session.protocolOptionId,
                torActive = session.torActive,
                message = getString(R.string.status_reconnecting),
            ),
        )
        updateNotification()
        val restartResult =
            startRuntimeWithHealthMetrics(
                session = session,
                owner = "vpn_reload_recovery",
            )
        val staleRecovery = !isCurrentRuntimeTransition(transitionGeneration, "reload_recovery_start_result")
        recovered =
            staleRecovery ||
            handleRuntimeReloadRecoveryRestartResult(restartResult, session, transitionGeneration)
    }
    return recovered
}

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
    container.connectionController.markRuntimeApplied(session, transitionGeneration)
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
