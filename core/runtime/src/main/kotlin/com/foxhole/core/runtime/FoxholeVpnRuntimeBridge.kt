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
    // Internal, not private: the surfaces split into FoxholeVpnRuntimeBridgeSurfaces.kt publish
    // under the very same lock they did as members here.
    internal val writeLock = Any()

    // Wired once by the app to route stale-write rejections into the diagnostics journal.
    @Volatile
    var onWriteRejected: ((mode: TrafficMode, incoming: ConnectionSnapshot?) -> Unit)? = null

    private val writerGate =
        BridgeWriterGate(
            lock = writeLock,
            currentSnapshot = { snapshotMutable.value },
            onRejected = { mode, incoming -> onWriteRejected?.invoke(mode, incoming) },
        )

    /**
     * Mode-scoped write facade with a monotonically increasing session epoch. A newer writer may
     * claim the current mode, while CONNECTING may also transfer ownership across modes. Once a
     * newer writer claims the bridge, every mutation from the older writer is rejected, including
     * a delayed CONNECTING that used to reopen the previous runtime.
     */
    fun writer(mode: TrafficMode): ModeScopedBridgeWriter =
        ModeScopedBridgeWriter(
            token = writerGate.newToken(mode),
        )

    /** Control-flow check for teardown paths that should skip work during a cross-mode handoff. */
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

    // Called only while writeLock is held.
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

    // The Tor exit IP as observed by the runtime's own tunnel validation (it routes through
    // tunnel -> runtime -> Tor). The dashboard VPN ipInfo deliberately keeps the VPN exit, and the
    // app's own probe is excluded from Tor, so this is the only source of the real Tor exit.
    private val torRouteIpInfoMutable = MutableStateFlow<IpInfo?>(null)
    private val trafficMutable = MutableStateFlow(TrafficSnapshot())

    // Honest network phases: Tor's fed by the log tail / control probe, I2P's by the
    // i2pd process output. Both reset to OFFLINE with their runtimes.
    private val torPhaseMutable = MutableStateFlow(TorPhaseSnapshot())
    private val i2pPhaseMutable = MutableStateFlow(I2pPhaseSnapshot())

    // The LAN proxy as the core reports it. Ungated on purpose: its only writer is the LAN
    // controller on the VPN service's control path, and unlike the connection snapshot it is not
    // contested between traffic modes — a stale-writer fence here would only be able to drop the
    // teardown that turns the pill off.
    //
    // Internal, not private: its writer is one of the surfaces split below the object.
    internal val lanProxyStatusMutable = MutableStateFlow(LanProxyStatusSnapshot())
    internal val localProxyStatusMutable = MutableStateFlow(LocalProxyStatusSnapshot())
    private val activeServerPingTargetMutable = MutableStateFlow<ActiveServerPingTarget?>(null)
    private val highFrequencyTrafficUpdatesMutable = MutableStateFlow(false)
    private val immediateTrafficSampleRequestsMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val runtimeUiStateMutable = MutableStateFlow(RuntimeUiState())

    // The VpnService's own protect(), installed by the control plane and read on probe threads.
    // Internal, not private: its accessors are surfaces split below the object.
    @Volatile
    internal var socketProtector: ((Socket) -> Boolean)? = null

    // Written from the control plane on Main and from validation coroutines on IO.
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

    /** The device-local proxy, published by the same session path and read the same way. */
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

    // Called only while writeLock is held.
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

    // Called only while writeLock is held.
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
            // Clearing the dashboard IP (disconnect/teardown) also clears the Tor route exit.
            torRouteIpInfoMutable.value = null
        }
        publishRuntimeUiState()
    }

    fun updateTorRouteIpInfo(value: IpInfo?) {
        synchronized(writeLock) {
            torRouteIpInfoMutable.value = value
            // The Tor exit is half of the published Tor state (Bootstrapping -> Ready), so the
            // projection has to be re-run here. Without it the state stayed on whatever the last
            // snapshot write happened to leave behind.
            publishRuntimeUiState()
        }
    }

    fun markIpInfoRefreshPending(reason: RuntimeIpRefreshReason = RuntimeIpRefreshReason.POST_CONNECT) {
        synchronized(writeLock) {
            markIpInfoRefreshPendingLocked(reason)
        }
    }

    // Called only while writeLock is held.
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

    // Called only while writeLock is held.
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

    // Called only while writeLock is held.
    private fun clearTransientStateLocked(clearIpInfo: Boolean) {
        pendingIpRefreshReason = null
        if (clearIpInfo) {
            ipInfoMutable.value = null
            // Mirror updateIpInfoLocked(null): the disconnect that clears the dashboard IP must also
            // drop the Tor-route exit, or the last TOR IP lingers on screen after a VPN+TOR stop.
            torRouteIpInfoMutable.value = null
        }
        activeServerPingTargetMutable.value = null
        trafficMutable.value = TrafficSnapshot()
        highFrequencyTrafficUpdatesMutable.value = false
        publishRuntimeUiState()
    }

    /**
     * The Tor phase, derived — because nothing feeds it.
     *
     * `updateTorPhase` had no caller anywhere in the product: the phase was written for the era
     * when Tor was a separate daemon whose bootstrap was read off a log tail, and when Tor moved
     * inside FoxCore that feed disappeared with it. The core publishes no bootstrap progress at
     * all, so the snapshot sat on OFFLINE forever and every Tor line the terminal knows how to
     * print — leg opened, network up, stopped — was unreachable. Measured on the bench Pixel: a
     * session that demonstrably carried traffic through Tor reported `Off` for three minutes.
     *
     * So it is computed here from what the runtime does know, and deliberately not more than that:
     * an engaged Tor route that has not finished connecting is CONNECTING, one on a connected
     * session is CONNECTED. BUILDING_CIRCUITS and the percentage are not invented — the terminal
     * already handles a bootstrap that skips straight to connected.
     */
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

    // Called only while writeLock is held.
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

// Two writer surfaces of the bridge that own no part of the connection snapshot and take no part
// in its projection: the LAN proxy pill and the direct-socket protector. They sit beside the
// object rather than inside it (member-count budget); the state, the lock and the publishing
// order are unchanged, and they stay in this file so the legacy-bridge fence keeps covering them.

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

/**
 * Bridge mutators bound to the [TrafficMode] on whose behalf they publish. Every method returns
 * false (and writes nothing) when another mode owns the published snapshot — the central
 * replacement for the per-call-site isActiveRuntimeForAnotherMode guards.
 */
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
    // Guarded by lock. Epochs mint from the shared control-plane clock (Ф3c): acceptLocked uses
    // only ordering comparisons, and a global total order strengthens them — bridge tokens and
    // control-plane transitions now order against each other too.
    private var fenceEpoch = 0L

    // Guarded by lock.
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

    // Called only while lock is held.
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

    // Called only while lock is held.
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
