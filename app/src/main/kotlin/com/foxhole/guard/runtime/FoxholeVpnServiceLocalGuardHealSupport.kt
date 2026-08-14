package com.foxhole.guard.runtime

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeAutoReconnectPolicy
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.runtimeProfileName
import com.foxhole.guard.R
import com.foxhole.guard.cancelGuardHealSafetyNet
import com.foxhole.guard.enqueueGuardHealSafetyNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Self-heal for the local guard (firewall / system-DNS replacement). Unlike a profile tunnel — which
 * only auto-reconnects when the user opted in — the guard is meant to be always-on protection, so a
 * dropped VPN network (or a boot start that raced ahead of connectivity) must retry on its own
 * instead of silently sitting in ERROR until the app is reopened. Retries are bounded by the shared
 * backoff policy; only a real restart dispatch consumes the attempt budget (waiting out an offline
 * device does not), and on exhaustion the guard lands in ERROR with a tap-to-restart notification.
 *
 * NOTE: this changes runtime lifecycle behaviour and must be smoke-tested on-device (drop wifi under
 * a live firewall; boot with no network) before release.
 */

// The first reconnect backoff is zero, which suits an immediate tunnel retry, but re-arming the
// same attempt with zero delay while offline turned into a hot spin on Dispatchers.Default that
// flooded the journal. With no network there is nothing for guard to protect, so poll every 15s
// and do not journal each tick.
private const val LOCAL_GUARD_HEAL_OFFLINE_POLL_MS = 15_000L
private const val LOCAL_GUARD_HEAL_STILL_OFFLINE_REASON = "still_offline"

// Floor for the out-of-process safety net: while the service lives, the in-process heal wins and
// the worker finds guard already up, no-oping honestly. An immediate worker would merely duplicate
// the first attempt.
private const val LOCAL_GUARD_HEAL_SAFETY_NET_MIN_DELAY_MS = 15_000L

private data class LocalGuardHealState(
    var job: Job? = null,
    var attempts: Int = 0,
)

private val localGuardHealStates = WeakHashMap<FoxholeVpnService, LocalGuardHealState>()

private fun FoxholeVpnService.localGuardHealState(): LocalGuardHealState =
    synchronized(localGuardHealStates) {
        localGuardHealStates.getOrPut(this) { LocalGuardHealState() }
    }

internal fun FoxholeVpnService.cancelLocalGuardHeal(
    resetAttempts: Boolean,
    cancelSafetyNet: Boolean = true,
) {
    val state = localGuardHealState()
    state.job?.cancel()
    state.job = null
    if (resetAttempts) {
        state.attempts = 0
    }
    // onDestroy passes false: the in-process job dies with the service scope anyway, and the
    // out-of-process net is the only thing that carries the retry through an error teardown, which
    // always ends in stopService. Only deliberate cancels drop the net.
    if (cancelSafetyNet) {
        cancelGuardHealSafetyNet()
    }
}

internal fun FoxholeVpnService.scheduleLocalGuardHeal(
    mode: LocalGuardMode,
    reason: String,
) {
    val state = localGuardHealState()
    if (state.job?.isActive == true) {
        return
    }
    if (container.settingsRepository.settings.value.localGuardModeOrNull() != mode) {
        // The user turned the guard off (or switched mode) during the outage — stop healing.
        cancelLocalGuardHeal(resetAttempts = true)
        return
    }
    val nextAttempt = state.attempts + 1
    if (nextAttempt > RuntimeAutoReconnectPolicy.MAX_ATTEMPTS) {
        publishLocalGuardHealExhausted(mode, reason)
        return
    }
    val stillOffline = reason == LOCAL_GUARD_HEAL_STILL_OFFLINE_REASON
    val delayMs =
        RuntimeAutoReconnectPolicy.jitteredBackoffDelayMs(nextAttempt)
            .coerceAtLeast(if (stillOffline) LOCAL_GUARD_HEAL_OFFLINE_POLL_MS else 0L)
    // Every arming also moves the out-of-process net: an error teardown kills the service together
    // with the job below, leaving the worker to carry the retry.
    enqueueGuardHealSafetyNet(delayMs.coerceAtLeast(LOCAL_GUARD_HEAL_SAFETY_NET_MIN_DELAY_MS))
    if (!stillOffline) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "local guard heal scheduled",
            "mode=${mode.name.lowercase()}",
            "reason=$reason",
            "attempt=$nextAttempt",
            "delay_ms=$delayMs",
        )
    }
    state.job =
        scope.launch(Dispatchers.Default) {
            delay(delayMs)
            if (container.settingsRepository.settings.value.localGuardModeOrNull() != mode) {
                cancelLocalGuardHeal(resetAttempts = true)
                return@launch
            }
            state.job = null
            if (!defaultNetworkAvailable) {
                // No network to protect yet: re-arm the SAME attempt so a long outage never burns the
                // budget — a firewall with no connectivity has nothing to leak. The attempt only counts
                // once we actually dispatch a restart below.
                scheduleLocalGuardHeal(mode, LOCAL_GUARD_HEAL_STILL_OFFLINE_REASON)
                return@launch
            }
            state.attempts = nextAttempt
            FoxholeConnectionServiceContract.startForegroundService(
                context = this@scheduleLocalGuardHeal,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = mode,
            )
        }
}

private fun FoxholeVpnService.publishLocalGuardHealExhausted(
    mode: LocalGuardMode,
    reason: String,
) {
    cancelLocalGuardHeal(resetAttempts = true)
    container.diagnosticsLogger.recordStructured(
        "connection",
        "local guard heal exhausted",
        "mode=${mode.name.lowercase()}",
        "reason=$reason",
    )
    bridgeWriter.update(
        ConnectionSnapshot(
            state = ConnectionState.ERROR,
            trafficMode = TrafficMode.TUNNEL,
            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            profileName = mode.runtimeProfileName(),
            message = getString(R.string.error_runtime_stopped),
            reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
        ),
    )
    updateNotification()
}
