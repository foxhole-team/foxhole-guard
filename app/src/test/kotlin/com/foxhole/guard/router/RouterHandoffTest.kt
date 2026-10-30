package com.foxhole.guard.router

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterHandoffTest {
    private fun response() = buildJsonObject {
        put("schema", 1); put("claimId", "a".repeat(32)); put("mode", "phone_vpn"); put("phase", "active")
        put("remainingMs", 60000); put("observedUptimeMs", 10000); put("policyRevision", 3)
        put("serverId", JsonNull); put("generation", JsonNull)
    }

    @Test fun leaseDeadlineAccountsForTheWholeRequestLatency() {
        assertEquals(69000L, parseRouterHandoff(response(), "a".repeat(32), "phone_vpn", 10000, 1000).expiresAtElapsedMs)
    }

    @Test fun wrongClaimModeOrUnboundedLeaseCannotAuthorizeAPhoneStart() {
        for (value in listOf(JsonObject(response() + ("claimId" to JsonPrimitive("b".repeat(32)))),
            JsonObject(response() + ("remainingMs" to JsonPrimitive(60001))),
            JsonObject(response() + ("phase" to JsonPrimitive("released"))))) {
            assertThrows(RouterApiFailure::class.java) { parseRouterHandoff(value, "a".repeat(32), "phone_vpn", 10000, 1000) }
        }
        assertThrows(RouterApiFailure::class.java) { parseRouterHandoff(response(), "a".repeat(32), "phone_vpn", 10000, 5001) }
    }

    @Test fun routerReleaseMustAcknowledgeTheExactVerifiedServerGeneration() {
        val proof = RouterHomeProof("pair", "server-000000000001", "b".repeat(16), 10000, true, true)
        val released = JsonObject(response() + mapOf("mode" to JsonPrimitive("router_vpn"), "phase" to JsonPrimitive("released"),
            "remainingMs" to JsonPrimitive(0), "serverId" to JsonPrimitive(proof.serverId), "generation" to JsonPrimitive(proof.generation)))
        assertEquals("released", parseRouterHandoff(released, "a".repeat(32), "router_vpn", 10000, 0, proof).phase)
        assertThrows(RouterApiFailure::class.java) {
            parseRouterHandoff(JsonObject(released + ("generation" to JsonPrimitive("c".repeat(16)))), "a".repeat(32), "router_vpn", 10000, 0, proof)
        }
    }

}
