package com.foxhole.core.runtime

import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LocalProxyStatusSnapshot
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.retainKnownDetailsFrom
import com.foxhole.core.model.samePrimaryAddress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.net.Socket

object FoxholeVpnRuntimeBridge {
    internal val writeLock = Any()

    @Volatile
    var onWriteRejected: ((mode: TrafficMode, incoming: ConnectionSnapshot?) -> Unit)? = null

    private val writerGate =
        BridgeWriterGate(
            lock = writeLock,
            currentSnapshot = { snapshotMutable.value },
            onRejected = { mode, incoming -> onWriteRejected?.invoke(mode, incoming) },
        )

    fun writer(mode: TrafficMode): ModeScopedBridgeWriter =
        ModeScopedBridgeWriter(
            token = writerGate.newToken(mode),
        )

    fun snapshotOwnedByAnotherMode(mode: TrafficMode): Boolean =
        snapshotMutable.value.isActiveRuntimeForAnotherMode(mode)

    internal fun applyWriterMutation(
        token: BridgeWriterToken,
        mutation: BridgeWriterMutation,
    ): Boolean =
        writerGate.reduce(
            token = token,
            incoming = (mutation as? BridgeWriterMutation.UpdateSnapshot)?.value,
        ) {
            applyWriterMutationLocked(mutation)
        }

    private fun applyWriterMutationLocked(mutation: BridgeWriterMutation): Boolean =
        when (mutation) {
            is BridgeWriterMutation.UpdateSnapshot -> {
                updateSnapshotLocked(mutation.value, mutation.refreshLastChangeAt)
                true
            }
            is BridgeWriterMutation.UpdateIpInfo -> {
                updateIpInfoLocked(mutation.value)
                true
            }
            is BridgeWriterMutation.UpdateDeviceIpInfo ->
                updateDeviceIpInfoLocked(mutation.value, mutation.allowNewAddress)
            is BridgeWriterMutation.UpdateTorRouteIpInfo -> {
                torRouteIpInfoMutable.value = mutation.value
                publishRuntimeUiState()
                true
            }
            is BridgeWriterMutation.UpdateTraffic -> {
                trafficMutable.value = mutation.value
                true
            }
            is BridgeWriterMutation.UpdateI2pPhase -> {
                i2pPhaseMutable.value = mutation.value
                true
            }
            is BridgeWriterMutation.UpdateActiveServerPingTarget -> {
                activeServerPingTargetMutable.value = mutation.value
                true
            }
            is BridgeWriterMutation.MarkIpInfoRefreshPending -> {
                markIpInfoRefreshPendingLocked(mutation.reason)
                true
            }
            is BridgeWriterMutation.SetHighFrequencyTrafficUpdates -> {
                highFrequencyTrafficUpdatesMutable.value = mutation.enabled
                true
            }
            is BridgeWriterMutation.ClearTransientState -> {
                clearTransientStateLocked(mutation.clearIpInfo)
                true
            }
        }

    private val snapshotMutable = MutableStateFlow(ConnectionSnapshot())
    private val ipInfoMutable = MutableStateFlow<IpInfo?>(null)
    private val deviceIpInfoMutable = MutableStateFlow<IpInfo?>(null)

    private val torRouteIpInfoMutable = MutableStateFlow<IpInfo?>(null)
    private val trafficMutable = MutableStateFlow(TrafficSnapshot())

    private val torPhaseMutable = MutableStateFlow(TorPhaseSnapshot())
    private val i2pPhaseMutable = MutableStateFlow(I2pPhaseSnapshot())

    internal val lanProxyStatusMutable = MutableStateFlow(LanProxyStatusSnapshot())
    internal val localProxyStatusMutable = MutableStateFlow(LocalProxyStatusSnapshot())
    private val activeServerPingTargetMutable = MutableStateFlow<ActiveServerPingTarget?>(null)
    private val highFrequencyTrafficUpdatesMutable = MutableStateFlow(false)
    private val immediateTrafficSampleRequestsMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val runtimeUiStateMutable = MutableStateFlow(RuntimeUiState())

    @Volatile
    internal var socketProtector: ((Socket) -> Boolean)? = null

    @Volatile
    private var pendingIpRefreshReason: RuntimeIpRefreshReason? = null

    val snapshot: StateFlow<ConnectionSnapshot> = snapshotMutable
    val ipInfo: StateFlow<IpInfo?> = ipInfoMutable
    val deviceIpInfo: StateFlow<IpInfo?> = deviceIpInfoMutable
    val torRouteIpInfo: StateFlow<IpInfo?> = torRouteIpInfoMutable
    val traffic: StateFlow<TrafficSnapshot> = trafficMutable
    val torPhase: StateFlow<TorPhaseSnapshot> = torPhaseMutable
    val i2pPhase: StateFlow<I2pPhaseSnapshot> = i2pPhaseMutable
    val lanProxyStatus: StateFlow<LanProxyStatusSnapshot> = lanProxyStatusMutable

    val localProxyStatus: StateFlow<LocalProxyStatusSnapshot> = localProxyStatusMutable
    val activeServerPingTarget: StateFlow<ActiveServerPingTarget?> = activeServerPingTargetMutable
    val highFrequencyTrafficUpdates: StateFlow<Boolean> = highFrequencyTrafficUpdatesMutable
    val immediateTrafficSampleRequests: SharedFlow<Unit> = immediateTrafficSampleRequestsMutable
    val runtimeUiState: StateFlow<RuntimeUiState> = runtimeUiStateMutable

    fun update(
        value: ConnectionSnapshot,
        refreshLastChangeAt: Boolean = true,
    ) {
        synchronized(writeLock) {
            writerGate.fenceForControlPlaneTransition(
                previous = snapshotMutable.value,
                incoming = value,
            )
            updateSnapshotLocked(value, refreshLastChangeAt)
        }
    }

    private fun updateSnapshotLocked(
        value: ConnectionSnapshot,
        refreshLastChangeAt: Boolean,
    ) {
        snapshotMutable.value =
            if (refreshLastChangeAt) {
                value.copy(lastChangeAt = System.currentTimeMillis())
            } else {
                value
            }
        publishRuntimeUiState()
    }

    fun updateIpInfo(value: IpInfo?) {
        synchronized(writeLock) {
            updateIpInfoLocked(value)
        }
    }

    private fun updateIpInfoLocked(value: IpInfo?) {
        pendingIpRefreshReason = null
        ipInfoMutable.value =
            if (value == null) {
                null
            } else {
                val previous = ipInfoMutable.value
                value.retainKnownDetailsFrom(previous)
            }
        if (value == null) {
            torRouteIpInfoMutable.value = null
        }
        publishRuntimeUiState()
    }

    fun updateTorRouteIpInfo(value: IpInfo?) {
        synchronized(writeLock) {
            torRouteIpInfoMutable.value = value

            publishRuntimeUiState()
        }
    }

    fun markIpInfoRefreshPending(reason: RuntimeIpRefreshReason = RuntimeIpRefreshReason.POST_CONNECT) {
        synchronized(writeLock) {
            markIpInfoRefreshPendingLocked(reason)
        }
    }

    private fun markIpInfoRefreshPendingLocked(reason: RuntimeIpRefreshReason) {
        pendingIpRefreshReason = reason
        publishRuntimeUiState()
    }

    @Suppress("ComplexCondition")
    fun updateDeviceIpInfo(
        value: IpInfo?,
        allowNewAddress: Boolean = true,
    ): Boolean =
        synchronized(writeLock) {
            updateDeviceIpInfoLocked(value, allowNewAddress)
        }

    private fun updateDeviceIpInfoLocked(
        value: IpInfo?,
        allowNewAddress: Boolean,
    ): Boolean {
        val previous = deviceIpInfoMutable.value
        if (value != null && !allowNewAddress) {
            if (previous == null || !value.samePrimaryAddress(previous)) {
                return false
            }
        }
        deviceIpInfoMutable.value =
            if (value == null) {
                null
            } else {
                value.retainKnownDetailsFrom(previous)
            }
        publishRuntimeUiState()
        return true
    }

    fun updateTraffic(value: TrafficSnapshot) {
        synchronized(writeLock) {
            trafficMutable.value = value
        }
    }

    fun updateI2pPhase(value: I2pPhaseSnapshot) {
        synchronized(writeLock) {
            i2pPhaseMutable.value = value
        }
    }

    fun updateActiveServerPingTarget(value: ActiveServerPingTarget?) {
        synchronized(writeLock) {
            activeServerPingTargetMutable.value = value
        }
    }

    fun setHighFrequencyTrafficUpdates(enabled: Boolean) {
        synchronized(writeLock) {
            highFrequencyTrafficUpdatesMutable.value = enabled
        }
    }

    fun requestImmediateTrafficSample() {
        immediateTrafficSampleRequestsMutable.tryEmit(Unit)
    }

    fun clearTransientState(clearIpInfo: Boolean = true) {
        synchronized(writeLock) {
            clearTransientStateLocked(clearIpInfo)
        }
    }

    private fun clearTransientStateLocked(clearIpInfo: Boolean) {
        pendingIpRefreshReason = null
        if (clearIpInfo) {
            ipInfoMutable.value = null

            torRouteIpInfoMutable.value = null
        }
        activeServerPingTargetMutable.value = null
        trafficMutable.value = TrafficSnapshot()
        highFrequencyTrafficUpdatesMutable.value = false
        publishRuntimeUiState()
    }

    private fun torPhaseFor(snapshot: ConnectionSnapshot): TorPhaseSnapshot {
        val engaged = snapshot.torActive || snapshot.profileId == TOR_ONLY_PROFILE_ID
        val previous = torPhaseMutable.value
        return when {
            !engaged -> TorPhaseSnapshot()
            snapshot.state == ConnectionState.CONNECTED ->
                TorPhaseSnapshot(
                    phase = TorNetworkPhase.CONNECTED,
                    startedAt = previous.startedAt.takeIf { it != 0L } ?: snapshot.lastChangeAt,
                    connectedAt = previous.connectedAt.takeIf { it != 0L } ?: snapshot.lastChangeAt,
                )
            snapshot.state in ACTIVE_CONNECTION_STATES ->
                TorPhaseSnapshot(
                    phase = TorNetworkPhase.CONNECTING,
                    startedAt = previous.startedAt.takeIf { it != 0L } ?: snapshot.lastChangeAt,
                )
            else -> TorPhaseSnapshot()
        }
    }

    private fun publishRuntimeUiState() {
        torPhaseMutable.value = torPhaseFor(snapshotMutable.value)
        runtimeUiStateMutable.value =
            ConnectionRuntimeBridgeProjection(
                snapshot = snapshotMutable.value,
                ipInfo = ipInfoMutable.value,
                deviceIpInfo = deviceIpInfoMutable.value,
                pendingIpRefreshReason = pendingIpRefreshReason,
                torPhase = torPhaseMutable.value,
                torExit = torRouteIpInfoMutable.value,
            ).project(previous = runtimeUiStateMutable.value)
    }
}

fun FoxholeVpnRuntimeBridge.updateLanProxyStatus(value: LanProxyStatusSnapshot) {
    synchronized(writeLock) {
        lanProxyStatusMutable.value = value
    }
}

fun FoxholeVpnRuntimeBridge.updateLocalProxyStatus(value: LocalProxyStatusSnapshot) {
    synchronized(writeLock) {
        localProxyStatusMutable.value = value
    }
}

fun FoxholeVpnRuntimeBridge.updateSocketProtector(value: ((Socket) -> Boolean)?) {
    socketProtector = value
}

/** Fail-closed: with no protector installed a direct socket is refused, never let out unprotected. */
fun FoxholeVpnRuntimeBridge.protectDirectSocket(socket: Socket): Boolean =
    socketProtector?.invoke(socket) ?: false

class ModeScopedBridgeWriter internal constructor(
    private val token: BridgeWriterToken,
) {
    val mode: TrafficMode = token.mode

    fun update(
        value: ConnectionSnapshot,
        refreshLastChangeAt: Boolean = true,
    ): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateSnapshot(value, refreshLastChangeAt),
        )

    fun updateIpInfo(value: IpInfo?): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateIpInfo(value),
        )

    fun updateDeviceIpInfo(
        value: IpInfo?,
        allowNewAddress: Boolean = true,
    ): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateDeviceIpInfo(value, allowNewAddress),
        )

    fun updateTorRouteIpInfo(value: IpInfo?): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateTorRouteIpInfo(value),
        )

    fun updateTraffic(value: TrafficSnapshot): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateTraffic(value),
        )

    fun updateI2pPhase(value: I2pPhaseSnapshot): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateI2pPhase(value),
        )

    fun updateActiveServerPingTarget(value: ActiveServerPingTarget?): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.UpdateActiveServerPingTarget(value),
        )

    fun markIpInfoRefreshPending(reason: RuntimeIpRefreshReason = RuntimeIpRefreshReason.POST_CONNECT): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.MarkIpInfoRefreshPending(reason),
        )

    fun setHighFrequencyTrafficUpdates(enabled: Boolean): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.SetHighFrequencyTrafficUpdates(enabled),
        )

    fun clearTransientState(clearIpInfo: Boolean = true): Boolean =
        FoxholeVpnRuntimeBridge.applyWriterMutation(
            token,
            BridgeWriterMutation.ClearTransientState(clearIpInfo),
        )
}

internal data class BridgeWriterToken(
    val mode: TrafficMode,
    val epoch: Long,
)

internal sealed interface BridgeWriterMutation {
    data class UpdateSnapshot(
        val value: ConnectionSnapshot,
        val refreshLastChangeAt: Boolean,
    ) : BridgeWriterMutation

    data class UpdateIpInfo(
        val value: IpInfo?,
    ) : BridgeWriterMutation

    data class UpdateDeviceIpInfo(
        val value: IpInfo?,
        val allowNewAddress: Boolean,
    ) : BridgeWriterMutation

    data class UpdateTorRouteIpInfo(
        val value: IpInfo?,
    ) : BridgeWriterMutation

    data class UpdateTraffic(
        val value: TrafficSnapshot,
    ) : BridgeWriterMutation

    data class UpdateI2pPhase(
        val value: I2pPhaseSnapshot,
    ) : BridgeWriterMutation

    data class UpdateActiveServerPingTarget(
        val value: ActiveServerPingTarget?,
    ) : BridgeWriterMutation

    data class MarkIpInfoRefreshPending(
        val reason: RuntimeIpRefreshReason,
    ) : BridgeWriterMutation

    data class SetHighFrequencyTrafficUpdates(
        val enabled: Boolean,
    ) : BridgeWriterMutation

    data class ClearTransientState(
        val clearIpInfo: Boolean,
    ) : BridgeWriterMutation
}

private class BridgeWriterGate(
    private val lock: Any,
    private val currentSnapshot: () -> ConnectionSnapshot,
    private val onRejected: (TrafficMode, ConnectionSnapshot?) -> Unit,
) {
    private var fenceEpoch = 0L

    private var activeToken: BridgeWriterToken? = null

    fun newToken(mode: TrafficMode): BridgeWriterToken =
        BridgeWriterToken(
            mode = mode,
            epoch = RuntimeGenerationClock.next(),
        )

    fun reduce(
        token: BridgeWriterToken,
        incoming: ConnectionSnapshot?,
        mutation: () -> Boolean,
    ): Boolean {
        var accepted = false
        val result =
            synchronized(lock) {
                if (acceptLocked(token, incoming)) {
                    accepted = true
                    mutation()
                } else {
                    false
                }
            }
        if (!accepted) {
            onRejected(token.mode, incoming)
        }
        return result
    }

    fun fenceForControlPlaneTransition(
        previous: ConnectionSnapshot,
        incoming: ConnectionSnapshot,
    ) {
        val crossModeTakeover =
            incoming.state == ConnectionState.CONNECTING &&
                previous.state in ACTIVE_CONNECTION_STATES &&
                previous.trafficMode != incoming.trafficMode
        val runtimeTerminated =
            incoming.state == ConnectionState.IDLE ||
                incoming.state == ConnectionState.ERROR
        if (crossModeTakeover || runtimeTerminated) {
            fenceEpoch = RuntimeGenerationClock.current()
            activeToken = null
        }
    }

    private fun acceptLocked(
        token: BridgeWriterToken,
        incoming: ConnectionSnapshot?,
    ): Boolean {
        if (token.epoch <= fenceEpoch) {
            return false
        }
        if (activeToken == token) {
            return true
        }
        val previousOwnerEpoch = activeToken?.epoch ?: fenceEpoch
        val newerWriter = token.epoch > previousOwnerEpoch
        val snapshot = currentSnapshot()
        val mayClaimCurrentMode = !snapshot.isActiveRuntimeForAnotherMode(token.mode)
        val mayClaimCrossMode =
            incoming?.state == ConnectionState.CONNECTING &&
                incoming.trafficMode == token.mode
        if (!newerWriter || (!mayClaimCurrentMode && !mayClaimCrossMode)) {
            return false
        }
        activeToken = token
        return true
    }
}

private data class ConnectionRuntimeBridgeProjection(
    val snapshot: ConnectionSnapshot,
    val ipInfo: IpInfo?,
    val deviceIpInfo: IpInfo?,
    val pendingIpRefreshReason: RuntimeIpRefreshReason?,
    val torPhase: TorPhaseSnapshot,
    val torExit: IpInfo?,
) {
    fun project(previous: RuntimeUiState): RuntimeUiState =
        runtimeUiStateFromBridge(
            previous = previous,
            snapshot = snapshot,
            ipInfo = ipInfo,
            deviceIpInfo = deviceIpInfo,
            pendingIpRefreshReason = pendingIpRefreshReason,
            torPhase = torPhase,
            torExit = torExit,
        )
}

data class ActiveServerPingTarget(
    val profileId: Long,
    val protocolOptionId: String?,
    val target: VpnHealthProbeTarget,
    val resolvedAddress: InetAddress? = null,
) {
    fun matchesRequest(
        requestedProfileId: Long,
        requestedProtocolOptionId: String?,
    ): Boolean =
        profileId == requestedProfileId &&
            (
                requestedProtocolOptionId.isNullOrBlank() ||
                    protocolOptionId.isNullOrBlank() ||
                    protocolOptionId == requestedProtocolOptionId
                )
}
