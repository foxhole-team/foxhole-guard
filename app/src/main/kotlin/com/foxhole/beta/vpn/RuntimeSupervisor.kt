package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

internal class RuntimeSupervisor(
    scope: CoroutineScope,
    private val diagnosticsLogger: DiagnosticsLogger?,
    private val emergencyKill: suspend (String) -> RuntimeKillResult,
    initialState: RuntimeUiState = RuntimeUiState(),
) {
    private val transitionGeneration = AtomicLong(initialState.generation)
    private val stateStore = RuntimeStateStore(initialState)
    private val commandActor =
        RuntimeCommandActor(
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            emergencyKill = emergencyKill,
        )

    val state: StateFlow<RuntimeUiState> = stateStore.state

    fun dispatch(event: RuntimeEvent) {
        stateStore.dispatch(event)
    }

    fun launch(
        priority: RuntimeCommandPriority,
        reason: String,
        block: suspend () -> Unit,
    ) {
        commandActor.launch(priority = priority, reason = reason, block = block)
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

    fun isCurrentTransition(
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

    fun queueSnapshot(): RuntimeCommandQueueSnapshot =
        commandActor.queueSnapshot()

    fun close() {
        commandActor.close()
    }
}
