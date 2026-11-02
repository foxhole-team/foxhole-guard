package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterNetworkFactsTest {
    private val id = "pair-" + "a".repeat(32)
    private fun evidence(value: String) = parseRouterPublicIpv4Evidence(Json.parseToJsonElement(value))

    @Test fun absentAndExplicitUnknownRemainUnknownWhileFalseIsNotUnknown() {
        assertNull(parseRouterPublicIpv4Evidence(null))
        assertNull(evidence("""{"schema":1,"source":"default-uplink","publicIpv4Available":null}"""))
        assertEquals(false, evidence("""{"schema":1,"source":"default-uplink","publicIpv4Available":false}"""))
        assertEquals(true, evidence("""{"schema":1,"source":"default-uplink","publicIpv4Available":true}"""))
    }

    @Test fun unknownSourceOrStringBooleanCannotBecomeDirectIpEvidence() {
        for (text in listOf("null", "{}",
            """{"schema":1,"source":"vpn-exit","publicIpv4Available":true}""",
            """{"schema":"1","source":"default-uplink","publicIpv4Available":true}""",
            """{"schema":1,"source":"default-uplink","publicIpv4Available":"true"}""",
            """{"schema":1,"source":"default-uplink","publicIpv4Available":1}""")) {
            try { evidence(text); throw AssertionError("Malformed evidence accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }

    @Test fun pairingEnablesManagementButPublicEvidenceNeverConfirmsStaticIp() {
        val old = RouterControlPreferences(id, enabled = false, countryCode = "DE", countryAuto = false, homeAutodetection = true)
        assertEquals(old, old.withPublicIpv4Evidence(true))
        val pending = old.afterSuccessfulPairing()
        assertTrue(pending.enabled); assertFalse(pending.homeVpnDefaultPending)
        assertFalse(pending.homeVpnManagementEnabled); assertFalse(pending.publicIpConfirmed)
        assertEquals(pending, pending.withPublicIpv4Evidence(null)); assertEquals(pending, pending.withPublicIpv4Evidence(false))
        val ready = pending.withPublicIpv4Evidence(true)
        assertTrue(ready.enabled); assertFalse(ready.homeVpnManagementEnabled); assertFalse(ready.publicIpConfirmed)
        assertFalse(ready.homeVpnDefaultPending); assertEquals("DE", ready.countryCode)
        assertFalse(ready.countryAuto); assertTrue(ready.homeAutodetection)
        val optedOut = ready.copy(homeVpnManagementEnabled = false)
        assertEquals(optedOut, optedOut.withPublicIpv4Evidence(true))
    }

    @Test fun authenticatedExistingClientsShowHubWithoutPublicIpConfirmation() {
        val preferences = RouterControlPreferences(id)
        val snapshot = RouterControlSnapshot(1, JsonObject(emptyMap()), JsonArray(emptyList()), JsonArray(emptyList()),
            JsonArray(emptyList()), emptyList(), listOf(JsonObject(mapOf("id" to JsonPrimitive("client-000000000001")))), null, false)
        assertFalse(RouterControlState(preferences = preferences).remoteClientHubAvailable)
        assertTrue(RouterControlState(preferences = preferences, snapshot = snapshot).remoteClientHubAvailable)
        assertFalse(preferences.publicIpConfirmed); assertFalse(preferences.homeVpnManagementEnabled)
    }
}
