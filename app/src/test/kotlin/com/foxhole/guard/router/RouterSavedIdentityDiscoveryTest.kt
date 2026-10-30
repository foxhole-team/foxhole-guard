package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterSavedIdentityDiscoveryTest {
    private fun pair(index: Int, address: String = "192.168.1.1") = RouterPairing(RouterTlsIdentity(RouterEndpoint(address), index.toString().repeat(64)),
        "pair-${index.toString().repeat(32)}", ByteArray(32) { index.toByte() }, "Phone")
    private class Transport(val observed: RouterTlsIdentity?, val gatewayAddress: String = "192.168.1.1") : RouterWizardTransport {
        val addresses = mutableListOf<String>()
        var credentials = 0
        var release: CompletableDeferred<Unit>? = null
        override fun gateway() = gatewayAddress
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
            addresses += endpoint.address
            release?.await()
            return observed?.takeIf { it.endpoint == endpoint } ?: RouterTlsIdentity(endpoint, "a".repeat(64))
        }
        override fun api(identity: RouterTlsIdentity): RouterPanelApi { credentials++; error("identity discovery cannot authenticate") }
    }

    @Test fun equalGatewayAddressesAreDistinguishedByThePinnedCertificateBeforeAnyCredentialRequest() = runTest {
        val saved = (1..3).map { pair(it) }
        try {
            val transport = Transport(saved[1].identity)
            val result = discoverSavedRouterIdentity(transport, saved)
            assertEquals(saved[1].id, result.pairingId)
            assertEquals(listOf("192.168.1.1"), transport.addresses)
            assertEquals(0, transport.credentials)
            assertEquals(RouterSavedStatus.AVAILABLE, result.statuses[saved[1].id])
        } finally { saved.forEach(RouterPairing::clear) }
    }

    @Test fun unknownGatewayCannotHideTheThirdSavedManagementAddress() = runTest {
        val saved = (1..3).map { pair(it, "192.168.1.${it + 1}") }
        try {
            val transport = Transport(saved[2].identity)
            val result = discoverSavedRouterIdentity(transport, saved)
            assertEquals(saved[2].id, result.pairingId)
            assertEquals(listOf("192.168.1.1", "192.168.1.2", "192.168.1.3", "192.168.1.4"), transport.addresses)
            assertTrue(result.unknownIdentity)
            assertEquals(0, transport.credentials)
        } finally { saved.forEach(RouterPairing::clear) }
    }

    @Test fun unknownOrChangedFingerprintNeverUsesSavedPairingCredentials() = runTest {
        val saved = listOf(pair(1))
        try {
            val transport = Transport(saved.single().identity.copy(certificateSha256 = "b".repeat(64)))
            val result = discoverSavedRouterIdentity(transport, saved)
            assertNull(result.pairingId)
            assertTrue(result.unknownIdentity)
            assertEquals(0, transport.credentials)
            assertArrayEquals(ByteArray(32) { 1 }, saved.single().credential)
        } finally { saved.forEach(RouterPairing::clear) }
    }

    @Test fun aLateIdentityResponseCannotSelectAfterItsOperationOwnerChanges() = runTest {
        val saved = listOf(pair(1))
        try {
            val transport = Transport(saved.single().identity).also { it.release = CompletableDeferred() }
            var owned = true
            var published = false
            val pending = launch {
                try { discoverSavedRouterIdentity(transport, saved) { if (!owned) throw CancellationException("fixture_owner_changed") }; published = true }
                catch (_: CancellationException) { }
            }
            runCurrent(); owned = false; transport.release!!.complete(Unit); runCurrent()
            assertTrue(pending.isCompleted)
            assertFalse(published)
            assertEquals(0, transport.credentials)
        } finally { saved.forEach(RouterPairing::clear) }
    }
}
