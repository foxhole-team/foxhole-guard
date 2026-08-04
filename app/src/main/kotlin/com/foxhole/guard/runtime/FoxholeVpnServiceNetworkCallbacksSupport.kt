package com.foxhole.guard.runtime

import android.net.Network
import android.os.Build
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.guard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Connectivity-callback registration and VPN/default network-loss handling for [FoxholeVpnService],
 * extracted from the service body in the Phase B split by responsibility. The service's inline
 * NetworkCallback objects call into these registration and loss-handling entry points.
 */

internal fun FoxholeVpnService.registerNetworkCallbackIfNeeded() {
    if (networkCallbackRegistered) {
        return
    }
    upstreamNetworkHandles.clear()
    val registration =
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    connectivityManager.registerBestMatchingNetworkCallback(
                        trackedNetworkRequest,
                        networkCallback,
                        mainHandler
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    connectivityManager.registerNetworkCallback(trackedNetworkRequest, networkCallback, mainHandler)
                else -> connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler)
            }
        }.recoverCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler)
        }
    registration
        .onSuccess { networkCallbackRegistered = true }
        .onFailure { container.diagnosticsLogger.record("connection", "network callback registration failed") }
}

internal fun FoxholeVpnService.updateActiveVpnUnderlyingNetwork(network: Network?) {
    if (activeSession == null && activeLocalGuardMode == null) {
        return
    }
    runCatching {
        // null (not an empty array) tells Android to fall back to the system default network for
        // metered/validated accounting. An empty array marks the VPN as having no underlying
        // network at all, which skews those signals while an upstream is briefly unavailable.
        setUnderlyingNetworks(network?.let { arrayOf(it) })
    }.onSuccess { updated ->
        container.diagnosticsLogger.recordStructured(
            "network",
            "VPN underlying network updated",
            "available=${network != null}",
            "updated=$updated",
            "network=${describeNetworkCapabilities(connectivityManager.getNetworkCapabilities(network))}",
        )
    }.onFailure {
        container.diagnosticsLogger.record("network", "vpn underlying network update failed")
    }
}

internal fun FoxholeVpnService.publishUpstreamNetworkChange(
    network: Network?,
    reason: String,
) {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (!snapshot.shouldPublishUpstreamNetworkChange()) {
        return
    }
    // Keep-alive sockets pooled on the previous upstream would keep answering the identity
    // refreshes below with the OLD network's egress; evict before anyone re-fetches.
    scope.launch(Dispatchers.IO) { container.ipInfoRepository.onDefaultNetworkChanged() }
    val nextRevision = snapshot.upstreamNetworkRevision + 1L
    bridgeWriter.update(
        snapshot.copy(upstreamNetworkRevision = nextRevision),
        refreshLastChangeAt = false,
    )
    container.diagnosticsLogger.recordStructured(
        "network",
        "upstream network refresh signal",
        "reason=$reason",
        "available=${network != null}",
        "revision=$nextRevision",
    )
}

private fun ConnectionSnapshot.shouldPublishUpstreamNetworkChange(): Boolean =
    state in setOf(ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != null &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

internal fun FoxholeVpnService.registerVpnNetworkCallbackIfNeeded() {
    if (vpnNetworkCallbackRegistered) {
        return
    }
    val registration =
        runCatching {
            connectivityManager.registerNetworkCallback(trackedVpnNetworkRequest, vpnNetworkCallback, mainHandler)
        }.recoverCatching {
            connectivityManager.registerNetworkCallback(trackedVpnNetworkRequest, vpnNetworkCallback)
        }
    registration
        .onSuccess { vpnNetworkCallbackRegistered = true }
        .onFailure { container.diagnosticsLogger.record("connection", "vpn network callback registration failed") }
}

internal fun FoxholeVpnService.handleVpnNetworkLost(
    lostHandle: Long,
    reason: String,
) {
    val session = activeSession
    val localGuardMode = activeLocalGuardMode
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (!shouldHandleVpnNetworkLoss(session, localGuardMode, snapshot)) {
        return
    }
    val currentVpnHandle = currentVpnNetworkOrNull()?.networkHandle
    if (currentVpnHandle != null && currentVpnHandle != lostHandle) {
        activeVpnNetworkHandle = currentVpnHandle
        return
    }
    activeVpnNetworkHandle = null
    invalidateValidationEpoch("vpn_network_lost")
    stopGeoRefresh()
    markNotificationConnectivityOffline()
    if (session != null) {
        handleActiveTunnelVpnNetworkLost(
            session = session,
            snapshot = snapshot,
            lostHandle = lostHandle,
            reason = reason,
        )
    } else if (localGuardMode != null) {
        handleLocalGuardVpnNetworkLost(
            mode = localGuardMode,
            snapshot = snapshot,
            lostHandle = lostHandle,
            reason = reason,
        )
    }
}

private fun FoxholeVpnService.shouldHandleVpnNetworkLoss(
    session: VpnSession?,
    localGuardMode: LocalGuardMode?,
    snapshot: ConnectionSnapshot,
): Boolean =
    snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.state in ACTIVE_CONNECTION_STATES &&
        (session != null || localGuardMode != null)

private fun FoxholeVpnService.handleActiveTunnelVpnNetworkLost(
    session: VpnSession,
    snapshot: ConnectionSnapshot,
    lostHandle: Long,
    reason: String,
) {
    container.diagnosticsLogger.recordStructured(
        "connection",
        "active vpn network disappeared",
        "reason=$reason",
        "lost_handle=$lostHandle",
        "sessionId=${session.correlationId}",
    )
    if (splitVpnRecoveryRequired(session)) {
        publishSplitVpnUnavailable(session = session, reason = reason)
        scheduleAutoReconnect(reason = reason)
    } else {
        bridgeWriter.update(
            snapshot.copy(
                state = ConnectionState.RECONNECTING,
                message = getString(R.string.status_reconnecting),
            ),
        )
        updateNotification()
        if (container.settingsRepository.settings.value.connection.autoReconnect) {
            scheduleAutoReconnect(reason = reason)
        } else {
            fail(
                message = getString(R.string.error_runtime_stopped),
                reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
            )
        }
    }
}

private fun FoxholeVpnService.handleLocalGuardVpnNetworkLost(
    mode: LocalGuardMode,
    snapshot: ConnectionSnapshot,
    lostHandle: Long,
    reason: String,
) {
    container.diagnosticsLogger.recordStructured(
        "connection",
        "local guard vpn network disappeared",
        "reason=$reason",
        "lost_handle=$lostHandle",
        "mode=${mode.name.lowercase()}",
    )
    // The firewall is always-on protection: instead of dropping straight to a silent ERROR, surface
    // RECONNECTING and self-heal (bounded retries; terminal ERROR + tap-to-restart only on
    // exhaustion). The scheduled restart re-issues ACTION_START_LOCAL_GUARD, which tears the dead
    // guard runtime down and brings a fresh one up.
    bridgeWriter.update(
        snapshot.copy(
            state = ConnectionState.RECONNECTING,
            message = getString(R.string.status_reconnecting),
        ),
    )
    updateNotification()
    scheduleLocalGuardHeal(mode = mode, reason = reason)
}

internal fun FoxholeVpnService.registerDefaultNetworkCallbackIfNeeded() {
    refreshDefaultNetworkAvailability()
    if (defaultNetworkCallbackRegistered) {
        return
    }
    val registration =
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback, mainHandler)
        }
    registration
        .onSuccess { defaultNetworkCallbackRegistered = true }
        .onFailure {
            container.diagnosticsLogger.record(
                "connection",
                "default network callback registration failed"
            )
        }
}
