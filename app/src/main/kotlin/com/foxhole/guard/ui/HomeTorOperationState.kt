package com.foxhole.guard.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tor/I2P operation state: the running-operation banner, the transition prompt and the timeout /
 * background-refresh / protocol-revert jobs the Tor support files juggle. HomeViewModel exposes
 * same-named aliases so the Support-file call sites read unchanged.
 */
internal class HomeTorOperationState {
    val torOperationMutable = MutableStateFlow(HomeTorOperationUiState())
    val torTransitionPromptMutable = MutableStateFlow<TorTransitionPrompt?>(null)
    val torIdentityProbe = TorIdentityProbeTracker()
    var torOperationTimeoutJob: Job? = null
    var torIdentityProbeTimeoutJob: Job? = null
    var torExitBackgroundRefreshJob: Job? = null
    var protocolSwitchRevertJob: Job? = null
    var liveModeSwitchCountdownJob: Job? = null
}

internal enum class TorIdentityProbePhase { IDLE, LOOKING_UP, CONFIRMED, FAILED, CANCELLED }

internal data class TorIdentityProbeState(
    val generation: Long = 0L,
    val phase: TorIdentityProbePhase = TorIdentityProbePhase.IDLE,
)

/** Generation fence for the terminal-facing Tor identity lookup. */
internal class TorIdentityProbeTracker {
    val state = MutableStateFlow(TorIdentityProbeState())
    private var nextGeneration = 0L

    fun restart(): Long {
        val generation = ++nextGeneration
        state.value = TorIdentityProbeState(generation, TorIdentityProbePhase.IDLE)
        return generation
    }

    fun begin(retryAfterFailure: Boolean = false): Long {
        val current = state.value
        return when {
            current.phase == TorIdentityProbePhase.IDLE -> {
                state.value = current.copy(phase = TorIdentityProbePhase.LOOKING_UP)
                current.generation
            }
            retryAfterFailure && current.phase in TERMINAL_FAILURE_PHASES -> {
                val generation = ++nextGeneration
                state.value = TorIdentityProbeState(generation, TorIdentityProbePhase.LOOKING_UP)
                generation
            }
            else -> current.generation
        }
    }

    fun confirm(generation: Long = state.value.generation) {
        val current = state.value
        if (current.generation != generation || current.phase in TERMINAL_FAILURE_PHASES) return
        state.value = current.copy(phase = TorIdentityProbePhase.CONFIRMED)
    }

    fun fail(generation: Long) {
        val current = state.value
        if (current.generation == generation && current.phase == TorIdentityProbePhase.LOOKING_UP) {
            state.value = current.copy(phase = TorIdentityProbePhase.FAILED)
        }
    }

    fun cancel() {
        if (state.value.phase == TorIdentityProbePhase.CANCELLED) return
        val generation = ++nextGeneration
        state.value = TorIdentityProbeState(generation, TorIdentityProbePhase.CANCELLED)
    }

    private companion object {
        val TERMINAL_FAILURE_PHASES = setOf(TorIdentityProbePhase.FAILED, TorIdentityProbePhase.CANCELLED)
    }
}
