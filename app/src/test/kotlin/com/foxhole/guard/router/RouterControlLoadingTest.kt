package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterControlLoadingTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val pair = RouterPairing(identity, "pair-" + "b".repeat(32), ByteArray(32) { 7 }, "Phone", true)
    private class PairStore(private val pairing: RouterPairing) : RouterPairingStore {
        override suspend fun read() = pairing.copy()
        override suspend fun save(pairing: RouterPairing) = Unit
        override suspend fun remove(expectedId: String) = error("unrelated")
    }
    private class Preferences(private val value: RouterControlPreferences) : RouterControlPreferenceStore {
        val versions = mutableMapOf<Pair<String, String>, String>()
        override suspend fun read(pairingId: String) = value
        override suspend fun save(preferences: RouterControlPreferences) = Unit
        override suspend fun readAuthenticatedVersion(pairingId: String, certificateSha256: String) = versions[pairingId to certificateSha256]
        override suspend fun saveAuthenticatedVersion(pairingId: String, certificateSha256: String, version: String): Boolean {
            versions[pairingId to certificateSha256] = version; return true
        }
    }
    private class Fixture {
        var hold: String? = null
        var delayed: (() -> Unit)? = null
        var updates = false
        val actions = mutableListOf<String>()
        fun api(identity: RouterTlsIdentity) = RouterPanelApi(identity, Call.Factory { request ->
            val body = kotlinx.serialization.json.Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
            val action = body["action"]!!.jsonPrimitive.content
            actions += action
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(callback: Callback) {
                    val reply = {
                        val result = when (action) {
                            "pair.unlock" -> buildJsonObject { put("token", "p1." + "c".repeat(64)) }
                            "state" -> buildJsonObject {
                                put("schema", 1); put("revision", 1)
                                put("settings", buildJsonObject { put("localRules", false); put("localScope", "all") })
                                listOf("countries", "devices", "sites", "servers", "clients").forEach { put(it, JsonArray(emptyList())) }
                                put("runtime", buildJsonObject { put("desiredServerId", JsonNull); put("connected", false) })
                            }
                            "phone.capabilities" -> JsonObject(UpdateDocuments.capabilities() + ("packageUpdates" to JsonPrimitive(updates)))
                            "updates.status" -> UpdateDocuments.status()
                            "device.list" -> buildJsonObject { put("devices", JsonArray(emptyList())) }
                            else -> error("unexpected action $action")
                        }
                        callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(result.toString().toResponseBody("application/json".toMediaType())).build())
                    }
                    if (hold == action) delayed = reply else reply()
                }
                override fun cancel() = Unit
            }
        })
    }
    private fun transport(fixture: Fixture) = object : RouterWizardTransport {
        override fun gateway() = "192.168.1.1"
        override suspend fun inspectIdentity(endpoint: RouterEndpoint) = identity
        override fun api(identity: RouterTlsIdentity) = fixture.api(identity)
    }

    @Test fun authenticatedEmptyDeviceReplyFinishesLoadingInsteadOfLookingUnreceived() = runTest {
        val fixture = Fixture()
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair),
            Preferences(RouterControlPreferences(pair.id, enabled = true, publicIpConfirmed = true)),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.preparePairedOpen(); coordinator.open()
        assertFalse(coordinator.state.value.lanDevicesLoaded)
        coordinator.refreshDevices()
        assertTrue(coordinator.state.value.lanDevicesLoaded)
        assertFalse(coordinator.state.value.lanDevicesLoading)
        assertFalse(coordinator.state.value.lanDevicesFailed)
        assertTrue(coordinator.state.value.lanDevices.isEmpty())
        assertFalse(fixture.actions.any { it in setOf("settings", "server.start", "updates.apply", "client.save") })
        coordinator.close()
    }

    @Test fun deviceReadDeadlineEndsSpinnerAndLateReplyCannotPublishData() = runTest {
        val fixture = Fixture()
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair),
            Preferences(RouterControlPreferences(pair.id, enabled = true, publicIpConfirmed = true)),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.preparePairedOpen(); coordinator.open(); fixture.hold = "device.list"
        val job = launch { coordinator.refreshDevices() }; runCurrent()
        assertTrue(coordinator.state.value.lanDevicesLoading)
        assertFalse(coordinator.state.value.lanDevicesLoaded)
        advanceTimeBy(15_001); runCurrent(); job.join()
        assertFalse(coordinator.state.value.lanDevicesLoading)
        assertFalse(coordinator.state.value.lanDevicesLoaded)
        assertTrue(coordinator.state.value.lanDevicesFailed)
        assertEquals(RouterControlStage.OFFLINE, coordinator.state.value.stage)
        requireNotNull(fixture.delayed).invoke(); runCurrent()
        assertFalse(coordinator.state.value.lanDevicesLoaded)
        assertEquals(null, coordinator.state.value.snapshot)
        coordinator.close()
    }

    @Test fun closeInvalidatesAnOwnedDeviceReplyAndAllLoadingFlags() = runTest {
        val fixture = Fixture()
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair),
            Preferences(RouterControlPreferences(pair.id, enabled = true, publicIpConfirmed = true)),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.preparePairedOpen(); coordinator.open(); fixture.hold = "device.list"
        val job = launch { coordinator.refreshDevices() }; runCurrent(); coordinator.close()
        requireNotNull(fixture.delayed).invoke(); runCurrent(); job.join()
        assertFalse(coordinator.state.value.lanDevicesLoading)
        assertFalse(coordinator.state.value.lanDevicesLoaded)
        assertTrue(coordinator.state.value.lanDevices.isEmpty())
        assertEquals(null, coordinator.state.value.snapshot)
    }

    @Test fun entryDeadlineNeverLeavesAStaleConnectedSnapshotOrBusySpinner() = runTest {
        val fixture = Fixture().apply { hold = "state" }
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair),
            Preferences(RouterControlPreferences(pair.id, enabled = true, publicIpConfirmed = true)),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.preparePairedOpen()
        val job = launch { coordinator.open() }; runCurrent()
        assertEquals(RouterControlStage.CONNECTING, coordinator.state.value.stage)
        advanceTimeBy(40_001); runCurrent(); job.join()
        assertFalse(coordinator.state.value.busy)
        assertEquals(RouterControlStage.OFFLINE, coordinator.state.value.stage)
        requireNotNull(fixture.delayed).invoke(); runCurrent()
        assertEquals(null, coordinator.state.value.snapshot)
        coordinator.close()
    }

    @Test fun pairedVersionCacheUsesIdentityAndOnlyExistingAuthenticatedStatus() = runTest {
        val fixture = Fixture().apply { updates = true }
        val preferences = Preferences(RouterControlPreferences(pair.id, enabled = true, publicIpConfirmed = true))
        preferences.versions[pair.id to identity.certificateSha256] = "1.0.2-r1"
        preferences.versions[pair.id to "f".repeat(64)] = "9.9.9-r1"
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.loadPairingState()
        assertEquals("1.0.2-r1", coordinator.state.value.lastAuthenticatedPackageVersion)
        coordinator.preparePairedOpen(); coordinator.open()
        assertEquals("1.0.0-r2", coordinator.state.value.lastAuthenticatedPackageVersion)
        assertEquals("1.0.0-r2", preferences.versions[pair.id to identity.certificateSha256])
        assertEquals("9.9.9-r1", preferences.versions[pair.id to "f".repeat(64)])
        assertFalse(fixture.actions.any { it in setOf("package.status", "settings", "server.start", "updates.apply", "updates.plan") })
        coordinator.close()
    }
}
