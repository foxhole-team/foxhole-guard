package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class FoxCoreNumericRangePolicyTest : FoxCoreConfigTranslatorTestSupport() {
    private fun stringArray(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private fun vless(): JsonObject = primary("vless") {
        put("server", "203.0.113.10")
        put("server_port", 443)
        put("uuid", "d0cf0001-0000-4000-8000-000000000000")
        put("tls", tls())
    }

    private fun cover(): List<String> = requireNotNull(javaClass.getResourceAsStream("/ipv6-full-range-cidrs.txt"))
        .bufferedReader().use { it.readLines() }
    private fun rule(cidrs: List<String>): JsonObject = buildJsonObject {
        put("package_name", "com.example.browser")
        put("domain", stringArray("exact.example"))
        put("domain_suffix", stringArray("suffix.example"))
        put("ip_cidr", JsonArray(cidrs.map(::JsonPrimitive)))
        put("port", 443); put("network", "tcp")
        put("action", "route"); put("outbound", "direct")
    }
    @Test fun fullIpv6RangeSplitsIntoFourBoundedRoutesWithoutChangingSelectorsOrPriority() {
        val cidrs = cover()
        assertEquals(254, cidrs.size)
        val following = buildJsonObject {
            put("domain", stringArray("later.example")); put("action", "reject")
        }
        val output = policy(translate(ProtocolHint.VLESS, vless(), routeRules = managedRouteRules() + rule(cidrs) + following))
            .getValue("routes").jsonArray.map { it.jsonObject }
        assertEquals(listOf(64, 64, 64, 62), output.take(4).map { it.getValue("cidrs").jsonArray.size })
        assertEquals(cidrs, output.take(4).flatMap { it.getValue("cidrs").jsonArray.map { c -> c.jsonPrimitive.content } })
        val selectors = output.first().filterKeys { it != "cidrs" }
        assertEquals("com.example.browser", selectors.getValue("package").jsonPrimitive.content)
        assertEquals("tcp", selectors.getValue("transport").jsonPrimitive.content)
        assertEquals("direct", selectors.getValue("action").jsonObject.getValue("type").jsonPrimitive.content)
        output.take(4).forEach { assertEquals(selectors, it.filterKeys { key -> key != "cidrs" }) }
        assertEquals("later.example", output.last().getValue("exact_domains").jsonArray.single().jsonPrimitive.content)
    }
    @Test fun sixtyFourCidrsKeepOneRuleAndAllSelectors() {
        val output = policy(translate(ProtocolHint.VLESS, vless(), routeRules = managedRouteRules() + rule(cover().take(64))))
            .getValue("routes").jsonArray
        assertEquals(1, output.size)
        assertEquals(64, output.single().jsonObject.getValue("cidrs").jsonArray.size)
    }
    @Test fun expandedNativeRouteCountCannotExceed4096() {
        val small = rule(listOf("192.0.2.1/32"))
        val error = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translate(ProtocolHint.VLESS, vless(), routeRules = managedRouteRules() + List(4093) { small } + rule(cover()))
        }
        assertEquals(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, error.rejection)
    }
}
