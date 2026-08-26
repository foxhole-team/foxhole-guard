package com.foxhole.guard.runtime

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FailClosedEvent
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.guard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        .onFailure { container.diagnosticsLogger.recordFailure("connection", "network callback registration failed") }
}

internal fun FoxholeVpnService.rememberI2pRelayNetworkClass() {
    val upstream = currentUpstreamNetworkOrNull()
    val capabilities = upstream?.let(connectivityManager::getNetworkCapabilities) ?: return
    lastI2pRelayMeteredClass =
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

internal fun FoxholeVpnService.handleI2pRelayNetworkClass(capabilities: NetworkCapabilities?) {
    val observed = capabilities ?: return
    val metered = !observed.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val previous = lastI2pRelayMeteredClass
    lastI2pRelayMeteredClass = metered
    if (previous == null || previous == metered) {
        return
    }
    val settings = container.settingsRepository.settings.value
    if (
        !settings.i2pRuntimeActive() ||
        !settings.i2p.relayTransitTraffic ||
        settings.i2p.allowRelayOnCellular
    ) {
        return
    }
    val session = activeSession
    val localGuardMode = activeLocalGuardMode
    if (session == null && localGuardMode == null) {
        return
    }
    container.diagnosticsLogger.recordStructured(
        "i2pd",
        "network class changed; rebuilding I2P relay policy and runtime atomically",
        "metered=$metered",
    )

    container.i2pdManager.kill("relay_network_class_changed")
    launchCommand("i2p_relay_network_class_changed") {
        when {
            session != null && activeSession?.correlationId == session.correlationId ->
                reconnectActiveRuntime(
                    session = session,
                    reason = "i2p_relay_network_class_changed",
                    attempt = 0,
                )
            localGuardMode != null && activeSession == null && activeLocalGuardMode == localGuardMode -> {
                container.connectionController.clearAppliedRuntime()
                startLocalGuard(requestedMode = localGuardMode, commandStartId = 0)
            }
        }
    }
}

internal fun FoxholeVpnService.updateActiveVpnUnderlyingNetwork(network: Network?) {
    if (activeSession == null && activeLocalGuardMode == null) {
        return
    }
    runCatching {
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
        container.diagnosticsLogger.recordFailure("network", "vpn underlying network update failed")
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
    container.ipInfoRepository.markDefaultNetworkChanged()
    invalidateValidationEpoch("upstream_$reason")
    stopGeoRefresh()

    scope.launch(Dispatchers.IO) { container.ipInfoRepository.evictStaleConnections() }
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
    if (network != null) {
        startGeoRefresh()
    }
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
        .onFailure {
            container.diagnosticsLogger.recordFailure(
                "connection",
                "vpn network callback registration failed",
            )
        }
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
    container.i2pdManager.markCarrierUnavailable()
    runtimeInstanceStore.current()?.let { runtime ->
        cutEveryFlowIfKillSwitchArmed(
            runtime = runtime,
            event = FailClosedEvent.TUNNEL_LOST,
            reason = reason,
        )
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
            container.diagnosticsLogger.recordFailure(
                "connection",
                "default network callback registration failed"
            )
        }
}
