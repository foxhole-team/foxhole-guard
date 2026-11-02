package com.foxhole.guard.router

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal class RouterPersonalVpnAtHome : IOException("router_personal_vpn_at_home")

internal interface RouterPersonalVpnOrigins {
    val startRevision: Long
    suspend fun trustedProfileForStart(profileId: String, configSha256: String): RouterTrustedProfile?
    suspend fun readById(id: String): RouterPairing?
}

internal class RouterPersonalVpnStartGuard(
    private val pairings: RouterPersonalVpnOrigins,
    private val wifi: RouterWizardTransport,
) {
    suspend fun requireAway(profileId: String, configSha256: String, profileCurrent: suspend () -> Boolean) {
        suspend fun current() {
            currentCoroutineContext().ensureActive()
            if (!profileCurrent()) throw CancellationException("router_start_changed")
        }
        current()
        var transportCurrent: (() -> Unit)? = null
        var proofRevision: Long? = null
        val atHome = try {
            withTimeoutOrNull(8000) {
                val origin = pairings.trustedProfileForStart(profileId, configSha256) ?: return@withTimeoutOrNull false
                if (!wifi.hasPhysicalWifi()) return@withTimeoutOrNull false
                val revision = pairings.startRevision
                val saved = pairings.readById(origin.marker.pairingId) ?: return@withTimeoutOrNull false
                try {
                    if (!saved.active || saved.identity.certificateSha256 != origin.certificateSha256) return@withTimeoutOrNull false
                    suspend fun fence() {
                        current()
                        if (pairings.startRevision != revision) throw RouterWifiRequired()
                    }
                    fence()
                    val match = discoverSavedRouterIdentity(wifi, listOf(saved), ::fence)
                    val observed = match.identity?.takeIf { match.pairingId == saved.id } ?: return@withTimeoutOrNull false
                    val local = saved.atVerifiedIdentity(observed)
                    try {
                        val api = wifi.api(observed)
                        try {
                            val session = api.unlockPairing(local)
                            try {
                                RouterHttpProvisioner(localAddress = { throw RouterWifiRequired() }).snapshot(api, session)
                                api.requireTransportCurrent()
                                fence()
                                if (pairings.trustedProfileForStart(profileId, configSha256) != origin) return@withTimeoutOrNull false
                                fence()
                                transportCurrent = api::requireTransportCurrent
                                proofRevision = revision
                                true
                            } finally { session.close() }
                        } finally { api.close() }
                    } finally { local.clear() }
                } finally { saved.clear() }
            } == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        current()
        if (!atHome) return
        try {
            if (!wifi.hasPhysicalWifi() || pairings.startRevision != proofRevision) return
            transportCurrent?.invoke() ?: return
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        // Unknown/unreachable networks never become HOME and never block a remote personal VPN.
        throw RouterPersonalVpnAtHome()
    }
}
