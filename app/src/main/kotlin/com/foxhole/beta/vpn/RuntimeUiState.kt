package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.TrafficMode

internal data class RuntimeUiState(
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

internal sealed interface RuntimePhase {
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

internal data class RuntimeProfileUi(
    val profileId: Long,
    val profileName: String?,
    val protocolHint: ProtocolHint?,
    val protocolOptionId: String?,
)

internal data class RuntimeNetworkState(
    val upstreamNetworkRevision: Long = 0L,
    val vpnNetworkHandle: Long? = null,
    val upstreamNetworkHandle: Long? = null,
)

internal data class RuntimeErrorUi(
    val message: String,
    val reasonCode: AutoConnectReasonCode? = null,
)

internal data class RuntimeIpState(
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

internal sealed interface RuntimeIpPanelState {
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

internal enum class RuntimeIpRefreshTarget {
    DEVICE,
    TUNNEL,
    TOR,
}

internal enum class RuntimeIpRefreshReason {
    FIRST_LOAD,
    POST_CONNECT,
    POST_UPDATE,
    FOREGROUND,
    NETWORK_CHANGE,
    MANUAL,
    TOR_ROUTE,
    LEGACY_BRIDGE,
}

internal sealed interface RuntimeTorUiState {
    data object Off : RuntimeTorUiState
    data class Starting(val sinceMs: Long) : RuntimeTorUiState
    data class Bootstrapping(val progress: Int?, val previousExit: IpInfo?) : RuntimeTorUiState
    data class Ready(val exit: IpInfo, val circuitId: String?) : RuntimeTorUiState
    data class Rotating(val previousExit: IpInfo) : RuntimeTorUiState
    data class Failed(val previousExit: IpInfo?, val message: String) : RuntimeTorUiState
}

internal val RuntimeIpPanelState.previousOrReadyInfo: IpInfo?
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
        tor = previous.tor,
        error = error,
    )
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
        profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> RuntimeIpRefreshTarget.TOR
        state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            trafficMode == TrafficMode.TUNNEL -> RuntimeIpRefreshTarget.TUNNEL
        else -> RuntimeIpRefreshTarget.DEVICE
    }
