package com.foxhole.guard.runtime

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the three Android network callback instances for one [FoxholeVpnService] lifetime.
 *
 * Registration and unregistration must receive the exact same callback object. Keeping those
 * identities in one owner also keeps Android network events out of the service's framework
 * lifecycle surface; the callbacks still delegate every state transition to the service-owned
 * runtime operations on the same main handler and coroutine scope.
 */
internal class FoxholeVpnServiceNetworkCallbackOwner(
    private val service: FoxholeVpnService,
) {
    val upstream: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!service.isUpstreamNetwork(network)) {
                    return
                }
                val capabilities = service.connectivityManager.getNetworkCapabilities(network)
                service.recordNetworkEvent(
                    message = "upstream available",
                    capabilities = capabilities,
                )
                service.handleI2pRelayNetworkClass(capabilities)
                service.upstreamNetworkHandles += network.networkHandle
                service.updateActiveVpnUnderlyingNetwork(network)
                service.publishUpstreamNetworkChange(network, reason = "available")
                service.runtime.onDefaultNetworkAvailable()
                val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                if (snapshot.state == ConnectionState.RECONNECTING) {
                    val session = service.activeSession
                    if (session != null) {
                        service.scheduleValidation(
                            session = session,
                            failOnFailure = false,
                            onSuccess = { vpnNetwork -> service.onTunnelValidated(session, vpnNetwork) },
                        )
                    }
                }
                service.updateNotification()
            }

            override fun onLost(network: Network) {
                val wasAcceptedUpstream = service.upstreamNetworkHandles.remove(network.networkHandle)
                if (!wasAcceptedUpstream && !service.isUpstreamNetwork(network)) {
                    return
                }
                val fallbackUpstream =
                    service.currentUpstreamNetworkOrNull(excludedHandle = network.networkHandle)
                if (fallbackUpstream != null) {
                    service.upstreamNetworkHandles += fallbackUpstream.networkHandle
                    val capabilities = service.connectivityManager.getNetworkCapabilities(fallbackUpstream)
                    service.recordNetworkEvent(
                        message = "upstream switched",
                        capabilities = capabilities,
                    )
                    service.handleI2pRelayNetworkClass(capabilities)
                    service.updateActiveVpnUnderlyingNetwork(fallbackUpstream)
                    service.publishUpstreamNetworkChange(fallbackUpstream, reason = "switched")
                    service.runtime.onDefaultNetworkAvailable()
                    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                    if (snapshot.state == ConnectionState.RECONNECTING) {
                        val session = service.activeSession
                        if (session != null) {
                            service.scheduleValidation(
                                session = session,
                                failOnFailure = false,
                                onSuccess = { vpnNetwork -> service.onTunnelValidated(session, vpnNetwork) },
                            )
                        }
                    }
                    service.updateNotification()
                    return
                }
                service.recordNetworkEvent(
                    message = "upstream lost",
                    capabilities = service.connectivityManager.getNetworkCapabilities(network),
                )
                service.updateActiveVpnUnderlyingNetwork(null)
                service.publishUpstreamNetworkChange(null, reason = "lost")
                service.runtime.onDefaultNetworkLost()
                service.invalidateValidationEpoch("upstream_lost")
                val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                if (snapshot.state == ConnectionState.CONNECTED) {
                    service.stopGeoRefresh()
                    service.bridgeWriter.update(
                        snapshot.copy(
                            state = ConnectionState.RECONNECTING,
                            message = service.getString(R.string.status_reconnecting),
                        ),
                    )
                }
                service.updateNotification()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (!service.isUpstreamNetwork(network)) {
                    return
                }
                service.handleI2pRelayNetworkClass(networkCapabilities)
            }
        }

    val vpn: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = service.connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
                    return
                }
                service.recordNetworkEvent(
                    message = "vpn network available",
                    capabilities = capabilities,
                )
                if (service.activeSession != null || service.activeLocalGuardMode != null) {
                    service.activeVpnNetworkHandle = network.networkHandle
                }
            }

            override fun onLost(network: Network) {
                val lostHandle = network.networkHandle
                val trackedHandle = service.activeVpnNetworkHandle
                service.recordNetworkEvent(
                    message = "vpn network lost",
                    capabilities = service.connectivityManager.getNetworkCapabilities(network),
                )
                if (service.ignoredVpnNetworkLossHandles.remove(lostHandle)) {
                    service.container.diagnosticsLogger.recordStructured(
                        "connection",
                        "ignored expected vpn network loss",
                        "lost_handle=$lostHandle",
                    )
                    return
                }
                if (trackedHandle == null || trackedHandle == lostHandle) {
                    service.handleVpnNetworkLost(lostHandle, reason = "vpn_network_lost")
                    return
                }
                service.scope.launch(Dispatchers.Main.immediate) {
                    delay(FoxholeVpnService.VPN_NETWORK_LOST_SETTLE_MS)
                    if (service.currentVpnNetworkOrNull()?.networkHandle == null) {
                        service.handleVpnNetworkLost(lostHandle, reason = "vpn_network_lost")
                    }
                }
            }
        }

    val default: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                service.onDefaultNetworkCapabilitiesChanged(
                    capabilities = service.connectivityManager.getNetworkCapabilities(network),
                    reason = "default network available",
                )
            }

            override fun onLost(network: Network) {
                service.defaultNetworkAvailable = false
                service.lastDefaultNetworkSummary = null
                service.container.diagnosticsLogger.record("network", "default network lost")
                if (
                    FoxholeVpnRuntimeBridge.snapshot.value.state in
                    FoxholeVpnService.NOTIFICATION_HEALTH_VISIBLE_STATES
                ) {
                    service.markNotificationConnectivityOffline()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                service.onDefaultNetworkCapabilitiesChanged(
                    capabilities = networkCapabilities,
                    reason = "default network changed",
                )
            }
        }
}
