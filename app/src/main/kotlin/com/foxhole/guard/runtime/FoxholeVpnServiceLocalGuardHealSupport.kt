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

private const val LOCAL_GUARD_HEAL_OFFLINE_POLL_MS = 15_000L
private const val LOCAL_GUARD_HEAL_STILL_OFFLINE_REASON = "still_offline"

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
