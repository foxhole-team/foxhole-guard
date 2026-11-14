package com.foxhole.guard.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterHomeReturnPolicyTest {
    private val pairId = "pair-" + "a".repeat(32)
    private val certificate = "b".repeat(64)
    private val profile = RouterConnectedProfile("profile", "c".repeat(64), "connection-1", 42L)
    private val pairing = RouterPairing(RouterTlsIdentity(RouterEndpoint("192.168.1.1"), certificate),
        pairId, ByteArray(32), "Fixture", active = true)
    private val settings = RouterControlPreferences(pairId, enabled = true, publicIpConfirmed = true,
        homeVpnManagementEnabled = true, homeAutodetection = true, authenticatedHomeServerEnabled = true)
    private val trusted = RouterTrustedProfile(profile.id, profile.configSha256, certificate,
        RouterProfileMarker(1, "foxhole-openwrt-client", pairId, "client-" + "d".repeat(12), "e".repeat(64)))
    private val proof = RouterHomeProof(pairId, "server", "generation", 10_000L, true, true)

    private fun allows(current: RouterConnectedProfile? = profile, origin: RouterTrustedProfile? = trusted,
        saved: RouterPairing = pairing, preferences: RouterControlPreferences = settings,
        evidence: RouterHomeProof? = proof, now: Long = 10_100L) =
        routerHomeReturnAllowed(profile, current, origin, saved, preferences, evidence, now)

    @Test fun exactOwnedRouterProfileWithFreshRoutedProofCanReturnHome() { assertTrue(allows()) }
    @Test fun unrelatedOrModifiedProfilesAndNewSessionsStayConnected() {
        assertFalse(allows(current = null))
        assertFalse(allows(current = profile.copy(id = "other")))
        assertFalse(allows(current = profile.copy(configSha256 = "f".repeat(64))))
        assertFalse(allows(current = profile.copy(connectionGeneration = "connection-2")))
        assertFalse(allows(current = profile.copy(networkHandle = 43L)))
        assertFalse(allows(origin = null))
        assertFalse(allows(origin = trusted.copy(profileId = "other")))
        assertFalse(allows(origin = trusted.copy(configSha256 = "f".repeat(64))))
    }
    @Test fun disabledManagementAndUnconfirmedPublicIpNeverStopThePhone() {
        assertFalse(allows(preferences = settings.copy(enabled = false)))
        assertFalse(allows(preferences = settings.copy(publicIpConfirmed = false)))
        assertFalse(allows(preferences = settings.copy(homeVpnManagementEnabled = false)))
        assertFalse(allows(preferences = settings.copy(pairingId = "other")))
        val inactive = RouterPairing(pairing.identity, pairId, ByteArray(32), "Fixture", active = false)
        try { assertFalse(allows(saved = inactive)) } finally { inactive.clear() }
    }
    @Test fun smartOffOrUnknownHomeServerNeverStopsThePhone() {
        assertFalse(allows(preferences = settings.copy(homeAutodetection = false)))
        assertFalse(allows(preferences = settings.copy(authenticatedHomeServerEnabled = false)))
        assertFalse(allows(preferences = settings.copy(authenticatedHomeServerEnabled = null)))
    }
    @Test fun anotherRouterOrCertificateCannotAuthorizeReturn() {
        assertFalse(allows(origin = trusted.copy(certificateSha256 = "f".repeat(64))))
        assertFalse(allows(origin = trusted.copy(marker = trusted.marker.copy(pairingId = "other"))))
        assertFalse(allows(evidence = proof.copy(pairingId = "other")))
    }
    @Test fun staleFutureMissingAndUnroutedProofsDoNotStopThePhone() {
        assertFalse(allows(evidence = null))
        assertFalse(allows(now = 9_999L))
        assertFalse(allows(now = 15_001L))
        assertFalse(allows(evidence = proof.copy(thisPhoneRouted = false)))
        assertFalse(allows(evidence = proof.copy(tunnelValidated = false)))
        assertFalse(allows(evidence = proof.copy(serverId = "")))
        assertFalse(allows(evidence = proof.copy(generation = "")))
    }
}
