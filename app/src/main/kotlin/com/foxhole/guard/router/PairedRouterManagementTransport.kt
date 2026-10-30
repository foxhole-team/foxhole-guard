package com.foxhole.guard.router

import android.net.ConnectivityManager

internal class PairedRouterManagementTransport(
    private val wifi: RouterWizardTransport,
    private val manager: ConnectivityManager,
    private val accessCheck: () -> Unit,
) : RouterWizardTransport by wifi {
    override suspend fun openManagement(pairing: RouterPairing, preferences: RouterControlPreferences): RouterPanelApi {
        if (wifi.hasPhysicalWifi()) return wifi.openManagement(pairing, preferences)
        if (!routerHomeVpnManagementAllowed(pairing.id == preferences.pairingId, pairing.active,
                preferences.enabled, preferences.publicIpConfirmed, preferences.homeVpnManagementEnabled)) throw RouterHomeVpnRequired()
        // No discovery or new pairing is allowed here. Only this stored private endpoint and TLS identity are used.
        val connection = RouterHomeVpnConnection.select(manager, pairing.identity.endpoint, accessCheck)
        return RouterPanelApi.forHomeVpn(connection, pairing.identity)
    }
}
