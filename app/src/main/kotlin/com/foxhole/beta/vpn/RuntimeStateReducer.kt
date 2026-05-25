package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.IpInfo
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

    data class TrafficSampled(val traffic: com.foxhole.beta.core.model.TrafficSnapshot) : RuntimeEvent
}

internal class RuntimeStateStore(initialState: RuntimeUiState = RuntimeUiState()) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<RuntimeUiState> = mutableState

    fun dispatch(event: RuntimeEvent) {
        mutableState.update { current -> reduceRuntimeState(current, event) }
    }
}

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
        is RuntimeEvent.TrafficSampled ->
            state.copy(traffic = event.traffic)
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
