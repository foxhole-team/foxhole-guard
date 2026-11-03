package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal class PairedRouterManagementTransport(
    private val wifi: RouterWizardTransport,
    private val manager: ConnectivityManager,
    private val accessCheck: () -> Unit,
    private val pairings: EncryptedRouterPairingStore? = null,
    private val connectedProfile: () -> RouterConnectedProfile? = { null },
) : RouterWizardTransport by wifi {
    private val authenticatedNetworks = ConcurrentHashMap<String, Long>()
    private val pendingNetworks = ConcurrentHashMap<String, Long>()

    override suspend fun managementAccess(pairing: RouterPairing?, preferences: RouterControlPreferences?,
        forceLocal: Boolean): RouterManagementAccess {
        val physicalWifi = wifi.hasPhysicalWifi()
        if (pairing == null || preferences == null) return routerManagementAccess(
            physicalWifi, physicalWifi, routerVpnActive(manager), false)
        val local = localConnection(pairing)
        val known = local != null && (forceLocal || knownHome(pairing, preferences, local) ||
            !routerVpnActive(manager) && identifyHome(pairing, local))
        return routerManagementAccess(physicalWifi, known, routerVpnActive(manager),
            remoteProfile(pairing, preferences) != null)
    }

    override suspend fun openManagement(pairing: RouterPairing, preferences: RouterControlPreferences): RouterPanelApi {
        val local = localConnection(pairing)
        val profile = remoteProfile(pairing, preferences)
        if (local != null && (!routerVpnActive(manager) || knownHome(pairing, preferences, local))) {
            if (routerVpnActive(manager)) throw RouterVpnSocketUnavailable()
            val api = wifi.openManagement(pairing, preferences)
            try { local.requireCurrent(); pendingNetworks[key(pairing)] = local.network.networkHandle }
            catch (failure: Exception) { api.close(); throw failure }
            return api
        }
        if (profile == null) throw RouterHomeVpnRequired()
        val fence = {
            accessCheck()
            if (connectedProfile() != profile) throw RouterHomeVpnRequired()
        }
        // The exact authenticated profile/network stays inside the VPN, including on another Wi-Fi.
        val connection = RouterHomeVpnConnection.select(manager, pairing.identity.endpoint, fence, profile.networkHandle)
        return RouterPanelApi.forHomeVpn(connection, pairing.identity)
    }

    override fun authenticatedWifiPreferences(pairing: RouterPairing, preferences: RouterControlPreferences): RouterControlPreferences {
        val local = localConnection(pairing) ?: return preferences
        val pending = pendingNetworks.remove(key(pairing)) ?: return preferences
        if (local.network.networkHandle != pending) return preferences
        authenticatedNetworks[key(pairing)] = pending
        val hint = wifiHint(pairing, local)
        return preferences.copy(homeWifiHashes = (preferences.homeWifiHashes + listOfNotNull(hint)).toList().takeLast(8).toSet())
    }

    private suspend fun remoteProfile(pairing: RouterPairing, preferences: RouterControlPreferences): RouterConnectedProfile? {
        if (!routerHomeVpnManagementAllowed(pairing.id == preferences.pairingId, pairing.active,
                preferences.enabled, preferences.publicIpConfirmed, preferences.homeVpnManagementEnabled)) return null
        val profile = connectedProfile() ?: return null
        val trusted = pairings?.trustedProfile(profile.id, profile.configSha256) ?: return null
        if (trusted.marker.pairingId != pairing.id || trusted.certificateSha256 != pairing.identity.certificateSha256 ||
            connectedProfile() != profile) return null
        return profile
    }

    private fun localConnection(pairing: RouterPairing): RouterWifiConnection? = try {
        RouterWifiConnection.select(manager, pairing.identity.endpoint)
    } catch (_: RouterWifiRequired) { null }

    private fun knownHome(pairing: RouterPairing, preferences: RouterControlPreferences, local: RouterWifiConnection): Boolean =
        authenticatedNetworks[key(pairing)] == local.network.networkHandle ||
            wifiHint(pairing, local)?.let { it in preferences.homeWifiHashes } == true

    private suspend fun identifyHome(pairing: RouterPairing, local: RouterWifiConnection): Boolean {
        // Unknown/renamed networks stay visually offline until this bounded certificate-only check succeeds.
        // No pairing credential or router command is sent during the check.
        val observed = try { withTimeoutOrNull(600) { wifi.inspectDiscoveryIdentity(pairing.identity.endpoint) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
        catch (_: SecurityException) { null }
        if (observed == null || observed.certificateSha256 != pairing.identity.certificateSha256 || observed.endpoint != pairing.identity.endpoint) return false
        local.requireCurrent()
        authenticatedNetworks[key(pairing)] = local.network.networkHandle
        return true
    }

    private fun wifiHint(pairing: RouterPairing, local: RouterWifiConnection): String? {
        return try {
            val info = manager.getNetworkCapabilities(local.network)?.transportInfo as? WifiInfo ?: return null
            routerWifiIdentityHash(pairing.credential, info.bssid, info.ssid)
        } catch (_: SecurityException) { null }
    }

    private fun key(pairing: RouterPairing) = pairing.id + ":" + pairing.identity.certificateSha256
}
