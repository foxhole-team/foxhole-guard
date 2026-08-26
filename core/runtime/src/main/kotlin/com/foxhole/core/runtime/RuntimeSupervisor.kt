package com.foxhole.core.runtime

import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RuntimeCommand {
    val source: RuntimeCommandSource

    data class StartTunnel(
        val profileId: Long,
        val optionId: String? = null,
        val previousVpnNetworkHandle: Long? = null,
        override val source: RuntimeCommandSource,
        val subscriptionRefreshPrepared: Boolean = false,
        val protocolTestTrafficFreeze: Boolean = false,
        val replaceActiveTunnel: Boolean = false,
    ) : RuntimeCommand

    data class StartProxy(
        val profileId: Long,
        val optionId: String? = null,
        override val source: RuntimeCommandSource,
        val subscriptionRefreshPrepared: Boolean = false,
        val protocolTestTrafficFreeze: Boolean = false,
        val replaceActiveTunnel: Boolean = false,
    ) : RuntimeCommand

    data class StartLocalGuard(
        val mode: LocalGuardMode,
        override val source: RuntimeCommandSource,

        val configStamp: Int = 0,
    ) : RuntimeCommand

    data class Reload(
        val reason: String,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class EnforceQuarantine(
        val requestedRevision: Long,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class Restore(
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class Stop(
        val reason: String,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class Kill(
        val reason: String,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand
}

enum class RuntimeCommandSource {
    USER,
    SERVICE,
    TILE,
    BOOT,
    AUTO_RECONNECT,
    SYSTEM,
}

val RuntimeCommand.priority: RuntimeCommandPriority
    get() =
        when (this) {
            is RuntimeCommand.Kill -> RuntimeCommandPriority.KILL
            is RuntimeCommand.Stop -> RuntimeCommandPriority.USER_STOP

            is RuntimeCommand.Reload -> RuntimeCommandPriority.NORMAL
            is RuntimeCommand.EnforceQuarantine -> RuntimeCommandPriority.NORMAL
            is RuntimeCommand.Restore -> RuntimeCommandPriority.NORMAL
            is RuntimeCommand.StartLocalGuard,
            is RuntimeCommand.StartProxy,
            is RuntimeCommand.StartTunnel,
            -> RuntimeCommandPriority.SWITCH
        }

val RuntimeCommand.queueReason: String
    get() =
        when (this) {
            is RuntimeCommand.Kill -> reason
            is RuntimeCommand.Reload -> "reload:$reason"
            is RuntimeCommand.EnforceQuarantine -> "enforce_quarantine"
            is RuntimeCommand.Restore -> "restore"
            is RuntimeCommand.StartLocalGuard -> "local_guard:${mode.name.lowercase()}:cfg=$configStamp"
            is RuntimeCommand.StartProxy ->
                "proxy:$profileId:${optionId ?: "default"}" +
                    (if (subscriptionRefreshPrepared) ":subscription_prepared" else "") +
                    (if (protocolTestTrafficFreeze) ":protocol_test_freeze" else "") +
                    (if (replaceActiveTunnel) ":replace_active" else "")
            is RuntimeCommand.StartTunnel ->
                "connect:$profileId:${optionId ?: "default"}" +
                    (if (subscriptionRefreshPrepared) ":subscription_prepared" else "") +
                    (if (protocolTestTrafficFreeze) ":protocol_test_freeze" else "") +
                    (if (replaceActiveTunnel) ":replace_active" else "")
            is RuntimeCommand.Stop -> reason
        }

data class RuntimeControlPlaneOwnershipState(
    val activeMode: TrafficMode? = null,
    val activeSession: VpnSession? = null,
    val activeLocalGuardMode: LocalGuardMode? = null,
    val validationActive: Boolean = false,
    val networkCallbackRegistered: Boolean = false,
    val vpnNetworkCallbackRegistered: Boolean = false,
    val defaultNetworkCallbackRegistered: Boolean = false,
    val activeVpnNetworkHandle: Long? = null,
    val networkActivityLoggingSuspended: Boolean = false,
)

enum class RuntimeNetworkCallbackKind {
    UPSTREAM,
    VPN,
    DEFAULT,
}

class RuntimeCommandOwner internal constructor(
    val id: Long,
    val mode: TrafficMode,
    val label: String,
) {
    private val closed = AtomicBoolean(false)

    internal val isClosed: Boolean
        get() = closed.get()

    internal fun close() {
        closed.set(true)
    }
}

@Suppress("TooManyFunctions")
class RuntimeSupervisor(
    scope: CoroutineScope,
    private val diagnosticsLogger: RuntimeDiagnosticsSink?,
    private val emergencyKill: suspend (String) -> RuntimeKillResult,
    initialState: RuntimeUiState = RuntimeUiState(),
    diagnosticsRecorder: RuntimeCommandDiagnosticsRecorder? = null,
) {
    init {
        RuntimeGenerationClock.advanceTo(initialState.generation)
    }

    private val stateMachine =
        RuntimeStateMachine(
            diagnosticsLogger = diagnosticsLogger,
            initialState = initialState,
        )
    private val ownershipMutable = MutableStateFlow(RuntimeControlPlaneOwnershipState())
    private val mailbox =
        RuntimeSupervisorMailbox(
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            emergencyKill = emergencyKill,
            diagnosticsRecorder = diagnosticsRecorder,
        )

    val state: StateFlow<RuntimeUiState> = stateMachine.state
    val ownership: StateFlow<RuntimeControlPlaneOwnershipState> = ownershipMutable

    fun dispatch(event: RuntimeEvent) {
        stateMachine.dispatch(event)
    }

    fun dispatch(
        command: RuntimeCommand,
        owner: RuntimeCommandOwner? = null,
        execute: suspend (RuntimeCommand) -> Unit,
    ) {
        launch(priority = command.priority, reason = command.queueReason, owner = owner) {
            execute(command)
        }
    }

    fun launch(
        priority: RuntimeCommandPriority,
        reason: String,
        owner: RuntimeCommandOwner? = null,
        block: suspend () -> Unit,
    ) {
        mailbox.launch(
            priority = priority,
            reason = reason,
            owner = owner,
            block = block,
        )
    }

    fun openCommandOwner(
        mode: TrafficMode,
        label: String,
    ): RuntimeCommandOwner =
        RuntimeCommandOwner(
            id = RuntimeGenerationClock.next(),
            mode = mode,
            label = label,
        )

    fun closeCommandOwner(owner: RuntimeCommandOwner) {
        owner.close()
        mailbox.cancelOwner(owner)
    }

    fun beginTransition(
        reason: String,
        phase: RuntimePhase? = null,
    ): Long {
        val generation = RuntimeGenerationClock.next()
        stateMachine.adoptTransition(generation = generation, reason = reason, phase = phase)
        return generation
    }

    fun isCurrentTransition(
        generation: Long,
        owner: String,
    ): Boolean = stateMachine.isCurrentGeneration(generation = generation, owner = owner)

    fun currentGeneration(): Long = stateMachine.currentGeneration()

    fun queueSnapshot(): RuntimeCommandQueueSnapshot =
        mailbox.queueSnapshot()

    fun activeSessionFor(mode: TrafficMode): VpnSession? =
        ownershipMutable.value
            .takeIf { state -> state.activeMode == mode }
            ?.activeSession

    fun setActiveSession(
        session: VpnSession?,
        mode: TrafficMode = TrafficMode.TUNNEL,
    ) {
        ownershipMutable.update { state ->
            when {
                session != null ->
                    state.copy(
                        activeMode = mode,
                        activeSession = session,
                        activeLocalGuardMode = null,
                    )
                state.activeMode == mode ->
                    state.copy(
                        activeMode = if (state.activeLocalGuardMode == null) null else state.activeMode,
                        activeSession = null,
                    )
                else -> state
            }
        }
    }

    fun setActiveLocalGuardMode(localGuardMode: LocalGuardMode?) {
        ownershipMutable.update { state ->
            when {
                localGuardMode != null ->
                    state.copy(
                        activeMode = TrafficMode.TUNNEL,
                        activeSession = null,
                        activeLocalGuardMode = localGuardMode,
                    )
                state.activeMode == TrafficMode.TUNNEL ->
                    state.copy(
                        activeMode = if (state.activeSession == null) null else state.activeMode,
                        activeLocalGuardMode = null,
                    )
                else -> state
            }
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

    fun setNetworkActivityLoggingSuspended(suspended: Boolean) {
        ownershipMutable.update { state -> state.copy(networkActivityLoggingSuspended = suspended) }
    }

    fun clearRuntimeOwnership(mode: TrafficMode? = null) {
        ownershipMutable.update { state ->
            if (mode == null || state.activeMode == null || state.activeMode == mode) {
                RuntimeControlPlaneOwnershipState()
            } else {
                state
            }
        }
    }

    fun close() {
        mailbox.close()
    }
}
