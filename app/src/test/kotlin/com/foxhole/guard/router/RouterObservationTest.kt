package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterObservationTest {
    private val id = "client-000000000001"
    private val snapshot = RouterControlSnapshot(7, JsonObject(emptyMap()), JsonArray(emptyList()), JsonArray(emptyList()),
        JsonArray(emptyList()), emptyList(), listOf(JsonObject(mapOf("id" to JsonPrimitive(id), "provisioned" to JsonPrimitive(true)))), null, false)
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test fun countryComesOnlyFromTypedRouterWanAndNeverFromVpnOrPhoneFallback() {
        assertEquals("NL", parseRouterWanCountry(json("""{"sample":{"wan":{"country":"NL"},"vpn":{"country":"DE"}}}""")))
        for (raw in listOf("""{"sample":{"vpn":{"country":"DE"}}}""", """{"sample":{"wan":{"country":null}}}""",
            """{"sample":{"wan":{"country":"ZZ"}}}""", """{"sample":{"wan":{"country":true}}}""")) {
            assertNull(parseRouterWanCountry(json(raw)))
        }
    }

    @Test fun configuredAndProvisionedClientRemainsUnknownUntilActualStatusObservation() {
        assertEquals(listOf(RouterRemoteClientStatus(id, null)), unknownRouterClientStatuses(snapshot))
        for ((raw, expected) in listOf("true" to true, "false" to false, "null" to null)) {
            assertEquals(listOf(RouterRemoteClientStatus(id, expected)), parseRouterClientStatuses(
                json("""{"schema":1,"revision":7,"clients":[{"id":"$id","online":$raw}]}"""), snapshot))
        }
    }

    @Test fun staleForeignDuplicateAndMalformedClientObservationsCannotBeAccepted() {
        for (raw in listOf(
            """{"schema":1,"revision":6,"clients":[{"id":"$id","online":true}]}""",
            """{"schema":1,"revision":7,"clients":[]}""",
            """{"schema":1,"revision":7,"clients":[{"id":"client-000000000002","online":true}]}""",
            """{"schema":1,"revision":7,"clients":[{"id":"$id","online":"true"}]}""",
            """{"schema":1,"revision":7,"clients":[{"id":"$id","online":false},{"id":"$id","online":true}]}""",
        )) {
            try { parseRouterClientStatuses(json(raw), snapshot); error("unsafe client status accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }
}
