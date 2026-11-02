package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal interface RouterWizardTransport {
    suspend fun requireDiscoveryAccess(endpoint: RouterEndpoint) = Unit
    fun gateway(): String?
    fun gatewayHint(): String? = gateway()
    fun hasPhysicalWifi(): Boolean = gatewayHint() != null
    suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity
    suspend fun inspectDiscoveryIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = inspectIdentity(endpoint)
    suspend fun advertisedCandidates(): List<RouterEndpoint> = emptyList()
    fun api(identity: RouterTlsIdentity): RouterPanelApi
    fun discoveryApi(identity: RouterTlsIdentity): RouterPanelApi = api(identity)
    fun beginAddition() = Unit
    suspend fun openManagement(pairing: RouterPairing, preferences: RouterControlPreferences): RouterPanelApi {
        val observed = inspectIdentity(pairing.identity.endpoint)
        if (observed != pairing.identity) throw RouterApiFailure(RouterApiError.IDENTITY)
        return api(pairing.identity)
    }
}

internal class WifiRouterWizardTransport(private val manager: ConnectivityManager, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val socketProtection: RouterSocketProtection? = null, private val accessCheck: () -> Unit = {},
    private val nsd: NsdManager? = null) : RouterWizardTransport {
    override suspend fun requireDiscoveryAccess(endpoint: RouterEndpoint) = routerIo(io) { operation ->
        RouterWifiConnection.select(manager, endpoint, socketProtection, accessCheck)
            .createSocketForOperation(operation).use { }
    }
    override fun gateway(): String? { accessCheck(); return RouterWifiConnection.suggestedGateway(manager) }
    override fun gatewayHint(): String? = RouterWifiConnection.suggestedGateway(manager)
    override fun hasPhysicalWifi(): Boolean = RouterWifiConnection.hasPhysicalWifi(manager)
    override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = routerIo(io) { operation ->
        inspectRouterIdentity(RouterWifiConnection.select(manager, endpoint, socketProtection, accessCheck), operation)
    }
    override suspend fun inspectDiscoveryIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = routerIo(io) { operation ->
        inspectRouterIdentity(RouterWifiConnection.select(manager, endpoint, socketProtection, accessCheck), operation, 2000)
    }
    override suspend fun advertisedCandidates(): List<RouterEndpoint> {
        accessCheck()
        val discovery = nsd ?: return emptyList()
        val network = RouterWifiConnection.physicalNetworks(manager).singleOrNull() ?: throw RouterWifiRequired()
        return discoverRouterNsdEndpoints(discovery, network) { endpoint ->
            accessCheck()
            RouterWifiConnection.select(manager, endpoint, socketProtection, accessCheck).requireCurrent()
        }
    }
    override fun discoveryApi(identity: RouterTlsIdentity): RouterPanelApi = RouterPanelApi.forDiscoveryWifi(
        RouterWifiConnection.select(manager, identity.endpoint, socketProtection, accessCheck), identity)
    override fun api(identity: RouterTlsIdentity): RouterPanelApi = RouterPanelApi.forWifi(RouterWifiConnection.select(manager, identity.endpoint, socketProtection, accessCheck), identity)
}
