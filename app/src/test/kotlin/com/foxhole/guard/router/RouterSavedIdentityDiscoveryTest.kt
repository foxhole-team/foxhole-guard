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
    private fun pair(index: Int, address: String = "192.168.1.1") = RouterPairing(RouterTlsIdentity(RouterEndpoint(address), index.toString(16).padStart(64, '0')),
        "pair-${index.toString(16).padStart(32, '0')}", ByteArray(32) { index.toByte() }, "Phone")
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
    @Test fun legacyElevenRecordsCanStillMatchTheCurrentGatewayWithoutCredentialUse() = runTest {
        val saved = (1..11).map { pair(it) }
        try {
            val transport = Transport(saved[10].identity)
            val result = discoverSavedRouterIdentity(transport, saved)
            assertEquals(saved[10].id, result.pairingId)
            assertEquals(11, result.statuses.size)
            assertEquals(listOf("192.168.1.1"), transport.addresses)
            assertEquals(0, transport.credentials)
        } finally { saved.forEach(RouterPairing::clear) }
    }


    @Test fun advertisedLanAliasStillRequiresTheStoredCertificateBeforeCredentialUse() = runTest {
        val saved = pair(1)
        val alias = RouterTlsIdentity(RouterEndpoint("192.168.1.9"), saved.identity.certificateSha256)
        var advertised = 0;var credentials = 0
        val transport = object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun advertisedCandidates(): List<RouterEndpoint> { advertised++;return listOf(alias.endpoint) }
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
                if (endpoint == alias.endpoint) return alias
                throw java.io.IOException("fixture_old_address")
            }
            override fun api(identity: RouterTlsIdentity): RouterPanelApi { credentials++;error("discovery only") }
        }
        try {
            val result = discoverSavedRouterIdentity(transport, listOf(saved))
            assertEquals(saved.id, result.pairingId);assertEquals(alias, result.identity)
            assertEquals(1, advertised);assertEquals(0, credentials)
        } finally { saved.clear() }
    }
    @Test fun advertisedCandidatesAreBoundedAndCannotReplaceTrust() = runTest {
        val saved = pair(1);var inspections = 0
        val transport = object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun advertisedCandidates() = (2..20).map { RouterEndpoint("192.168.1.$it") }
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
                inspections++;return RouterTlsIdentity(endpoint, "0".repeat(64))
            }
            override fun api(identity: RouterTlsIdentity): RouterPanelApi = error("untrusted")
        }
        try {
            val result = discoverSavedRouterIdentity(transport, listOf(saved))
            assertNull(result.pairingId);assertTrue(result.unknownIdentity);assertEquals(5, inspections)
        } finally { saved.clear() }
    }

}
