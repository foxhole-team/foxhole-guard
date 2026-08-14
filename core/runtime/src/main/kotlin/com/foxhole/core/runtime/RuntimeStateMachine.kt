package com.foxhole.core.runtime

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// Consolidated runtime state machine: the UI-state model, the event/reducer/store, and the
// generation-tracking state machine. Previously split across RuntimeUiState / RuntimeStateReducer /
// RuntimeStateMachine. Behaviour is unchanged.

internal class RuntimeStateMachine(
    private val diagnosticsLogger: RuntimeDiagnosticsSink?,
    initialState: RuntimeUiState = RuntimeUiState(),
) {
    // Last transition token minted FOR this machine (by RuntimeSupervisor from the shared
    // RuntimeGenerationClock). Staleness is equality against this value, so unrelated clock
    // activity (commands, child-process lifecycles) never invalidates a live UI transition.
    @Volatile
    private var lastTransitionGeneration: Long = initialState.generation

    private val stateStore = RuntimeStateStore(initialState)

    val state: StateFlow<RuntimeUiState> = stateStore.state

    fun dispatch(event: RuntimeEvent) {
        stateStore.dispatch(event)
    }

    fun adoptTransition(
        generation: Long,
        reason: String,
        phase: RuntimePhase? = null,
    ) {
        lastTransitionGeneration = generation
        diagnosticsLogger?.recordStructured(
            "runtime",
            "runtime transition generation advanced",
            "generation=$generation",
            "reason=$reason",
        )
        if (phase != null) {
            dispatch(RuntimeEvent.PhaseChanged(generation = generation, phase = phase))
        }
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

    fun currentGeneration(): Long = lastTransitionGeneration
}

sealed interface RuntimeEvent {
    data class PhaseChanged(
        val generation: Long,
        val phase: RuntimePhase,
    ) : RuntimeEvent

    data class IpRefreshRequested(
        val generation: Long,
        val target: RuntimeIpRefreshTarget,
        val reason: RuntimeIpRefreshReason,
    ) : RuntimeEvent

    data class IpProbeFinished(
        val generation: Long,
        val target: RuntimeIpRefreshTarget,
        val result: Result<IpInfo>,
    ) : RuntimeEvent

    data class NativeStarted(
        val generation: Long,
        val sessionId: String?,
    ) : RuntimeEvent

    data class NativeStopped(
        val generation: Long,
        val sessionId: String?,
    ) : RuntimeEvent

    data class NativeCleanupUnresolved(
        val generation: Long,
        val message: String,
    ) : RuntimeEvent

    data class VpnNetworkAvailable(
        val generation: Long,
        val networkHandle: Long,
    ) : RuntimeEvent

    data class VpnNetworkLost(
        val generation: Long,
        val networkHandle: Long,
    ) : RuntimeEvent

    data class UpstreamChanged(
        val generation: Long,
        val networkHandle: Long?,
        val revision: Long,
    ) : RuntimeEvent

    data class ValidationStarted(
        val generation: Long,
        val sessionId: String?,
    ) : RuntimeEvent

    data class ValidationSucceeded(
        val generation: Long,
        val sessionId: String?,
        val vpnNetworkHandle: Long?,
    ) : RuntimeEvent

    data class ValidationFailed(
        val generation: Long,
        val sessionId: String?,
        val message: String,
    ) : RuntimeEvent

    data class TorStateChanged(
        val generation: Long,
        val tor: RuntimeTorUiState,
    ) : RuntimeEvent
}

internal class RuntimeStateStore(initialState: RuntimeUiState = RuntimeUiState()) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<RuntimeUiState> = mutableState

    fun dispatch(event: RuntimeEvent) {
        mutableState.update { current -> reduceRuntimeState(current, event) }
    }
}

@Suppress("CyclomaticComplexMethod")
internal fun reduceRuntimeState(
    state: RuntimeUiState,
    event: RuntimeEvent,
): RuntimeUiState =
    when (event) {
        is RuntimeEvent.PhaseChanged ->
            state.copy(
                generation = event.generation,
                phase = event.phase,
                error = (event.phase as? RuntimePhase.Error)?.reason,
            )
        is RuntimeEvent.IpRefreshRequested ->
            if (event.generation != state.generation) {
                state
            } else {
                state.copy(ip = state.ip.withPanel(event.target, state.ip.loadingPanel(event.target, event.reason)))
            }
        is RuntimeEvent.IpProbeFinished ->
            if (event.generation != state.generation) {
                state
            } else {
                val panel =
                    event.result.fold(
                        onSuccess = { info ->
                            RuntimeIpPanelState.Ready(
                                target = event.target,
                                info = info,
                            )
                        },
                        onFailure = { error ->
                            RuntimeIpPanelState.Failed(
                                target = event.target,
                                previous = state.ip.panel(event.target).previousOrReadyInfo,
                                message = error.message ?: error.javaClass.simpleName,
                            )
                        },
                    )
                state.copy(ip = state.ip.withPanel(event.target, panel))
            }
        is RuntimeEvent.NativeStarted ->
            state.whenCurrent(event.generation) {
                copy(sessionId = event.sessionId, phase = RuntimePhase.WaitingVpnNetwork)
            }
        is RuntimeEvent.NativeStopped ->
            state.whenCurrent(event.generation) {
                copy(sessionId = null, phase = RuntimePhase.Idle)
            }
        is RuntimeEvent.NativeCleanupUnresolved ->
            state.whenCurrent(event.generation) {
                copy(
                    phase = RuntimePhase.Error(RuntimeErrorUi(message = event.message)),
                    error = RuntimeErrorUi(message = event.message),
                )
            }
        is RuntimeEvent.VpnNetworkAvailable ->
            state.whenCurrent(event.generation) {
                copy(
                    network = network.copy(vpnNetworkHandle = event.networkHandle),
                    phase = RuntimePhase.ValidatingTunnel,
                )
            }
        is RuntimeEvent.VpnNetworkLost ->
            state.whenCurrent(event.generation) {
                if (network.vpnNetworkHandle == event.networkHandle) {
                    copy(network = network.copy(vpnNetworkHandle = null))
                } else {
                    this
                }
            }
        is RuntimeEvent.UpstreamChanged ->
            state.whenCurrent(event.generation) {
                copy(
                    network =
                    network.copy(
                        upstreamNetworkHandle = event.networkHandle,
                        upstreamNetworkRevision = event.revision,
                    ),
                )
            }
        is RuntimeEvent.ValidationStarted ->
            state.whenCurrent(event.generation) {
                copy(sessionId = event.sessionId, phase = RuntimePhase.ValidatingTunnel)
            }
        is RuntimeEvent.ValidationSucceeded ->
            state.whenCurrent(event.generation) {
                copy(
                    sessionId = event.sessionId,
                    phase = RuntimePhase.Connected,
                    network = network.copy(vpnNetworkHandle = event.vpnNetworkHandle ?: network.vpnNetworkHandle),
                    error = null,
                )
            }
        is RuntimeEvent.ValidationFailed ->
            state.whenCurrent(event.generation) {
                val error = RuntimeErrorUi(message = event.message)
                copy(sessionId = event.sessionId, phase = RuntimePhase.Error(error), error = error)
            }
        is RuntimeEvent.TorStateChanged ->
            state.whenCurrent(event.generation) {
                copy(tor = event.tor)
            }
    }

private inline fun RuntimeUiState.whenCurrent(
    generation: Long,
    block: RuntimeUiState.() -> RuntimeUiState,
): RuntimeUiState =
    if (generation == this.generation) {
        block()
    } else {
        this
    }

private fun RuntimeIpState.loadingPanel(
    target: RuntimeIpRefreshTarget,
    reason: RuntimeIpRefreshReason,
): RuntimeIpPanelState =
    RuntimeIpPanelState.Loading(
        target = target,
        previous = panel(target).previousOrReadyInfo,
        reason = reason,
    )

fun RuntimeIpState.panel(target: RuntimeIpRefreshTarget): RuntimeIpPanelState =
    when (target) {
        RuntimeIpRefreshTarget.DEVICE -> device
        RuntimeIpRefreshTarget.TUNNEL -> tunnel
        RuntimeIpRefreshTarget.TOR -> tor
    }

fun RuntimeIpState.withPanel(
    target: RuntimeIpRefreshTarget,
    panel: RuntimeIpPanelState,
): RuntimeIpState =
    when (target) {
        RuntimeIpRefreshTarget.DEVICE -> copy(device = panel)
        RuntimeIpRefreshTarget.TUNNEL -> copy(tunnel = panel)
        RuntimeIpRefreshTarget.TOR -> copy(tor = panel)
    }

data class RuntimeUiState(
    val generation: Long = 0L,
    val sessionId: String? = null,
    val mode: TrafficMode = TrafficMode.TUNNEL,
    val phase: RuntimePhase = RuntimePhase.Idle,
    val profile: RuntimeProfileUi? = null,
    val network: RuntimeNetworkState = RuntimeNetworkState(),
    val ip: RuntimeIpState = RuntimeIpState.empty(),
    val tor: RuntimeTorUiState = RuntimeTorUiState.Off,
    val error: RuntimeErrorUi? = null,
)

sealed interface RuntimePhase {
    data object Idle : RuntimePhase
    data object Preparing : RuntimePhase
    data object StartingNative : RuntimePhase
    data object WaitingVpnNetwork : RuntimePhase
    data object ValidatingTunnel : RuntimePhase
    data object Connected : RuntimePhase
    data object Reconnecting : RuntimePhase
    data object Reloading : RuntimePhase
    data object Stopping : RuntimePhase
    data object Killing : RuntimePhase
    data class Error(val reason: RuntimeErrorUi) : RuntimePhase
}

data class RuntimeProfileUi(
    val profileId: Long,
    val profileName: String?,
    val protocolHint: ProtocolHint?,
    val protocolOptionId: String?,
)

data class RuntimeNetworkState(
    val upstreamNetworkRevision: Long = 0L,
    val vpnNetworkHandle: Long? = null,
    val upstreamNetworkHandle: Long? = null,
)

data class RuntimeErrorUi(
    val message: String,
    val reasonCode: AutoConnectReasonCode? = null,
)

data class RuntimeIpState(
    val device: RuntimeIpPanelState,
    val tunnel: RuntimeIpPanelState,
    val tor: RuntimeIpPanelState,
) {
    companion object {
        fun empty(): RuntimeIpState =
            RuntimeIpState(
                device = RuntimeIpPanelState.Hidden,
                tunnel = RuntimeIpPanelState.Hidden,
                tor = RuntimeIpPanelState.Hidden,
            )
    }
}

sealed interface RuntimeIpPanelState {
    data object Hidden : RuntimeIpPanelState

    data class Loading(
        val target: RuntimeIpRefreshTarget,
        val previous: IpInfo?,
        val reason: RuntimeIpRefreshReason,
    ) : RuntimeIpPanelState

    data class Ready(
        val target: RuntimeIpRefreshTarget,
        val info: IpInfo,
        val stale: Boolean = false,
    ) : RuntimeIpPanelState

    data class Failed(
        val target: RuntimeIpRefreshTarget,
        val previous: IpInfo?,
        val message: String,
    ) : RuntimeIpPanelState
}

enum class RuntimeIpRefreshTarget {
    DEVICE,
    TUNNEL,
    TOR,
}

enum class RuntimeIpRefreshReason {
    FIRST_LOAD,
    POST_CONNECT,
    POST_UPDATE,
    FOREGROUND,
    NETWORK_CHANGE,
    MANUAL,
    TOR_ROUTE,
    LEGACY_BRIDGE,
}

sealed interface RuntimeTorUiState {
    data object Off : RuntimeTorUiState
    data class Starting(val sinceMs: Long) : RuntimeTorUiState
    data class Bootstrapping(val progress: Int?, val previousExit: IpInfo?) : RuntimeTorUiState
    data class Ready(val exit: IpInfo, val circuitId: String?) : RuntimeTorUiState
}

val RuntimeIpPanelState.previousOrReadyInfo: IpInfo?
    get() =
        when (this) {
            RuntimeIpPanelState.Hidden -> null
            is RuntimeIpPanelState.Failed -> previous
            is RuntimeIpPanelState.Loading -> previous
            is RuntimeIpPanelState.Ready -> info
        }

internal fun runtimeUiStateFromBridge(
    previous: RuntimeUiState,
    snapshot: ConnectionSnapshot,
    ipInfo: IpInfo?,
    deviceIpInfo: IpInfo?,
    pendingIpRefreshReason: RuntimeIpRefreshReason? = null,
    torPhase: TorPhaseSnapshot = TorPhaseSnapshot(),
    torExit: IpInfo? = null,
): RuntimeUiState {
    val error = snapshot.runtimeError()
    val phase = snapshot.runtimePhase(error)
    val target = snapshot.runtimeIpTarget()
    val devicePanel =
        bridgeIpPanel(
            target = RuntimeIpRefreshTarget.DEVICE,
            activeTarget = target,
            info = deviceIpInfo,
            previous = previous.ip.device,
            phase = phase,
            pendingRefreshReason = pendingIpRefreshReason,
        )
    val tunnelPanel =
        bridgeIpPanel(
            target = RuntimeIpRefreshTarget.TUNNEL,
            activeTarget = target,
            info = ipInfo.takeUnless { target == RuntimeIpRefreshTarget.TOR },
            previous = previous.ip.tunnel,
            phase = phase,
            pendingRefreshReason = pendingIpRefreshReason,
        )
    val torPanel =
        bridgeIpPanel(
            target = RuntimeIpRefreshTarget.TOR,
            activeTarget = target,
            info = ipInfo.takeIf { target == RuntimeIpRefreshTarget.TOR },
            previous = previous.ip.tor,
            phase = phase,
            pendingRefreshReason = pendingIpRefreshReason,
        )
    val ipState =
        RuntimeIpState(
            device = devicePanel,
            tunnel = tunnelPanel,
            tor = torPanel,
        )
    return RuntimeUiState(
        generation = snapshot.lastChangeAt,
        mode = snapshot.trafficMode,
        phase = phase,
        profile = snapshot.runtimeProfileUi(),
        network = RuntimeNetworkState(upstreamNetworkRevision = snapshot.upstreamNetworkRevision),
        ip = ipState,
        tor = bridgeTorState(previous = previous.tor, phase = torPhase, exit = torExit),
        error = error,
    )
}

/**
 * The published Tor state, derived from the bridge's live feed.
 *
 * It used to be `previous.tor`, which — with [RuntimeEvent.TorStateChanged] having no producer
 * anywhere — pinned it to [RuntimeTorUiState.Off] for the lifetime of the process: every Tor status
 * derived from it was a branch that could not be reached. The two inputs here are the ones the
 * runtime actually maintains: the bootstrap phase (log tail / control probe) and the Tor-route exit
 * observed by tunnel validation.
 *
 * CONNECTED without an exit yet stays [RuntimeTorUiState.Bootstrapping] rather than claiming
 * readiness: "Tor is up" and "traffic demonstrably leaves through Tor" are different statements,
 * and the second one is the one worth showing.
 */
private fun bridgeTorState(
    previous: RuntimeTorUiState,
    phase: TorPhaseSnapshot,
    exit: IpInfo?,
): RuntimeTorUiState {
    val previousExit = previous.lastKnownExit
    return when (phase.phase) {
        TorNetworkPhase.OFFLINE -> RuntimeTorUiState.Off
        TorNetworkPhase.CONNECTING ->
            when (val progress = phase.progress) {
                null -> RuntimeTorUiState.Starting(sinceMs = phase.startedAt)
                else -> RuntimeTorUiState.Bootstrapping(progress = progress, previousExit = previousExit)
            }
        TorNetworkPhase.BUILDING_CIRCUITS ->
            RuntimeTorUiState.Bootstrapping(progress = phase.progress, previousExit = previousExit)
        TorNetworkPhase.CONNECTED ->
            when (exit) {
                null -> RuntimeTorUiState.Bootstrapping(progress = phase.progress, previousExit = previousExit)
                else -> RuntimeTorUiState.Ready(exit = exit, circuitId = phase.bootstrapTag)
            }
    }
}

private val RuntimeTorUiState.lastKnownExit: IpInfo?
    get() =
        when (this) {
            RuntimeTorUiState.Off -> null
            is RuntimeTorUiState.Starting -> null
            is RuntimeTorUiState.Bootstrapping -> previousExit
            is RuntimeTorUiState.Ready -> exit
        }

private fun bridgeIpPanel(
    target: RuntimeIpRefreshTarget,
    activeTarget: RuntimeIpRefreshTarget,
    info: IpInfo?,
    previous: RuntimeIpPanelState,
    phase: RuntimePhase,
    pendingRefreshReason: RuntimeIpRefreshReason?,
): RuntimeIpPanelState {
    val previousInfo = previous.previousOrReadyInfo
    return when {
        target == activeTarget && pendingRefreshReason != null && phase.isActiveRuntimePhase() ->
            RuntimeIpPanelState.Loading(
                target = target,
                previous = info ?: previousInfo,
                reason = pendingRefreshReason,
            )
        info != null -> RuntimeIpPanelState.Ready(target = target, info = info)
        target == activeTarget && phase.isActiveRuntimePhase() ->
            RuntimeIpPanelState.Loading(
                target = target,
                previous = previousInfo,
                reason = RuntimeIpRefreshReason.LEGACY_BRIDGE,
            )
        else -> RuntimeIpPanelState.Hidden
    }
}

private fun RuntimePhase.isActiveRuntimePhase(): Boolean =
    when (this) {
        RuntimePhase.StartingNative,
        RuntimePhase.WaitingVpnNetwork,
        RuntimePhase.ValidatingTunnel,
        RuntimePhase.Connected,
        RuntimePhase.Reconnecting,
        RuntimePhase.Reloading,
        -> true
        RuntimePhase.Idle,
        RuntimePhase.Killing,
        RuntimePhase.Preparing,
        RuntimePhase.Stopping,
        is RuntimePhase.Error,
        -> false
    }

private fun ConnectionSnapshot.runtimePhase(error: RuntimeErrorUi?): RuntimePhase =
    when (state) {
        ConnectionState.IDLE -> RuntimePhase.Idle
        ConnectionState.CONNECTING -> RuntimePhase.StartingNative
        ConnectionState.CONNECTED -> RuntimePhase.Connected
        ConnectionState.RECONNECTING -> RuntimePhase.Reconnecting
        ConnectionState.DISCONNECTING -> RuntimePhase.Stopping
        ConnectionState.ERROR -> RuntimePhase.Error(error ?: RuntimeErrorUi(message = message.orEmpty()))
    }

private fun ConnectionSnapshot.runtimeError(): RuntimeErrorUi? =
    message
        ?.takeIf { state == ConnectionState.ERROR && it.isNotBlank() }
        ?.let { RuntimeErrorUi(message = it, reasonCode = reasonCode) }

private fun ConnectionSnapshot.runtimeProfileUi(): RuntimeProfileUi? =
    profileId?.let { id ->
        RuntimeProfileUi(
            profileId = id,
            profileName = profileName,
            protocolHint = protocolHint,
            protocolOptionId = protocolOptionId,
        )
    }

private fun ConnectionSnapshot.runtimeIpTarget(): RuntimeIpRefreshTarget =
    when {
        profileId == TOR_ONLY_PROFILE_ID -> RuntimeIpRefreshTarget.TOR
        state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            trafficMode == TrafficMode.TUNNEL -> RuntimeIpRefreshTarget.TUNNEL
        else -> RuntimeIpRefreshTarget.DEVICE
    }
