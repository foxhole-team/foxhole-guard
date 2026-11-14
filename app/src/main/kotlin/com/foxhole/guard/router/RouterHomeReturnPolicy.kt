package com.foxhole.guard.router

internal fun routerHomeReturnAllowed(profile: RouterConnectedProfile, current: RouterConnectedProfile?,
    trusted: RouterTrustedProfile?, pairing: RouterPairing, settings: RouterControlPreferences,
    proof: RouterHomeProof?, now: Long): Boolean = routerSmartConsent(settings, pairing.id) && current == profile && trusted != null &&
    routerLocalProfileMatches(profile.id, profile.configSha256, trusted.profileId, trusted.configSha256) &&
    trusted.marker.pairingId == pairing.id && trusted.certificateSha256 == pairing.identity.certificateSha256 &&
    routerHomeVpnManagementAllowed(settings.pairingId == pairing.id, pairing.active, settings.enabled,
        settings.publicIpConfirmed, settings.homeVpnManagementEnabled) &&
    proof != null && proof.pairingId == pairing.id && proof.serverId.isNotBlank() && proof.generation.isNotBlank() &&
    proof.thisPhoneRouted && proof.tunnelValidated && now >= proof.verifiedAtElapsedMs && now - proof.verifiedAtElapsedMs <= 5000L
