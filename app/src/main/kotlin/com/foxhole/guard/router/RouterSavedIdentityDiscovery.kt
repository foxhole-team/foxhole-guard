package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal data class RouterSavedIdentityMatch(val pairingId: String?, val identity: RouterTlsIdentity?,
    val statuses: Map<String, RouterSavedStatus>, val unknownIdentity: Boolean)

internal suspend fun discoverSavedRouterIdentity(transport: RouterWizardTransport, saved: List<RouterPairing>,
    checkOwner: suspend () -> Unit = {}): RouterSavedIdentityMatch {
    require(saved.size <= MAX_SAVED_ROUTERS)
    if (saved.isEmpty()) return RouterSavedIdentityMatch(null, null, emptyMap(), false)
    val gateway = transport.gateway()?.let { address -> runCatching { RouterEndpoint(address) }.getOrNull() }
    val endpoints = (listOfNotNull(gateway) + saved.map { it.identity.endpoint }).distinct().take(MAX_SAVED_ROUTERS + 1)
    val statuses = saved.associate { it.id to RouterSavedStatus.UNKNOWN }.toMutableMap()
    var unknown = false
    for (endpoint in endpoints) {
        checkOwner()
        val observed = try { withTimeoutOrNull(2000) { transport.inspectIdentity(endpoint) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RouterIdentityChanged) { unknown = true; null }
        catch (_: IOException) { null }
        checkOwner()
        saved.filter { it.identity.endpoint == endpoint }.forEach { statuses[it.id] = RouterSavedStatus.UNAVAILABLE }
        if (observed == null) continue
        if (observed.endpoint != endpoint) throw RouterIdentityChanged()
        val matching = saved.filter { it.identity.certificateSha256 == observed.certificateSha256 }
        if (matching.size != 1) { unknown = true; continue }
        val actual = matching.single()
        statuses[actual.id] = RouterSavedStatus.AVAILABLE
        return RouterSavedIdentityMatch(actual.id, observed, statuses, unknown)
    }
    return RouterSavedIdentityMatch(null, null, statuses, unknown)
}
