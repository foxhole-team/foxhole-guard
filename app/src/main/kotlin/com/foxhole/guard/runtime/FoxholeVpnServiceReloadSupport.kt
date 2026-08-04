package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.PrivateDnsSettings
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Runtime reload and post-reload-failure recovery for [FoxholeVpnService], extracted from the
 * service body in the Phase B split by responsibility. Hosts reload() and the restore/recover
 * helpers that roll back to the previous runtime or config when a reload fails.
 */

@Suppress("ReturnCount") // Reload has several distinct early-exit guards before the reload proper.
internal suspend fun FoxholeVpnService.reload(profileIdHint: Long) {
    // The local guard has no profile session (activeSession stays null), so it needs its own hot
    // reload path: re-assemble the guard config and swap it in place, keeping the guard invariants.
    val localGuardMode = activeLocalGuardMode
    if (profileIdHint == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID && localGuardMode != null) {
        reloadLocalGuardRuntime(localGuardMode)
        return
    }
    val targetProfileId =
        activeSession?.profileId
            ?: profileIdHint.takeIf { it > 0L || it == FoxholeVpnService.TOR_ONLY_PROFILE_ID }
            ?: return
    val previousSession = activeSession
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
        return
    }
    val session = loadReloadSessionOrFail(targetProfileId) ?: return
    val transitionGeneration = beginRuntimeTransition("reload")
    if (requiresColdRestartAfterTorRemoval(previousSession, session)) {
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
        container.connectionController.markCurrentRuntimeApplied()
        container.diagnosticsLogger.record("connection", "runtime reloaded, tunnel validation required")
        val connectedSnapshot = snapshot.state == ConnectionState.CONNECTED
        val nextState = if (connectedSnapshot) ConnectionState.CONNECTED else ConnectionState.RECONNECTING
        val nextMessage =
            if (connectedSnapshot) {
                snapshot.message
            } else {
                getString(R.string.status_reconnecting)
            }
        bridgeWriter.update(
            snapshot.copy(
                state = nextState,
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                protocolOptionId = session.protocolOptionId,
                torActive = session.torActive,
                message = nextMessage,
            ),
            refreshLastChangeAt = !connectedSnapshot,
        )
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
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
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload session failed: ${diagnosticFailureLabel(error)}",
        )
        fail(userFacingErrorMessage(error, R.string.error_profile_invalid))
        null
    }

/**
 * Hot-reload the live local guard in place: rebuild its prepared FoxCore policy for the SAME mode
 * and apply it to the running engine, so blocked-app / DNS-filter / activity-logging changes
 * take effect without tearing down the TUN (no CONNECTING flash). A mode change is rejected here and
 * left to the full START_LOCAL_GUARD restart, because FIREWALL and DNS use different TUN parameters.
 * Unlike a profile reload this keeps activeSession == null and re-marks the GUARD fingerprint.
 */
private suspend fun FoxholeVpnService.reloadLocalGuardRuntime(mode: LocalGuardMode) {
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
    val session = buildLocalGuardSession(mode = mode, settings = settings)
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
        container.connectionController.markCurrentLocalGuardRuntimeApplied(mode)
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
        container.diagnosticsLogger.record(
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
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload cancelled by preempt; skipping failure handling",
        )
        return
    }
    val diagnosticMessage = error?.let(::describeVpnRuntimeFailure) ?: "unknown"
    container.diagnosticsLogger.record("connection", "runtime reload failed: $diagnosticMessage")
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
        container.diagnosticsLogger.record(
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
        recovered = staleRecovery || handleRuntimeReloadRecoveryRestartResult(restartResult, session)
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
): Boolean {
    if (restartResult.isFailure) {
        val restartMessage = restartResult.exceptionOrNull()?.let(::describeVpnRuntimeFailure) ?: "unknown"
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload recovery restart failed: $restartMessage",
        )
        return false
    }
    container.connectionController.markCurrentRuntimeApplied()
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
