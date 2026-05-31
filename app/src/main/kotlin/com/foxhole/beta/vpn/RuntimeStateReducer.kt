package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal sealed interface RuntimeEvent {
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

    data class TrafficSampled(val traffic: TrafficSnapshot) : RuntimeEvent
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
        is RuntimeEvent.TrafficSampled ->
            state.copy(traffic = event.traffic)
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

internal fun RuntimeIpState.panel(target: RuntimeIpRefreshTarget): RuntimeIpPanelState =
    when (target) {
        RuntimeIpRefreshTarget.DEVICE -> device
        RuntimeIpRefreshTarget.TUNNEL -> tunnel
        RuntimeIpRefreshTarget.TOR -> tor
    }

internal fun RuntimeIpState.withPanel(
    target: RuntimeIpRefreshTarget,
    panel: RuntimeIpPanelState,
): RuntimeIpState =
    when (target) {
        RuntimeIpRefreshTarget.DEVICE -> copy(device = panel)
        RuntimeIpRefreshTarget.TUNNEL -> copy(tunnel = panel)
        RuntimeIpRefreshTarget.TOR -> copy(tor = panel)
    }
