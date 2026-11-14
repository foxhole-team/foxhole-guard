package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test

class RouterSmartPolicyTest {
    private val pairId = "pair-" + "a".repeat(32)
    private val certificate = "b".repeat(64)
    private val profile = RouterConnectedProfile("profile", "c".repeat(64), "generation", 42L)
    private val pause = RouterSmartPause(profile, pairId, "12345678-1234-1234-1234-123456789abc")
    private val pair = RouterPairing(RouterTlsIdentity(RouterEndpoint("192.168.1.1"), certificate), pairId, ByteArray(32), "Fixture", true)
    private val settings = RouterControlPreferences(pairId, enabled = true, homeAutodetection = true,
        publicIpConfirmed = true, homeVpnManagementEnabled = true, authenticatedHomeServerEnabled = true)
    private val trusted = RouterTrustedProfile(profile.id, profile.configSha256, certificate,
        RouterProfileMarker(1, "foxhole-openwrt-client", pairId, "client-" + "d".repeat(12), "e".repeat(64)))
    private fun allows(prefs: RouterControlPreferences = settings, origin: RouterTrustedProfile? = trusted,
        saved: RouterPairing = pair, network: RouterSmartNetwork = RouterSmartNetwork.CELLULAR,
        idle: Boolean = true, owned: Boolean = true, anotherVpn: Boolean = false) =
        routerSmartResumeAllowed(pause, origin, saved, prefs, network, idle, owned, anotherVpn)

    @Test fun ownedUnchangedProfileCanResumeOnlyWithEveryExplicitConsent() { assertTrue(allows()) }
    @Test fun smartDefaultsOffAndUnknownOrDisabledServerCannotResume() {
        assertFalse(RouterControlPreferences(pairId).homeAutodetection)
        assertFalse(allows(settings.copy(homeAutodetection = false)))
        assertFalse(allows(settings.copy(authenticatedHomeServerEnabled = false)))
        assertFalse(allows(settings.copy(authenticatedHomeServerEnabled = null)))
        assertFalse(allows(settings.copy(homeVpnManagementEnabled = false)))
        assertFalse(allows(settings.copy(publicIpConfirmed = false)))
        assertFalse(allows(settings.copy(enabled = false)))
    }
    @Test fun otherWifiAndUnknownNetworksNeverConnectAutomatically() {
        for (network in listOf(RouterSmartNetwork.HOME_WIFI, RouterSmartNetwork.OTHER_WIFI, RouterSmartNetwork.UNKNOWN))
            assertFalse(allows(network = network))
    }
    @Test fun anotherVpnPendingWorkAndManualOwnershipLossAreNotReplaced() {
        assertFalse(allows(idle = false)); assertFalse(allows(owned = false)); assertFalse(allows(anotherVpn = true))
    }
    @Test fun changedProfileCertificateOrPairingRejectsResume() {
        assertFalse(allows(origin = null))
        assertFalse(allows(origin = trusted.copy(profileId = "other")))
        assertFalse(allows(origin = trusted.copy(configSha256 = "f".repeat(64))))
        assertFalse(allows(origin = trusted.copy(certificateSha256 = "f".repeat(64))))
        assertFalse(allows(settings.copy(pairingId = "other")))
        val removed = RouterPairing(pair.identity, pairId, ByteArray(32), "Fixture", false)
        try { assertFalse(allows(saved = removed)) } finally { removed.clear() }
    }
    @Test fun ninetySecondWindowStartsAtHomeLossEvenAfterADayAtHome() {
        val window = RouterSmartTransitionWindow()
        window.observe(RouterSmartNetwork.HOME_WIFI, 0)
        window.observe(RouterSmartNetwork.HOME_WIFI, 86_400_000)
        assertFalse(window.observe(RouterSmartNetwork.UNKNOWN, 86_400_001))
        assertTrue(window.observe(RouterSmartNetwork.CELLULAR, 86_490_001))
        assertFalse(window.observe(RouterSmartNetwork.CELLULAR, 86_490_002))
        assertTrue(window.expired(86_490_002))
    }
    @Test fun manualControlRemainsAnOverrideWhileStillHome() {
        val window = RouterSmartTransitionWindow(); window.manualAction()
        assertFalse(window.permitsPause())
        window.observe(RouterSmartNetwork.HOME_WIFI, 100)
        assertFalse(window.permitsPause())
        window.observe(RouterSmartNetwork.CELLULAR, 200)
        assertTrue(window.permitsPause())
    }
    @Test fun transientUnknownNetworkDoesNotCancelTheManualOverrideAtHome() {
        val window = RouterSmartTransitionWindow(); window.manualAction()
        assertFalse(window.observe(RouterSmartNetwork.HOME_WIFI, 100))
        assertFalse(window.observe(RouterSmartNetwork.UNKNOWN, 200))
        assertFalse(window.permitsPause())
        assertFalse(window.observe(RouterSmartNetwork.HOME_WIFI, 300))
        assertFalse(window.permitsPause())
        window.observe(RouterSmartNetwork.CELLULAR, 400)
        assertTrue(window.permitsPause())
    }
    @Test fun anotherWifiInvalidatesTheTransitionInsteadOfDeferringItUntilLaterCellular() {
        val window = RouterSmartTransitionWindow()
        assertFalse(window.observe(RouterSmartNetwork.OTHER_WIFI, 100))
        assertFalse(window.observe(RouterSmartNetwork.CELLULAR, 101))
    }
}
