package com.foxhole.guard.router

import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal interface RouterWizardTransport {
    suspend fun requireDiscoveryAccess(endpoint: RouterEndpoint) = Unit
    fun gateway(): String?
    fun gatewayHint(): String? = gateway()
    fun hasPhysicalWifi(): Boolean = gatewayHint() != null
    suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity
    fun api(identity: RouterTlsIdentity): RouterPanelApi
    fun beginAddition() = Unit
    suspend fun openManagement(pairing: RouterPairing, preferences: RouterControlPreferences): RouterPanelApi {
        val observed = inspectIdentity(pairing.identity.endpoint)
        if (observed != pairing.identity) throw RouterApiFailure(RouterApiError.IDENTITY)
        return api(pairing.identity)
    }
}

internal class WifiRouterWizardTransport(private val manager: ConnectivityManager, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val socketProtection: RouterSocketProtection? = null, private val accessCheck: () -> Unit = {}) : RouterWizardTransport {
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
    override fun api(identity: RouterTlsIdentity): RouterPanelApi = RouterPanelApi.forWifi(RouterWifiConnection.select(manager, identity.endpoint, socketProtection, accessCheck), identity)
}
