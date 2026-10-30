package com.foxhole.guard.router

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterRemoteClientControlTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val pair = RouterPairing(identity, "pair-" + "b".repeat(32), ByteArray(32) { 7 }, "Phone", true)
    private val clientId = "client-000000000001"
    private val profile = "hysteria2://client-secret@1.1.1.1:443?sni=example.com#Phone"

    private class Store(pair: RouterPairing) : RouterPairingStore {
        var current: RouterPairing? = pair.copy()
        val removals = mutableListOf<String>()
        override suspend fun read() = current?.copy()
        override suspend fun save(pairing: RouterPairing) { current?.clear(); current = pairing.copy() }
        override suspend fun remove(expectedId: String) { removals += expectedId; if (current?.id == expectedId) { current?.clear(); current = null } }
    }
    private class Preferences(var current: RouterControlPreferences) : RouterControlPreferenceStore {
        override suspend fun read(pairingId: String) = current
        override suspend fun save(preferences: RouterControlPreferences) { current = preferences }
    }
    private inner class Router {
        var removalAvailable = false
        var revision = 1L
        var present = true
        var proof: JsonObject? = buildJsonObject { put("id", clientId); put("applied", true); put("targetRevoked", true) }
        var exportId = clientId
        var exportUri = profile
        var exportConfig: String? = null
        var inactive = false
        var exportProvisioned = true
        var recordProvisioned = true
        var reappearOnReadback = false
        var lostReply: String? = null
        var hold: String? = null
        var delayed: (() -> Unit)? = null
        var onRevoke: (() -> Unit)? = null
        val actions = mutableListOf<String>()
        fun state() = buildJsonObject {
            put("packageRemovalAvailable", removalAvailable)
            put("schema", 1); put("revision", revision); put("settings", buildJsonObject { put("localRules", true); put("localScope", "all") })
            for (field in listOf("countries", "devices", "sites", "servers")) put(field, JsonArray(emptyList()))
            put("clients", JsonArray(if (present) listOf(buildJsonObject { put("id", clientId); put("name", "Phone"); put("provisioned", recordProvisioned) }) else emptyList()))
            put("runtime", buildJsonObject { put("desiredServerId", JsonNull); put("connected", false) })
        }
        fun api(): RouterPanelApi = RouterPanelApi(identity, Call.Factory { request ->
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
            val action = envelope["action"]!!.jsonPrimitive.content
            actions += action
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(callback: Callback) {
                    val reply = {
                        val body = when (action) {
                            "phone.capabilities" -> UpdateDocuments.capabilities()
                            "pair.unlock" -> buildJsonObject { put("token", "p1." + "c".repeat(64)) }
                            "state" -> { if (reappearOnReadback && actions.contains("client.delete")) present = true; state() }
                            "telemetry" -> buildJsonObject {
                                put("sample", buildJsonObject {
                                    put("wan", buildJsonObject { put("country", JsonNull) })
                                })
                            }
                            "client.get" -> buildJsonObject { put("export", buildJsonObject {
                                put("id", exportId); put("uri", exportUri); put("config", exportConfig ?: exportUri)
                                put("provisioned", exportProvisioned); put("inactive", inactive)
                            }) }
                            "client.delete" -> {
                                assertEquals(revision.toString(), envelope["payload"]!!.jsonObject["revision"]!!.jsonPrimitive.content)
                                revision++; present = false
                                JsonObject(state() + proof?.let { mapOf("clientRevocation" to it) }.orEmpty())
                            }
                            "pair.revoke" -> { onRevoke?.invoke(); buildJsonObject {} }
                            "package.remove" -> buildJsonObject {
                                put("pairingId", pair.id); put("revision", revision)
                                put("packageRemoved", true); put("ownedSettingsRestored", true)
                            }
                            else -> error("unexpected fixture action $action")
                        }
                        if (lostReply == action) callback.onFailure(this, IOException("lost fixture reply"))
                        else callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(body.toString().toResponseBody("application/json".toMediaType())).build())
                    }
                    if (hold == action) delayed = reply else reply()
                }
            }
        })
    }
    private fun coordinator(router: Router, store: Store, preferences: Preferences, copied: MutableList<String> = mutableListOf(), homeOff: suspend (Boolean) -> Unit = {}) =
        RouterControlCoordinator(object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = identity
            override fun api(identity: RouterTlsIdentity) = router.api()
        }, store, preferences, RouterHttpProvisioner(localAddress = { "192.168.1.101" }),
            applyHomeAutomation = homeOff, copyProfile = { copied += it })

    private suspend fun open(control: RouterControlCoordinator, expected: RouterControlStage = RouterControlStage.CONNECTED) {
        control.open()
        assertNull("Fixture initialization must succeed before testing the action", control.state.value.error)
        assertEquals(expected, control.state.value.stage)
    }

    @Test fun remoteCopySharesTheCompleteMatchingProfileWithoutSelectingAnOutbound() = runTest {
        val router = Router(); val copied = mutableListOf<String>()
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")), copied)
        open(control); control.copyRemoteClient(clientId)
        assertEquals(listOf(profile), copied)
        assertFalse(router.actions.contains("server.start"))
        control.close()
    }

    @Test fun anUnboundInactiveOrMalformedExportCannotReachTheClipboard() = runTest {
        for (kind in 0..5) {
            val router = Router().apply { when (kind) {
                0 -> exportId = "client-000000000002"
                1 -> inactive = true
                2 -> exportProvisioned = false
                3 -> exportConfig = profile + "different"
                4 -> exportUri = "not-a-profile"
                5 -> exportUri = "vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls#Phone"
            } }
            val copied = mutableListOf<String>()
            val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")), copied)
            open(control); control.copyRemoteClient(clientId)
            assertTrue(copied.isEmpty()); assertTrue(control.state.value.error != null)
            control.close()
        }
    }

    @Test fun deletionRequiresAnExactRevocationReceiptAndFreshAbsence() = runTest {
        val router = Router()
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); control.removeRemoteClient(clientId, 1)
        assertNull(control.state.value.error)
        assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
        assertEquals(listOf("pair.unlock", "state", "phone.capabilities", "telemetry", "state", "client.delete", "state"), router.actions)
        control.close()
    }

    @Test fun missingWrongOrStringRevocationProofNeverReportsDeletionSuccess() = runTest {
        val proofs = listOf(null, buildJsonObject { put("id", "client-000000000002"); put("applied", true); put("targetRevoked", true) },
            buildJsonObject { put("id", clientId); put("applied", "true"); put("targetRevoked", true) },
            buildJsonObject { put("id", clientId); put("applied", true); put("targetRevoked", false) })
        for (proof in proofs) {
            val router = Router().apply { this.proof = proof }
            val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")))
            open(control); control.removeRemoteClient(clientId, 1)
            assertTrue(control.state.value.error != null)
            control.close()
        }
    }

    @Test fun aClaimedRevocationWithTheClientStillPresentFailsReadback() = runTest {
        val router = Router().apply { reappearOnReadback = true }
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); control.removeRemoteClient(clientId, 1)
        assertTrue(control.state.value.error != null)
        control.close()
    }

    @Test fun aLostDeleteReplyRemainsUnknownEvenWhenTheRouterRemovedTheClient() = runTest {
        val router = Router().apply { lostReply = "client.delete" }
        val store = Store(pair)
        val control = coordinator(router, store, Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); control.removeRemoteClient(clientId, 1)
        assertFalse(router.present); assertTrue(control.state.value.error != null)
        assertEquals(pair.id, store.current?.id)
        control.close()
    }

    @Test fun aStaleConfirmationDoesNotDeleteAnotherRevision() = runTest {
        val router = Router()
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); router.revision = 2
        control.removeRemoteClient(clientId, 1)
        assertFalse(router.actions.contains("client.delete")); assertTrue(router.present)
        control.close()
    }

    @Test fun localUnpairingDisablesHomeAndKeepsRouterConfiguration() = runTest {
        val router = Router(); val store = Store(pair); val order = mutableListOf<String>()
        router.onRevoke = { order += "revoke"; assertEquals(pair.id, store.current?.id) }
        val control = coordinator(router, store, Preferences(RouterControlPreferences(pair.id, false, "NL")),
            homeOff = { assertFalse(it); order += "home-off" })
        open(control, RouterControlStage.DISABLED); control.unpair(pair.id)
        assertEquals(listOf("home-off"), order)
        assertFalse(router.actions.contains("pair.revoke"))
        assertNull(store.current); assertTrue(router.present)
        assertEquals(RouterControlStage.UNPAIRED, control.state.value.stage)
        assertFalse(router.actions.contains("client.delete")); assertFalse(router.actions.contains("server.stop"))
        control.close()
    }

    @Test fun unsupportedPackageRemovalKeepsTheKeyForRecovery() = runTest {
        val router = Router().apply { lostReply = "pair.revoke" }; val store = Store(pair)
        val control = coordinator(router, store, Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); control.unpair(pair.id, deletePackage = true)
        assertEquals(RouterControlStage.CONNECTED, control.state.value.stage)
        assertEquals(pair.id, store.current?.id); assertTrue(store.removals.isEmpty())
        assertTrue(control.state.value.error != null)
        control.close()
    }

    @Test fun closingDuringPackageRemovalCannotEraseAKeyFromANewerPairing() = runTest {
        val router = Router(); val store = Store(pair)
        val control = coordinator(router, store, Preferences(RouterControlPreferences(pair.id, true, "NL")))
        router.removalAvailable = true
        open(control); router.hold = "package.remove"
        val action = launch { control.unpair(pair.id, deletePackage = true) }; runCurrent()
        control.close()
        val newer = RouterPairing(identity, "pair-" + "d".repeat(32), ByteArray(32) { 8 }, "New phone", true)
        store.save(newer); router.delayed!!.invoke(); runCurrent()
        assertTrue(action.isCompleted); assertEquals(newer.id, store.current?.id)
        assertTrue(store.removals.isEmpty())
    }

    @Test fun aLateProfileReplyAfterClosingCannotReachTheClipboard() = runTest {
        val router = Router(); val copied = mutableListOf<String>()
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")), copied)
        open(control); router.hold = "client.get"
        val action = launch { control.copyRemoteClient(clientId) }; runCurrent()
        control.close(); router.delayed!!.invoke(); runCurrent()
        assertTrue(action.isCompleted); assertTrue(copied.isEmpty())
    }

    @Test fun aFailedHomeStopDoesNotRevokePairingOrEraseItsKey() = runTest {
        val router = Router(); val store = Store(pair)
        val control = coordinator(router, store, Preferences(RouterControlPreferences(pair.id, true, "NL")),
            homeOff = { throw RouterSetupFailure(RouterWizardError.UNAVAILABLE) })
        open(control); control.unpair(pair.id)
        assertFalse(router.actions.contains("pair.revoke"))
        assertEquals(pair.id, store.current?.id); assertTrue(store.removals.isEmpty())
        control.close()
    }

    @Test fun historicalRemovalRequiresRecordAcknowledgmentWithoutCredentialRevocation() = runTest {
        val router = Router().apply {
            recordProvisioned = false
            proof = buildJsonObject { put("id", clientId); put("applied", true); put("targetRevoked", false) }
        }
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); control.removeRemoteClient(clientId, 1)
        assertNull(control.state.value.error)
        assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
        control.close()
    }

    @Test fun historicalRemovalCannotAcceptAnUnboundCredentialRevocationClaim() = runTest {
        val router = Router().apply { recordProvisioned = false }
        val control = coordinator(router, Store(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")))
        open(control); control.removeRemoteClient(clientId, 1)
        assertTrue(control.state.value.error != null)
        control.close()
    }
}
