package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal enum class RouterProfileProbePhase { IDLE, CHECKING, VERIFIED, FAILED }
internal data class RouterProfileProbeState(val phase: RouterProfileProbePhase = RouterProfileProbePhase.IDLE,
    val error: RouterWizardError? = null)

internal class RouterProfileAutoProbe(scope: CoroutineScope, private val manager: ConnectivityManager,
    private val pairings: EncryptedRouterPairingStore, private val preferences: RouterControlPreferenceStore,
    private val connected: StateFlow<RouterConnectedProfile?>, private val accessCheck: () -> Unit) {
    private val mutable = MutableStateFlow(RouterProfileProbeState())
    val state: StateFlow<RouterProfileProbeState> = mutable.asStateFlow()
    private val gate = RouterProfileProbeGate()

    init { scope.launch {
        combine(connected, pairings.changes, routerVpnNetworkStamps(manager)) { profile, revision, stamp ->
            Triple(profile, revision, stamp)
        }.collectLatest { (profile, revision, stamp) ->
            val previous = mutable.value
            mutable.value = RouterProfileProbeState()
            try {
                if (profile == null) return@collectLatest
                val origin = pairings.trustedProfile(profile.id, profile.configSha256) ?: return@collectLatest
                val saved = pairings.read() ?: return@collectLatest
                try {
                    if (saved.id != origin.marker.pairingId || saved.identity.certificateSha256 != origin.certificateSha256) return@collectLatest
                    val settings = preferences.read(saved.id)
                    if (!routerHomeVpnManagementAllowed(settings.pairingId == saved.id, saved.active, settings.enabled,
                            settings.publicIpConfirmed, settings.homeVpnManagementEnabled)) return@collectLatest
                    if (connected.value != profile || pairings.changes.value != revision) return@collectLatest
                    val key = RouterProfileProbeKey(profile.id, profile.configSha256, saved.id, profile.connectionGeneration)
                    if (!gate.claim(key)) {
                        if (previous.phase != RouterProfileProbePhase.IDLE) mutable.value = RouterProfileProbeState(
                            RouterProfileProbePhase.FAILED, RouterWizardError.HOME_VPN)
                        return@collectLatest
                    }
                    mutable.value = RouterProfileProbeState(RouterProfileProbePhase.CHECKING)
                    try {
                        withTimeout(8000) {
                            val fence = {
                                accessCheck()
                                if (connected.value != profile || pairings.changes.value != revision || routerVpnNetworkStamp(manager) != stamp)
                                    throw RouterHomeVpnRequired()
                            }
                            fence()
                            val connection = RouterHomeVpnConnection.select(manager, saved.identity.endpoint, fence, profile.networkHandle)
                            val api = RouterPanelApi.forHomeVpn(connection, saved.identity)
                            try {
                                val session = api.unlockPairing(saved)
                                try {
                                    // Read and validate the existing authenticated state contract; never apply router settings.
                                    RouterHttpProvisioner(localAddress = { throw RouterHomeVpnRequired() }).snapshot(api, session)
                                    currentCoroutineContext().ensureActive()
                                    fence()
                                    if (pairings.trustedProfile(profile.id, profile.configSha256) != origin ||
                                        preferences.read(saved.id) != settings) throw RouterHomeVpnRequired()
                                    fence()
                                    mutable.value = RouterProfileProbeState(RouterProfileProbePhase.VERIFIED)
                                } finally { session.close() }
                            } finally { api.close() }
                        }
                    } catch (cancelled: CancellationException) {
                        if (cancelled !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw cancelled
                        mutable.value = RouterProfileProbeState(RouterProfileProbePhase.FAILED, RouterWizardError.HOME_VPN)
                    } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        mutable.value = RouterProfileProbeState(RouterProfileProbePhase.FAILED, when {
                            failure is RouterLocalNetworkPermissionRequired -> RouterWizardError.LOCAL_NETWORK_PERMISSION
                            failure is RouterApiFailure && failure.kind == RouterApiError.IDENTITY -> RouterWizardError.IDENTITY
                            failure is RouterApiFailure && failure.kind == RouterApiError.PIN -> RouterWizardError.PIN
                            else -> RouterWizardError.HOME_VPN
                        })
                    }
                } finally { saved.clear() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = RouterProfileProbeState() }
        }
    } }
}

private fun routerVpnNetworkStamps(manager: ConnectivityManager) = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
        override fun onLost(network: Network) { trySend(Unit) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
    }
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    manager.registerNetworkCallback(request, callback)
    trySend(Unit)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.conflate().map { routerVpnNetworkStamp(manager) }.distinctUntilChanged()

private fun routerVpnNetworkStamp(manager: ConnectivityManager): String = routerProfileDigest(
    manager.allNetworks.mapNotNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
        val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
        network.networkHandle.toString() + ":" + listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET).filter(capabilities::hasTransport).joinToString() + ":" + properties.interfaceName + ":" +
            properties.linkAddresses.map { it.toString() }.sorted().joinToString() + ":" +
            properties.routes.map { it.toString() }.sorted().joinToString() + ":" +
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.sorted().joinToString("|")
)
