package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

internal class RuntimeStateMachine(
    private val diagnosticsLogger: DiagnosticsLogger?,
    initialState: RuntimeUiState = RuntimeUiState(),
) {
    private val transitionGeneration = AtomicLong(initialState.generation)
    private val stateStore = RuntimeStateStore(initialState)

    val state: StateFlow<RuntimeUiState> = stateStore.state

    fun dispatch(event: RuntimeEvent) {
        stateStore.dispatch(event)
    }

    fun beginTransition(
        reason: String,
        phase: RuntimePhase? = null,
    ): Long {
        val generation = transitionGeneration.incrementAndGet()
        diagnosticsLogger?.recordStructured(
            "runtime",
            "runtime transition generation advanced",
            "generation=$generation",
            "reason=$reason",
        )
        if (phase != null) {
            dispatch(RuntimeEvent.PhaseChanged(generation = generation, phase = phase))
        }
        return generation
    }

    fun isCurrentGeneration(
        generation: Long,
        owner: String,
    ): Boolean {
        val current = currentGeneration()
        if (generation == current) {
            return true
        }
        diagnosticsLogger?.recordStructured(
            "runtime",
            "stale runtime transition ignored",
            "owner=$owner",
            "generation=$generation",
            "current=$current",
        )
        return false
    }

    fun currentGeneration(): Long = transitionGeneration.get()
}
