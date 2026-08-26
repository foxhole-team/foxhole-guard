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

private const val CHILD_WATCHDOG_FALLBACK_POLL_MS = 30_000L

internal const val CHILD_WATCHDOG_CONFIRM_TICKS = 2
private const val CHILD_WATCHDOG_MAX_ATTEMPTS = 5

internal const val CHILD_WATCHDOG_CASUALTY_I2PD = "i2pd"

private const val CHILD_WATCHDOG_INCIDENT_RESET_MS = 10 * 60_000L

private class ChildWatchdogState {
    var attempts: Int = 0
    var lastAttemptAtElapsedMs: Long = 0L
    var exhaustionPublished: Boolean = false
}

private val childWatchdogStates = WeakHashMap<FoxholeVpnService, ChildWatchdogState>()

internal class ChildCasualtyTracker(
    private val confirmTicks: Int = CHILD_WATCHDOG_CONFIRM_TICKS,
) {
    private var sawI2pdRunning = false
    private var i2pdKilledTicks = 0

    fun resetTicks() {
        i2pdKilledTicks = 0
    }

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
