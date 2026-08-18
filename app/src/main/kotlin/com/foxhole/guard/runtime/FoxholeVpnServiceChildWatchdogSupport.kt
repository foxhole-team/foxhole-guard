package com.foxhole.guard.runtime

import android.os.SystemClock
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.I2pdState
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeAutoReconnectPolicy
import com.foxhole.guard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap

// An Android phantom-process kill can take an app-owned helper without touching the tunnel itself. The health
// probe only notices once traffic actually fails, and its reconnect is gated on the user's
// auto-reconnect setting - so with that toggle off a killed child stayed dead until a manual
// reconnect. This watchdog is the setting-independent safety net: a child that RAN during this
// session and is later seen KILLED gets the whole runtime rebuilt (children are wired into the
// session config - ports and credentials - so a lone child respawn would leave FoxCore dialing
// stale endpoints).
//
// Detection is event-first (Ф3e): the managers' stdout-drain threads see EOF the moment a child
// dies and fire setUnexpectedExitListener — deliberate stop()/kill() clear the process reference
// before destroying it, so the event only ever means a genuine unexpected death and needs no
// confirm-tick debounce. The slow poll below remains as a fallback for the cases the event cannot
// cover: a drain thread that itself died or hung on the pipe, a destroyForcibly that failed
// leaving the snapshot in ERROR, or a start that raced publish and never attached the drain.
// Fail-closed either way — a dead child means FoxCore refuses the unavailable private route
// rather than leaking — so slower fallback detection trades only availability latency, not safety.
private const val CHILD_WATCHDOG_FALLBACK_POLL_MS = 30_000L

// KILLED must be observed on consecutive ticks: a deliberate stop that needed destroyForcibly
// (e.g. the user-facing Tor restart) parks the snapshot on KILLED for a moment before STARTING.
internal const val CHILD_WATCHDOG_CONFIRM_TICKS = 2
private const val CHILD_WATCHDOG_MAX_ATTEMPTS = 5

// The in-process Arti outbound is owned and monitored by FoxCore. i2pd remains the only
// app-managed child process whose death requires a full session rebuild.
internal const val CHILD_WATCHDOG_CASUALTY_I2PD = "i2pd"

// Attempts survive the rebuilds they trigger (each rebuild mints a new session correlation id, so
// a per-session budget would reset itself into a crash-loop). A quiet period this long closes the
// incident instead.
private const val CHILD_WATCHDOG_INCIDENT_RESET_MS = 10 * 60_000L

private class ChildWatchdogState {
    var attempts: Int = 0
    var lastAttemptAtElapsedMs: Long = 0L
    var exhaustionPublished: Boolean = false
}

private val childWatchdogStates = WeakHashMap<FoxholeVpnService, ChildWatchdogState>()

/**
 * Pure per-tick casualty detection, extracted for unit tests: KILLED only counts for a child that
 * was seen RUNNING during this watchdog's lifetime (a stale KILLED snapshot from a previous
 * session must not condemn a session that never started that child).
 */
internal class ChildCasualtyTracker(
    private val confirmTicks: Int = CHILD_WATCHDOG_CONFIRM_TICKS,
) {
    private var sawI2pdRunning = false
    private var i2pdKilledTicks = 0

    fun resetTicks() {
        i2pdKilledTicks = 0
    }

    /**
     * Returns the confirmed casualty for this tick, or null.
     *
     */
    fun observe(i2pdState: I2pdState): String? {
        if (i2pdState == I2pdState.RUNNING) {
            sawI2pdRunning = true
        }
        i2pdKilledTicks =
            if (sawI2pdRunning && i2pdState == I2pdState.KILLED) i2pdKilledTicks + 1 else 0
        val casualty = CHILD_WATCHDOG_CASUALTY_I2PD.takeIf { i2pdKilledTicks >= confirmTicks }
        if (casualty != null) {
            resetTicks()
        }
        return casualty
    }
}

internal fun FoxholeVpnService.startChildProcessWatchdog() {
    stopChildProcessWatchdog()
    container.i2pdManager.setUnexpectedExitListener { onChildUnexpectedExit(CHILD_WATCHDOG_CASUALTY_I2PD) }
    if (!childWatchdogFallbackPollNeeded(container.settingsRepository.settings.value)) {
        return
    }
    val tracker = ChildCasualtyTracker()
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_CHILD_WATCHDOG,
        fireImmediately = false,
        intervalMs = { CHILD_WATCHDOG_FALLBACK_POLL_MS },
        // Dispatched: a confirmed casualty's heal path sleeps through a jittered backoff, which
        // must not stall the fast telemetry tasks sharing the ticker.
        runOn = Dispatchers.Default,
    ) {
        childWatchdogTick(tracker)
    }
}

internal fun FoxholeVpnService.stopChildProcessWatchdog() {
    container.i2pdManager.setUnexpectedExitListener(null)
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_CHILD_WATCHDOG)
}

internal fun childWatchdogFallbackPollNeeded(settings: Settings): Boolean = settings.i2p.enabled

/**
 * Event path: invoked from a manager's stdout-drain thread the moment a child dies unexpectedly.
 * Hops onto the service scope; the session/state guards mirror the fallback tick, and a snapshot
 * that already left KILLED means another flow (user restart, running heal) owns the recovery.
 */
private fun FoxholeVpnService.onChildUnexpectedExit(casualty: String) {
    scope.launch(Dispatchers.Default) {
        val session = activeSession
        val localGuardMode = activeLocalGuardMode
        if (session == null && localGuardMode == null) return@launch
        if (FoxholeVpnRuntimeBridge.snapshot.value.state != ConnectionState.CONNECTED) {
            return@launch
        }
        val stillKilled = container.i2pdManager.snapshot().state == I2pdState.KILLED
        if (stillKilled) {
            if (session != null) {
                healChildCasualty(session, casualty)
            } else if (localGuardMode != null) {
                healLocalGuardChildCasualty(localGuardMode, casualty)
            }
        }
    }
}

private suspend fun FoxholeVpnService.childWatchdogTick(
    tracker: ChildCasualtyTracker,
) {
    val session = activeSession
    val localGuardMode = activeLocalGuardMode
    if (
        (session == null && localGuardMode == null) ||
        FoxholeVpnRuntimeBridge.snapshot.value.state != ConnectionState.CONNECTED
    ) {
        tracker.resetTicks()
        return
    }
    val casualty = tracker.observe(container.i2pdManager.snapshot().state) ?: return
    if (session != null) {
        healChildCasualty(session, casualty)
    } else if (localGuardMode != null) {
        healLocalGuardChildCasualty(localGuardMode, casualty)
    }
}

private suspend fun FoxholeVpnService.healChildCasualty(
    session: VpnSession,
    casualty: String,
) {
    if (!registerChildWatchdogAttempt(casualty)) {
        return
    }
    val attempt = childWatchdogState().attempts
    delay(RuntimeAutoReconnectPolicy.jitteredBackoffDelayMs(attempt))
    launchCommand("child_watchdog_reconnect:${session.profileId}:$attempt") {
        val current = activeSession
        if (current?.correlationId == session.correlationId) {
            // Deliberately NOT gated on the auto-reconnect setting: the user asked for a running
            // Tor/I2P session and the OS took a child out from under it.
            reconnectActiveRuntime(
                session = session,
                reason = "child_process_died:$casualty",
                attempt = attempt,
            )
        }
    }
}

private suspend fun FoxholeVpnService.healLocalGuardChildCasualty(
    mode: LocalGuardMode,
    casualty: String,
) {
    if (!registerChildWatchdogAttempt(casualty)) {
        return
    }
    val attempt = childWatchdogState().attempts
    delay(RuntimeAutoReconnectPolicy.jitteredBackoffDelayMs(attempt))
    launchCommand("child_watchdog_local_guard:${mode.name.lowercase()}:$attempt") {
        if (activeSession == null && activeLocalGuardMode == mode) {
            // Force the ordinary single-owner guard restart path. Merely respawning i2pd would
            // mint a new port/password while the native config kept the dead endpoint.
            container.connectionController.clearAppliedRuntime()
            startLocalGuard(requestedMode = mode, commandStartId = 0)
        }
    }
}

internal fun FoxholeVpnService.isI2pEndpointLeaseCurrent(session: VpnSession): Boolean {
    val expectedGeneration = session.i2pEndpointGeneration ?: return true
    val snapshot = container.i2pdManager.snapshot()
    return snapshot.state == I2pdState.RUNNING && snapshot.endpoints?.generation == expectedGeneration
}

/** True when the incident budget allows another rebuild; logs the decision either way. */
private fun FoxholeVpnService.registerChildWatchdogAttempt(casualty: String): Boolean {
    val state = childWatchdogState()
    val now = SystemClock.elapsedRealtime()
    if (state.attempts > 0 && now - state.lastAttemptAtElapsedMs >= CHILD_WATCHDOG_INCIDENT_RESET_MS) {
        state.attempts = 0
        state.exhaustionPublished = false
    }
    if (state.attempts >= CHILD_WATCHDOG_MAX_ATTEMPTS) {
        if (!state.exhaustionPublished) {
            state.exhaustionPublished = true
            container.diagnosticsLogger.recordStructured(
                "connection",
                "child watchdog budget exhausted; runtime degraded until manual reconnect",
                "child=$casualty",
                "attempts=${state.attempts}",
            )
            publishChildWatchdogExhaustion(casualty)
        }
        return false
    }
    state.attempts += 1
    state.lastAttemptAtElapsedMs = now
    container.diagnosticsLogger.recordStructured(
        "connection",
        "child process died unexpectedly; rebuilding runtime",
        "child=$casualty",
        "attempt=${state.attempts}",
    )
    return true
}

// The rebuild budget is gone and the child stays dead: say so in the snapshot and the foreground
// notification instead of leaving a healthy-looking CONNECTED — the tunnel itself still stands,
// but the child's lane is down until the user reconnects.
private fun FoxholeVpnService.publishChildWatchdogExhaustion(casualty: String) {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.state != ConnectionState.CONNECTED) {
        return
    }
    val childName = if (casualty == CHILD_WATCHDOG_CASUALTY_I2PD) "I2P" else "Tor"
    bridgeWriter.update(
        snapshot.copy(message = getString(R.string.status_child_component_degraded, childName)),
    )
    updateNotification()
}

private fun FoxholeVpnService.childWatchdogState(): ChildWatchdogState =
    synchronized(childWatchdogStates) {
        childWatchdogStates.getOrPut(this) { ChildWatchdogState() }
    }
