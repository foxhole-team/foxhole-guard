package com.foxhole.guard.router

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterProviderResolutionTest {
    private val paired = "pair-" + "a".repeat(32)
    private fun response() = buildJsonObject {
        put("schema", 1); put("pairingId", paired); put("host", "vpn.example.com")
        put("addresses", JsonArray(listOf(JsonPrimitive("1.1.1.2")))); put("ttlMs", 30000); put("checkedUptimeMs", 9000)
    }
    private fun parsed(value: JsonObject = response()) = parseRouterProviderResolution(value, paired, "vpn.example.com", 10000, 20000, 500)

    @Test fun exactAuthenticatedPublicAddressesRetainOnlyTheRemainingTtl() {
        val result = parsed()
        assertEquals(listOf("1.1.1.2"), result.addresses.map { it.hostAddress })
        assertEquals(49500L, result.expiresAtElapsedMs)
        assertFalse(result.toString().contains("vpn.example"))
        assertEquals("vpn.example.com", routerProviderHostname("VPN.Example.COM"))
    }

    @Test fun mismatchedHostPairClockAndInflatedOrStringTtlAreRefused() {
        for ((key, bad) in listOf("host" to JsonPrimitive("other.example.com"), "pairingId" to JsonPrimitive("pair-" + "b".repeat(32)),
            "checkedUptimeMs" to JsonPrimitive(11001), "checkedUptimeMs" to JsonPrimitive(-1),
            "checkedUptimeMs" to JsonPrimitive("9000"), "ttlMs" to JsonPrimitive(60000), "ttlMs" to JsonPrimitive("30000"))) {
            assertThrows(RouterApiFailure::class.java) { parsed(JsonObject(response() + (key to bad))) }
        }
        assertThrows(RouterApiFailure::class.java) { parseRouterProviderResolution(response(), paired, "vpn.example.com", 70001, 20000, 0) }
        assertThrows(RouterApiFailure::class.java) { parseRouterProviderResolution(response(), paired, "vpn.example.com", 10000, 20000, 5001) }
    }

    @Test fun privateMixedIpv6DuplicateAndExcessAnswersCannotBecomeAWanLease() {
        for (addresses in listOf(listOf("127.0.0.1"), listOf("1.1.1.2", "192.168.1.1"), listOf("2606:4700:4700::1111"),
            listOf("1.1.1.2", "1.1.1.2"), (1..9).map { "1.1.1.$it" })) {
            val value = JsonObject(response() + ("addresses" to JsonArray(addresses.map(::JsonPrimitive))))
            assertThrows(RouterApiFailure::class.java) { parsed(value) }
        }
    }

    @Test fun localNamesAndIpLiteralsAreNotProviderDnsQuestions() {
        for (host in listOf("router.local", "router.lan", "localhost", "127.0.0.1", "1.1.1.1", "vpn.example.com/path")) {
            assertThrows(IllegalArgumentException::class.java) { routerProviderHostname(host) }
        }
    }

    @Test fun zeroTtlIsAOneUseAnswerAndCannotExtendTheValidationMemo() {
        val value = JsonObject(response() + ("ttlMs" to JsonPrimitive(0)))
        assertEquals(20000L, parsed(value).expiresAtElapsedMs)
    }

    @Test fun provisionerCancellationPropagatesBeforeParsingOrDeliveringAddresses() = runTest {
        val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
        val client = RouterPanelApi(identity, okhttp3.Call.Factory { error("fake mobile seam only") })
        val session = RouterPanelSession("p1." + "b".repeat(64), false)
        val received = CompletableDeferred<JsonObject>()
        var deliveries = 0
        val provisioner = RouterHttpProvisioner(mobileCall = { _, action, payload ->
            assertEquals(RouterMobileAction.PHONE_RESOLVE, action)
            assertEquals(JsonPrimitive("192.168.1.50"), payload["address"])
            assertEquals(JsonPrimitive("vpn.example.com"), payload["host"])
            received.await()
        }, localAddress = { "192.168.1.50" }, elapsedMs = { 20000 })
        val lookup = launch { provisioner.resolveProvider(client, session, paired, "VPN.Example.COM", { 10000 }); deliveries++ }
        runCurrent()
        lookup.cancelAndJoin()
        received.complete(response()); runCurrent()
        assertTrue(lookup.isCancelled)
        assertEquals(0, deliveries)
        session.close(); client.close()
    }
}
