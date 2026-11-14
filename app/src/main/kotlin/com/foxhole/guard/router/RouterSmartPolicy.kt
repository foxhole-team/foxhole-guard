package com.foxhole.guard.router

internal enum class RouterSmartNetwork { HOME_WIFI, CELLULAR, OTHER_WIFI, UNKNOWN }
internal data class RouterSmartPause(val profile: RouterConnectedProfile, val pairingId: String, val token: String) {
    override fun toString(): String = "RouterSmartPause"
}

internal fun routerSmartConsent(settings: RouterControlPreferences, pairingId: String): Boolean =
    settings.pairingId == pairingId && settings.enabled && settings.homeAutodetection &&
        settings.publicIpConfirmed && settings.homeVpnManagementEnabled && settings.authenticatedHomeServerEnabled == true

internal fun routerSmartResumeAllowed(paused: RouterSmartPause, trusted: RouterTrustedProfile?, pairing: RouterPairing,
    settings: RouterControlPreferences, network: RouterSmartNetwork, idle: Boolean, ownedPause: Boolean,
    anotherVpn: Boolean): Boolean = routerSmartConsent(settings, paused.pairingId) && pairing.active &&
    pairing.id == paused.pairingId && trusted != null && trusted.marker.pairingId == pairing.id &&
    trusted.certificateSha256 == pairing.identity.certificateSha256 &&
    routerLocalProfileMatches(paused.profile.id, paused.profile.configSha256, trusted.profileId, trusted.configSha256) &&
    network == RouterSmartNetwork.CELLULAR && idle && ownedPause && !anotherVpn

internal class RouterSmartTransitionWindow {
    private var lostAt: Long? = null
    private var manualOverride = false
    private var invalidated = false
    fun manualAction() { manualOverride = true }
    fun permitsPause(): Boolean = !manualOverride
    fun observe(network: RouterSmartNetwork, now: Long): Boolean {
        if (now < 0 || invalidated) return false
        if (network == RouterSmartNetwork.HOME_WIFI) { lostAt = null; return false }
        if (network == RouterSmartNetwork.OTHER_WIFI) { invalidated = true; return false }
        if (manualOverride) {
            // Missing callbacks or an unvalidated network do not prove that the user left home.
            if (network != RouterSmartNetwork.CELLULAR) return false
            manualOverride = false; lostAt = null
        }
        val since = lostAt ?: now.also { lostAt = it }
        return network == RouterSmartNetwork.CELLULAR && now >= since && now - since <= 90_000L
    }
    fun expired(now: Long): Boolean = invalidated || lostAt?.let { now < it || now - it > 90_000L } == true
}
