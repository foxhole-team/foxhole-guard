package com.foxhole.guard.router

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterDiscoveryCoordinatorTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private class Store : RouterPairingStore {
        var value: RouterPairing? = null
        var otherRouter: RouterPairing? = null
        override suspend fun read() = value?.copy()
        override suspend fun readAll() = listOfNotNull(value?.copy(), otherRouter?.copy())
        override suspend fun save(pairing: RouterPairing) { value?.clear(); value = pairing.copy() }
        override suspend fun remove(expectedId: String) { if (value?.id == expectedId) { value?.clear(); value = null } }
    }
    private class FixtureCall(private val request: Request, private val answer: String?) : Call by OkHttpClient().newCall(request) {
        override fun execute(): Response = error("blocking fixture forbidden")
        override fun enqueue(callback: Callback) {
            if (answer == null) callback.onFailure(this, IOException("synthetic lost reset reply"))
            else callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(answer.toResponseBody("application/json".toMediaType())).build())
        }
    }
    private inner class Fixture(
        val store: Store = Store(),
        var configured: Boolean = true,
        var tlsAvailable: Boolean = true,
        var statusValid: Boolean = true,
    ) {
        val actions = mutableListOf<String>()
        var resetReplyLost = false
        var resetObservation: (() -> Unit)? = null
        var sshInspections = 0
        var tlsInspections = 0
        var sshAvailable = true
        var discoveryAccess: suspend () -> Unit = {}
        var tlsInspection: suspend () -> Unit = {}
        val enrolled = mutableListOf<String>()
        val transport = object : RouterWizardTransport {
            override suspend fun requireDiscoveryAccess(endpoint: RouterEndpoint) { discoveryAccess() }
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
                tlsInspections++
                tlsInspection()
                if (!tlsAvailable) throw IOException("no synthetic TLS endpoint")
                return identity
            }
            override fun api(identity: RouterTlsIdentity) = RouterPanelApi(identity, Call.Factory { request ->
                val body = Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
                val action = requireNotNull(body["action"]).jsonPrimitive.content
                val payload = requireNotNull(body["payload"]).jsonObject
                actions += action
                val answer = when (action) {
                    "package.status" -> if (statusValid) """{"schema":1,"product":"foxhole","installed":true,"pinConfigured":$configured,"resetSupported":true}""" else "{}"
                    "unlock" -> """{"token":"${"b".repeat(64)}","pinChangeRequired":false}"""
                    "state" -> """{"revision":7}"""
                    "setup.reset" -> {
                        assertEquals("true", payload["confirm"]?.jsonPrimitive?.content)
                        assertEquals("7", payload["revision"]?.jsonPrimitive?.content)
                        resetObservation?.invoke()
                        if (resetReplyLost) null else """{"schema":1,"scope":"foxhole","reset":true,"revision":8,"token":"${"d".repeat(64)}","pinChangeRequired":true}"""
                    }
                    "pin.change" -> """{"token":"${"e".repeat(64)}"}"""
                    "pair.create" -> {
                        val id = requireNotNull(payload["id"]).jsonPrimitive.content
                        enrolled += id
                        """{"id":"$id"}"""
                    }
                    "pair.unlock" -> """{"token":"p1.${"c".repeat(64)}"}"""
                    "lock" -> "{}"
                    else -> error("unexpected fixture action")
                }
                FixtureCall(request, answer)
            })
        }
        val setup = object : RouterSetupService {
            override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
            override suspend fun inspectSshIdentity(address: String): ByteArray {
                sshInspections++
                if (!sshAvailable) throw IOException("no synthetic SSH endpoint")
                return byteArrayOf(1, 2, 3)
            }
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection): RouterInstallProposal = error("no password during discovery")
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("no install during discovery")
        }
        val coordinator = RouterWizardCoordinator(transport, store, "Phone", setup, object : RouterWizardProvisioner {
            override val supportedInboundProtocols = setOf("hysteria2")
            override val supportsHomeAutodetection = false
        override suspend fun routerCountry(api: RouterPanelApi, session: RouterPanelSession) = "NL"
            override suspend fun availableInboundProtocols(api: RouterPanelApi, session: RouterPanelSession) = supportedInboundProtocols
            override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
                options: RouterWizardOptions, selectedProfile: RouterProfileSelection?) = RouterWizardResult(pairing.id)
        }, { null }, { true })
        suspend fun find() { coordinator.begin(RouterWizardPath.PAIR, "NL"); coordinator.findRouter() }
        fun oldPair() = RouterPairing(identity, "pair-" + "1".repeat(32), ByteArray(32) { 3 }, "Phone", true,
            "server-" + "2".repeat(12), "3".repeat(64))
    }

    @Test fun retryKeepsNotFoundVisibleThroughBeginAndPendingDiscovery() = runTest {
        val f = Fixture(tlsAvailable = false)
        f.sshAvailable = false
        f.find()
        val previous = requireNotNull(f.coordinator.state.value.discovery)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.tlsInspection = { entered.complete(Unit); release.await() }
        f.coordinator.begin(RouterWizardPath.PAIR, null, keepNotFoundPresentation = true)
        assertEquals(previous, f.coordinator.state.value.discovery)
        val job = launch { f.coordinator.findRouter() }
        entered.await()
        assertTrue(f.coordinator.state.value.busy)
        assertEquals(previous, f.coordinator.state.value.discovery)
        release.complete(Unit)
        job.join()
        assertFalse(f.coordinator.state.value.busy)
        assertEquals(RouterDiscoveryKind.NOT_FOUND, f.coordinator.state.value.discovery?.kind)
        assertNotEquals(previous.discoveryId, f.coordinator.state.value.discovery?.discoveryId)
        f.coordinator.close()
    }

    @Test fun retryNeverRetainsInstalledAuthorityOrAnOldResultId() = runTest {
        val f = Fixture()
        f.find()
        val previous = requireNotNull(f.coordinator.state.value.discovery)
        assertEquals(RouterDiscoveryKind.INSTALLED, previous.kind)
        f.coordinator.begin(RouterWizardPath.PAIR, null, keepNotFoundPresentation = true)
        assertEquals(null, f.coordinator.state.value.discovery)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.tlsInspection = { entered.complete(Unit); release.await() }
        val job = launch { f.coordinator.findRouter() }
        entered.await()
        assertTrue(f.coordinator.state.value.busy)
        assertEquals(null, f.coordinator.state.value.discovery)
        release.complete(Unit)
        job.join()
        assertEquals(RouterDiscoveryKind.INSTALLED, f.coordinator.state.value.discovery?.kind)
        assertNotEquals(previous.discoveryId, f.coordinator.state.value.discovery?.discoveryId)
        f.coordinator.close()
    }

    @Test fun closeThenBeginFindsAgainWithANewOperationGeneration() = runTest {
        val f = Fixture()
        f.find()
        assertEquals(RouterDiscoveryKind.INSTALLED, f.coordinator.state.value.discovery?.kind)
        f.coordinator.close()
        f.find()
        assertEquals(RouterDiscoveryKind.INSTALLED, f.coordinator.state.value.discovery?.kind)
        assertEquals(null, f.coordinator.state.value.error)
        assertEquals(2, f.tlsInspections)
        f.coordinator.close()
    }

    @Test fun unprotectableVpnShowsTheWarningBeforeAnyDiscoveryAttempt() = runTest {
        val f = Fixture()
        f.discoveryAccess = { throw RouterVpnSocketUnavailable() }
        f.find()
        assertEquals(RouterWizardError.VPN_ACTIVE, f.coordinator.state.value.error)
        assertEquals(0, f.tlsInspections)
        assertEquals(0, f.sshInspections)
        assertTrue(f.actions.isEmpty())
    }

    @Test fun closingDuringPreflightPreventsALateTlsAttempt() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.discoveryAccess = { entered.complete(Unit); release.await() }
        f.coordinator.begin(RouterWizardPath.PAIR, "NL")
        val job = launch { f.coordinator.findRouter() }
        entered.await()
        f.coordinator.close()
        release.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(0, f.tlsInspections)
        assertEquals(0, f.sshInspections)
        assertTrue(f.actions.isEmpty())
    }

    @Test fun installedDiscoveryIsTypedAndDoesNotSendAnyCredential() = runTest {
        val f = Fixture(); f.find()
        assertEquals(RouterWizardStage.ENTRY, f.coordinator.state.value.stage)
        assertEquals(RouterDiscoveryKind.INSTALLED, f.coordinator.state.value.discovery?.kind)
        assertEquals(listOf("package.status"), f.actions)
        assertEquals(0, f.sshInspections)
        f.coordinator.connectDiscovered()
        assertEquals(RouterWizardStage.LOGIN, f.coordinator.state.value.stage)
        assertFalse(f.actions.contains("setup.reset"))
    }

    @Test fun unconfiguredPackageRequiresAuthenticatedLuciSetupWithoutAnInitialPinAttempt() = runTest {
        val f = Fixture(configured = false); f.find(); f.coordinator.connectDiscovered()
        assertEquals(RouterWizardStage.PIN_SETUP_REQUIRED, f.coordinator.state.value.stage)
        val secret = "1234".toCharArray(); f.coordinator.login(secret)
        assertArrayEquals(CharArray(4), secret)
        assertFalse(f.actions.contains("unlock"))
    }

    @Test fun sshReachabilityDoesNotClaimPackageAbsenceAndCanSelectInstallWithoutPassword() = runTest {
        val f = Fixture(tlsAvailable = false); f.find()
        assertEquals(RouterDiscoveryKind.SSH_AVAILABLE, f.coordinator.state.value.discovery?.kind)
        assertEquals(null, f.coordinator.state.value.discovery?.pinConfigured)
        assertTrue(f.actions.isEmpty())
        f.coordinator.installDiscovered()
        assertEquals(RouterWizardStage.PROTOCOLS, f.coordinator.state.value.stage)
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), f.coordinator.state.value.installSelection?.protocols)
    }

    @Test fun foreignTlsMetadataIsUnknownAndDoesNotSilentlyChooseInstallation() = runTest {
        val f = Fixture(statusValid = false); f.find()
        assertEquals(RouterDiscoveryKind.UNKNOWN, f.coordinator.state.value.discovery?.kind)
        assertEquals(0, f.sshInspections)
        f.coordinator.installDiscovered()
        assertEquals(RouterWizardError.UNAVAILABLE, f.coordinator.state.value.error)
    }

    @Test fun changedStoredIdentityBlocksDiscoveryBeforeApiOrPassword() = runTest {
        val f = Fixture()
        f.store.value = RouterPairing.create(RouterTlsIdentity(identity.endpoint, "f".repeat(64)), "Phone")
        f.find()
        assertEquals(RouterWizardError.IDENTITY, f.coordinator.state.value.error)
        assertTrue(f.actions.isEmpty())
        assertEquals(0, f.sshInspections)
    }

    @Test fun connectWithoutResetPreservesExistingPairingAndPendingSync() = runTest {
        val f = Fixture(); f.store.value = f.oldPair()
        f.find(); f.coordinator.connectDiscovered(); f.coordinator.login("5678".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, f.coordinator.state.value.stage)
        assertFalse(f.actions.contains("setup.reset"))
        assertEquals("pair-" + "1".repeat(32), f.store.value?.id)
        assertEquals("server-" + "2".repeat(12), f.store.value?.pendingServerId)
    }

    @Test fun resetAcknowledgmentRemovesOnlyOldRouterSyncIdentityAndCreatesFreshEnrollment() = runTest {
        val f = Fixture(); f.store.value = f.oldPair()
        val oldId = requireNotNull(f.store.value).id
        val oldKey = requireNotNull(f.store.value).credential.copyOf()
        f.resetObservation = { assertEquals(oldId, f.store.value?.id); assertEquals("server-" + "2".repeat(12), f.store.value?.pendingServerId) }
        f.find(); f.coordinator.connectDiscovered(resetFoxHole = true); f.coordinator.login("5678".toCharArray())
        assertEquals(RouterWizardStage.NEW_PIN, f.coordinator.state.value.stage)
        assertEquals(null, f.store.value)
        f.coordinator.changePin("7890".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, f.coordinator.state.value.stage)
        assertNotEquals(oldId, f.store.value?.id)
        assertFalse(oldKey.contentEquals(requireNotNull(f.store.value).credential))
        assertEquals(null, f.store.value?.pendingServerId)
        assertEquals(null, f.store.value?.pendingSyncSha256)
        assertEquals(listOf(requireNotNull(f.store.value).id), f.enrolled)
    }

    @Test fun lostResetReplyRetainsTheOldPairAndPendingSyncForHonestRecovery() = runTest {
        val f = Fixture(); f.store.value = f.oldPair(); f.resetReplyLost = true
        f.find(); f.coordinator.connectDiscovered(resetFoxHole = true); f.coordinator.login("5678".toCharArray())
        assertEquals(RouterWizardError.NETWORK, f.coordinator.state.value.error)
        assertEquals(RouterWizardStage.LOGIN, f.coordinator.state.value.stage)
        assertEquals("pair-" + "1".repeat(32), f.store.value?.id)
        assertEquals("server-" + "2".repeat(12), f.store.value?.pendingServerId)
        assertTrue(f.enrolled.isEmpty())
    }

    @Test fun anotherLocalPairCreatedDuringResetReplyIsPreservedAndNotReenrolled() = runTest {
        val f = Fixture(); f.store.value = f.oldPair()
        val replacement = RouterPairing.create(identity, "Other phone")
        f.resetObservation = { f.store.value?.clear(); f.store.value = replacement.copy() }
        f.find(); f.coordinator.connectDiscovered(resetFoxHole = true); f.coordinator.login("5678".toCharArray())
        assertEquals(RouterWizardError.UNAVAILABLE, f.coordinator.state.value.error)
        assertEquals(replacement.id, f.store.value?.id)
        assertTrue(f.enrolled.isEmpty())
        replacement.clear()
    }

    @Test fun resetAcknowledgmentPreservesAnUnrelatedRouterThroughFreshEnrollment() = runTest {
        val f = Fixture(); f.store.value = f.oldPair()
        val other = RouterPairing.create(RouterTlsIdentity(RouterEndpoint("192.168.50.1"), "f".repeat(64)), "Other router")
        f.store.otherRouter = other.copy()
        try {
            f.find(); f.coordinator.connectDiscovered(resetFoxHole = true); f.coordinator.login("5678".toCharArray())
            assertEquals(RouterWizardStage.NEW_PIN, f.coordinator.state.value.stage)
            assertEquals(null, f.coordinator.state.value.error)
            assertEquals(other.id, f.store.otherRouter?.id)
            f.coordinator.changePin("7890".toCharArray())
            assertEquals(RouterWizardStage.OPTIONS, f.coordinator.state.value.stage)
            val created = requireNotNull(f.store.value)
            assertNotEquals(other.id, created.id)
            assertEquals(listOf(created.id), f.enrolled)
            assertEquals(other.id, f.store.otherRouter?.id)
            assertArrayEquals(other.credential, requireNotNull(f.store.otherRouter).credential)
        } finally {
            f.coordinator.close()
            f.store.value?.clear(); f.store.otherRouter?.clear(); other.clear()
        }
    }
}
