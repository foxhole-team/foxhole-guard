package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterControlReviewActionsTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val pairing = RouterPairing(identity, "pair-" + "b".repeat(32), ByteArray(32) { 7 }, "Phone", true)

    private class Store(pair: RouterPairing) : RouterPairingStore {
        var current: RouterPairing? = pair.copy()
        override suspend fun read() = current?.copy()
        override suspend fun save(pairing: RouterPairing) { current?.clear(); current = pairing.copy() }
        override suspend fun remove(expectedId: String) {
            if (current?.id == expectedId) { current?.clear(); current = null }
        }
    }

    private class Preferences(var value: RouterControlPreferences) : RouterControlPreferenceStore {
        override suspend fun read(pairingId: String) = value
        override suspend fun save(preferences: RouterControlPreferences) { value = preferences }
    }

    private fun localControl(store: Store, preferences: Preferences, onNetwork: () -> Unit = {}) =
        RouterControlCoordinator(object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = throw RouterWifiRequired()
            override fun api(identity: RouterTlsIdentity): RouterPanelApi { onNetwork(); throw RouterWifiRequired() }
        }, store, preferences, RouterHttpProvisioner(localAddress = { "192.168.1.101" }), copyProfile = {})

    @Test fun coldPairingStateAndBareUnpairNeverNeedNetwork() = runTest {
        val store = Store(pairing)
        val preferences = Preferences(RouterControlPreferences(pairing.id, enabled = true))
        var network = 0
        val control = localControl(store, preferences) { network++ }
        control.loadPairingState()
        assertEquals(pairing.id, control.state.value.preferences?.pairingId)
        assertEquals(RouterControlStage.OFFLINE, control.state.value.stage)
        assertEquals(0, network)
        control.unpair(pairing.id)
        assertNull(store.current)
        assertEquals(0, network)
        assertEquals(RouterControlStage.UNPAIRED, control.state.value.stage)
        control.close()
    }

    @Test fun localPublicIpChoicePersistsButDoesNotCreateAProfileOrInventAConnection() = runTest {
        val preferences = Preferences(RouterControlPreferences(pairing.id))
        var network = 0
        val control = localControl(Store(pairing), preferences) { network++ }
        control.loadPairingState()
        assertFalse(preferences.value.publicIpConfirmed)
        control.createRemoteClient("Phone")
        assertEquals(RouterWizardError.UNSUPPORTED, control.state.value.error)
        control.setPublicIpConfirmed(true)
        assertTrue(preferences.value.publicIpConfirmed)
        assertEquals(0, network)
        control.close()
        assertEquals(pairing.id, control.state.value.preferences?.pairingId)
        assertTrue(control.state.value.preferences?.publicIpConfirmed == true)
        assertEquals(RouterControlStage.DISABLED, control.state.value.stage)
    }

    @Test fun cleanupWithoutReachableRouterRetainsThePairingKey() = runTest {
        val store = Store(pairing)
        val control = localControl(store, Preferences(RouterControlPreferences(pairing.id)))
        control.loadPairingState()
        control.unpair(pairing.id, deletePackage = true)
        assertEquals(pairing.id, store.current?.id)
        assertEquals(RouterWizardError.WIFI, control.state.value.error)
        assertFalse(control.state.value.packageRemovalAvailable)
        control.close()
    }

    @Test fun authenticatedDeviceStatusMustBeExplicitAndRulesKeepTheirIdentity() {
        val rule = buildJsonObject {
            put("id", "device-000000000001"); put("name", "Phone"); put("address", "192.168.1.20")
            put("mac", "02:01:02:03:04:05"); put("bindMac", true); put("route", "direct")
        }
        val snapshot = snapshot(JsonArray(listOf(rule)))
        val absent = parseRouterLanDevices(devices(), snapshot).single()
        assertNull(absent.online)
        assertEquals("device-000000000001", absent.id)
        assertEquals("direct", absent.route)
        assertTrue(parseRouterLanDevices(devices(JsonPrimitive(true)), snapshot).single().online == true)
        assertTrue(parseRouterLanDevices(devices(JsonPrimitive(false)), snapshot).single().online == false)
        assertNull(parseRouterLanDevices(devices(JsonNull), snapshot).single().online)
    }

    @Test fun malformedDeviceStatusAndForeignAddressCannotBecomeLiveRows() {
        for (value in listOf(devices(JsonPrimitive("true")), devices(address = "1.1.1.1"), devices(mac = "invalid"))) {
            try { parseRouterLanDevices(value, snapshot()); error("malformed device accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }

    private fun devices(status: JsonPrimitive? = null, address: String = "192.168.1.20", mac: String = "02:01:02:03:04:05") = buildJsonObject {
        put("devices", JsonArray(listOf(buildJsonObject {
            put("name", "Phone"); put("address", address); put("mac", mac)
            if (status != null) put("online", status)
        })))
    }

    private fun snapshot(devices: JsonArray = JsonArray(emptyList())) = RouterControlSnapshot(1,
        buildJsonObject { put("localRules", true); put("localScope", "all") }, JsonArray(emptyList()), devices,
        JsonArray(emptyList()), emptyList(), emptyList(), null, false)
}
