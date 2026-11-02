package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterPublicIpPolicyTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val pair = RouterPairing(identity, "pair-" + "b".repeat(32), ByteArray(32) { 7 }, "Phone", true)
    private class Preferences(var current: RouterControlPreferences) : RouterControlPreferenceStore {
        override suspend fun read(pairingId: String) = current
        override suspend fun save(preferences: RouterControlPreferences) { current = preferences }
    }
    private inner class Fixture {
        val actions = mutableListOf<String>()
        val preferences = Preferences(RouterControlPreferences(pair.id).afterSuccessfulPairing())
        val store = object : RouterPairingStore {
            override suspend fun read() = pair.copy()
            override suspend fun readById(id: String) = pair.copy().takeIf { id == pair.id }
            override suspend fun save(pairing: RouterPairing) = Unit
            override suspend fun remove(expectedId: String) = error("unrelated")
        }
        fun api() = RouterPanelApi(identity, Call.Factory { request ->
            val action = Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8())
                .jsonObject.getValue("action").jsonPrimitive.content
            actions += action
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(responseCallback: Callback) {
                    val result = when (action) {
                        "pair.unlock" -> buildJsonObject { put("token", "p1." + "c".repeat(64)) }
                        "state" -> buildJsonObject {
                            put("schema", 1); put("revision", 1)
                            put("settings", buildJsonObject { put("localRules", true); put("localScope", "all") })
                            put("network", buildJsonObject { put("schema", 1); put("source", "default-uplink");put("publicIpv4Available", true) })
                            for (key in listOf("countries", "devices", "sites", "clients", "servers")) put(key, JsonArray(emptyList()))
                            put("runtime", buildJsonObject { put("desiredServerId", JsonNull);put("connected", false) })
                        }
                        else -> error("unexpected fixture action")
                    }
                    responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build())
                }
            }
        })
        val transport = object : RouterWizardTransport {
            override fun gateway() = identity.endpoint.address
            override suspend fun inspectIdentity(endpoint: RouterEndpoint) = identity
            override fun api(identity: RouterTlsIdentity) = api()
        }
        fun coordinator() = RouterControlCoordinator(transport, store, preferences,
            RouterHttpProvisioner(localAddress = { error("no mutation") }), copyProfile = {})
    }
    @Test fun freshPairingEnablesMainManagementWithoutClaimingStaticPublicIp() {
        val preferences = RouterControlPreferences(pair.id, countryCode = "DE", countryAuto = false).afterSuccessfulPairing()
        assertTrue(preferences.enabled);assertFalse(preferences.publicIpConfirmed);assertFalse(preferences.homeVpnManagementEnabled)
        assertEquals(preferences, preferences.withPublicIpv4Evidence(true))
        assertEquals("DE", preferences.countryCode);assertFalse(preferences.countryAuto)
    }
    @Test fun existingManualChoiceSurvivesPairingAndPublicEvidence() {
        val original = RouterControlPreferences(pair.id, publicIpConfirmed = true, homeVpnManagementEnabled = true)
        val paired = original.afterSuccessfulPairing().withPublicIpv4Evidence(true)
        assertTrue(paired.enabled);assertTrue(paired.publicIpConfirmed);assertTrue(paired.homeVpnManagementEnabled)
        val optedOut = paired.copy(publicIpConfirmed = false, homeVpnManagementEnabled = false)
        assertEquals(optedOut, optedOut.withPublicIpv4Evidence(true))
    }
    @Test fun legacyPendingDefaultCannotConvertPublicDetectionIntoConfirmation() {
        val observed = RouterControlPreferences(pair.id, homeVpnDefaultPending = true).withPublicIpv4Evidence(true)
        assertFalse(observed.homeVpnDefaultPending);assertFalse(observed.publicIpConfirmed);assertFalse(observed.homeVpnManagementEnabled)
    }
    @Test fun vpnManagementCannotBeEnabledWithoutTheEditablePublicIpChoice() = runTest {
        val fixture = Fixture();val coordinator = fixture.coordinator()
        try {
            coordinator.loadPairingState();coordinator.setHomeVpnManagement(true)
            assertFalse(fixture.preferences.current.homeVpnManagementEnabled)
            assertEquals(RouterWizardError.UNSUPPORTED, coordinator.state.value.error)
            assertTrue(fixture.actions.isEmpty())
        } finally { coordinator.close() }
    }
    @Test fun detectedPublicIpStillEnablesClientFactsButNotVpnManagementOptIn() = runTest {
        val fixture = Fixture()
        // Read the paired settings without invoking unrelated package-update capability checks.
        fixture.preferences.current = fixture.preferences.current.copy(enabled = false)
        val coordinator = fixture.coordinator()
        try {
            coordinator.preparePairedOpen();coordinator.open()
            assertEquals(true, coordinator.state.value.snapshot?.publicIpv4Available)
            assertTrue(coordinator.state.value.hasConfirmedDirectIp)
            assertFalse(fixture.preferences.current.publicIpConfirmed)
            coordinator.setHomeVpnManagement(true)
            assertFalse(fixture.preferences.current.homeVpnManagementEnabled)
            assertEquals(RouterWizardError.UNSUPPORTED, coordinator.state.value.error)
            assertEquals(listOf("pair.unlock", "state"), fixture.actions)
        } finally { coordinator.close() }
    }
    @Test fun manualPublicIpOptOutAlwaysClearsVpnManagementWithoutRouterWrites() = runTest {
        val fixture = Fixture();fixture.preferences.current = fixture.preferences.current.copy(publicIpConfirmed = true, homeVpnManagementEnabled = true)
        val coordinator = fixture.coordinator()
        try {
            coordinator.loadPairingState();coordinator.setPublicIpConfirmed(false)
            assertFalse(fixture.preferences.current.publicIpConfirmed);assertFalse(fixture.preferences.current.homeVpnManagementEnabled)
            assertTrue(fixture.actions.isEmpty())
        } finally { coordinator.close() }
    }
}
