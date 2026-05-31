package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooManyFunctions")
internal class RuntimeSupervisor(
    scope: CoroutineScope,
    private val diagnosticsLogger: DiagnosticsLogger?,
    private val emergencyKill: suspend (String) -> RuntimeKillResult,
    initialState: RuntimeUiState = RuntimeUiState(),
) {
    private val transitionGeneration = AtomicLong(initialState.generation)
    private val stateStore = RuntimeStateStore(initialState)
    private val ownershipMutable = MutableStateFlow(RuntimeControlPlaneOwnershipState())
    private val commandActor =
        RuntimeCommandActor(
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            emergencyKill = emergencyKill,
        )

    val state: StateFlow<RuntimeUiState> = stateStore.state
    val ownership: StateFlow<RuntimeControlPlaneOwnershipState> = ownershipMutable

    fun dispatch(event: RuntimeEvent) {
        stateStore.dispatch(event)
    }

    fun dispatch(
        command: RuntimeCommand,
        execute: suspend (RuntimeCommand) -> Unit,
    ) {
        launch(priority = command.priority, reason = command.queueReason) {
            execute(command)
        }
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

    fun setActiveSession(session: VpnSession?) {
        ownershipMutable.update { state ->
            state.copy(
                activeSession = session,
                activeLocalGuardMode = if (session != null) null else state.activeLocalGuardMode,
            )
        }
    }

    fun setActiveLocalGuardMode(mode: LocalGuardMode?) {
        ownershipMutable.update { state ->
            state.copy(
                activeLocalGuardMode = mode,
                activeSession = if (mode != null) null else state.activeSession,
            )
        }
    }

    fun setValidationActive(active: Boolean) {
        ownershipMutable.update { state -> state.copy(validationActive = active) }
    }

    fun setNetworkCallbackRegistered(
        kind: RuntimeNetworkCallbackKind,
        registered: Boolean,
    ) {
        ownershipMutable.update { state ->
            when (kind) {
                RuntimeNetworkCallbackKind.UPSTREAM -> state.copy(networkCallbackRegistered = registered)
                RuntimeNetworkCallbackKind.VPN -> state.copy(vpnNetworkCallbackRegistered = registered)
                RuntimeNetworkCallbackKind.DEFAULT -> state.copy(defaultNetworkCallbackRegistered = registered)
            }
        }
    }

    fun setActiveVpnNetworkHandle(handle: Long?) {
        ownershipMutable.update { state -> state.copy(activeVpnNetworkHandle = handle) }
    }

    fun clearRuntimeOwnership() {
        ownershipMutable.value = RuntimeControlPlaneOwnershipState()
    }

    fun close() {
        commandActor.close()
    }
}
