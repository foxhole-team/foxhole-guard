package com.foxhole.core.runtime

import com.foxhole.core.model.ClashApiSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.RoutingPresetSource
import com.foxhole.core.model.RoutingRule
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeConfigAssemblerManagedRoutesTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `foxhole managed routes keep final and bootstrap resolver`() {
        val config = parse(assembler.assemble(baseConfigWithFoxholeManagedRoute(), Settings(), null))
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertRuntimeProxyRoute(rules[0].jsonObject)
        assertSniffRule(rules[1].jsonObject)
        // The stale managed hijack rule from the source config is stripped and, with the default
        // hands-off DNS settings, not re-added.
        assertTrue(
            rules.none { rule -> rule.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns" },
        )
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `preset route ports are emitted as numeric ports and normalized port ranges`() {
        val preset =
            RoutingPreset(
                id = 7,
                name = "ports",
                source = RoutingPresetSource.LOCAL,
                overrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
                enabled = true,
                updatedAt = 1,
                rules =
                listOf(
                    RoutingRule(
                        id = 9,
                        presetId = 7,
                        name = "ports",
                        enabled = true,
                        order = 0,
                        action = RoutingRuleAction.PROXY,
                        matchDomains = emptyList(),
                        matchIpCidrs = emptyList(),
                        matchPorts = listOf("53", "443-445"),
                        matchProtocols = emptyList(),
                        matchNetworks = emptyList(),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), preset))
        val rule =
            config["route"]!!
                .jsonObject["rules"]!!
                .jsonArray
                .map { it.jsonObject }
                .first { it.containsKey("port_range") }

        assertEquals("53", rule["port"]!!.jsonPrimitive.content)
        assertFalse(rule["port"]!!.jsonPrimitive.isString)
        assertEquals("443-445", rule["port_range"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the sniff rule is emitted only when sniffing is enabled`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    sniff = true,
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertFalse(tunInbound.containsKey("sniff"))
        assertFalse(tunInbound.containsKey("sniff_override_destination"))
        assertRuntimeProxyRoute(rules[0].jsonObject)
        assertEquals("sniff", rules[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertTrue(
            rules.none { rule -> rule.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns" },
        )
    }

    @Test
    fun `custom dns servers are preserved`() {
        val config = parse(assembler.assemble(baseConfigWithCustomDns(), Settings(), null))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject

        assertEquals(
            "https://dns.example/dns-query",
            dns["servers"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content
        )
        assertEquals("prefer_ipv4", dns["strategy"]!!.jsonPrimitive.content)
        assertEquals("dns-custom", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects conflicting local ports`() {
        assembler.assemble(
            baseConfigJson = baseConfigWithRules("profile.example"),
            settings =
            Settings(
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        socks = ProxyInboundSettings(enabled = true, port = 10808),
                        clashApi = ClashApiSettings(enabled = true, port = 10808),
                    ),
                ),
            ),
            activePreset = null,
        )
    }
}
