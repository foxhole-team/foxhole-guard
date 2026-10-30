package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RouterMultiWizardTest {
    private fun pair(index: Int) = RouterPairing(RouterTlsIdentity(RouterEndpoint("192.168.1.1"), index.toString().repeat(64)),
        "pair-${index.toString().repeat(32)}", ByteArray(32) { index.toByte() }, "Phone", true)
    private class Transport(var observed: RouterTlsIdentity) : RouterWizardTransport {
        var discoveries = 0
        var apiCreations = 0
        override fun gateway() = "192.168.1.1"
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity { discoveries++; return observed.copy(endpoint = endpoint) }
        override fun api(identity: RouterTlsIdentity): RouterPanelApi {
            apiCreations++
            return RouterPanelApi(identity, Call.Factory { error("credentials were not requested") })
        }
    }
    private fun wizard(store: RouterPairingStore, transport: RouterWizardTransport) = RouterWizardCoordinator(transport, store, "Synthetic phone", null,
        object : RouterWizardProvisioner {
            override val supportedInboundProtocols = emptySet<String>()
            override val supportsHomeAutodetection = false
            override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
                options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult = error("not needed")
        }, { null }, { false })

    @Test fun explicitAddPermitsANewManualIdentityWhileTheNormalPathStillRefusesAChangedKey() = runTest {
        val store = MemoryRouterPairingStore()
        val original = pair(1); val other = pair(2)
        try {
            store.save(original)
            val transport = Transport(other.identity)
            val wizard = wizard(store, transport)
            wizard.begin(RouterWizardPath.PAIR, null, addingNew = true)
            wizard.inspectAddress("192.168.1.1")
            assertFalse(wizard.state.value.identityChanged)
            wizard.acceptIdentity()
            assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
            assertEquals(1, transport.apiCreations)
            wizard.begin(RouterWizardPath.PAIR, null)
            wizard.inspectAddress("192.168.1.1")
            assertTrue(wizard.state.value.identityChanged)
            wizard.acceptIdentity()
            assertEquals(RouterWizardError.IDENTITY, wizard.state.value.error)
            assertEquals(1, transport.apiCreations)
            val retained = requireNotNull(store.readById(original.id))
            try { assertArrayEquals(original.credential, retained.credential) } finally { retained.clear() }
            wizard.close()
        } finally { store.close(); original.clear(); other.clear() }
    }

    @Test fun aFourthAdditionFailsBeforeDiscoveryOrCredentialTransport() = runTest {
        val store = MemoryRouterPairingStore()
        val pairs = (1..3).map(::pair)
        try {
            pairs.forEach { store.save(it) }
            val transport = Transport(pair(4).identity)
            val wizard = wizard(store, transport)
            wizard.begin(RouterWizardPath.PAIR, null, addingNew = true)
            wizard.findRouter()
            assertEquals(RouterWizardError.LIMIT, wizard.state.value.error)
            assertEquals(0, transport.discoveries); assertEquals(0, transport.apiCreations)
            wizard.login("synthetic-password".toCharArray())
            assertEquals(RouterWizardError.LIMIT, wizard.state.value.error)
            assertEquals(0, transport.apiCreations)
            wizard.close()
        } finally { store.close(); pairs.forEach(RouterPairing::clear) }
    }

    @Test fun knownCertificateAtANewAddressRetainsItsPairingInsteadOfAddingADuplicate() = runTest {
        val store = MemoryRouterPairingStore()
        val original = pair(1)
        try {
            store.save(original)
            val moved = original.identity.copy(endpoint = RouterEndpoint("192.168.1.2"))
            val transport = Transport(moved)
            val wizard = wizard(store, transport)
            wizard.begin(RouterWizardPath.PAIR, null, addingNew = true)
            wizard.inspectAddress("192.168.1.2")
            assertEquals(1, store.listSaved().routers.size)
            val retained = requireNotNull(store.read())
            try { assertEquals(original.id, retained.id); assertEquals(moved, retained.identity); assertArrayEquals(original.credential, retained.credential) }
            finally { retained.clear() }
            wizard.close()
        } finally { store.close(); original.clear() }
    }
}
