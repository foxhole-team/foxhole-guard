package com.foxhole.guard.core.data

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxCoreConfigRejection
import com.foxhole.core.runtime.FoxCoreConfigTranslationException
import com.foxhole.core.runtime.FoxCoreConfigTranslator
import com.foxhole.guard.R
import com.foxhole.guard.userFacingErrorMessageRes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

internal class DnsRequiredRuntimePolicyTest {
    @Test fun packetTunnelRetainsBootstrapMetadataButDropsRequiredCategoryPolicyAndAppRefusesIt() {
        val bootstrap = bootstrap()
        val config = translate(
            hint = ProtocolHint.WIREGUARD,
            primary = wireGuard(),
            dnsServers = managedDnsServers() + wireGuardDnsServer("10.17.0.1"),
            dnsRules = listOf(categoryRule("ads")),
            dnsRuleSetBootstrap = bootstrap,
        )
        assertSame(bootstrap, config.dnsRuleSetBootstrap)
        assertEquals(setOf("mode"), engine(config).getValue("dns").jsonObject.keys)
        assertFalse(engine(config).getValue("dns").jsonObject.containsKey("rule_sets"))
        val failure = assertThrows(DnsFilterPolicyUnavailableException::class.java) {
            requireDnsFilterRuntimePolicy(selectedSettings(), session(ProtocolHint.WIREGUARD), config)
        }
        assertEquals(ProtocolHint.WIREGUARD, failure.protocol)
        assertEquals(
            R.string.dns_category_profile_policy_unavailable,
            userFacingErrorMessageRes(failure, R.string.error_runtime_start_failed)
        )
    }

    @Test fun supportedProxyRetainsExactlySelectedRequiredCategoryAndAppAcceptsIt() {
        val bootstrap = bootstrap()
        val config = translate(
            hint = ProtocolHint.SHADOWSOCKS,
            primary = primary(
                "shadowsocks"
            ) {
                put(
                    "server",
                    "203.0.113.7"
                )
                put("server_port", 8388)
                put("method", "aes-128-gcm")
                put("password", "owned-fixture")
            },
            dnsRules = listOf(categoryRule("ads")),
            dnsRuleSetBootstrap = bootstrap,
        )
        val rule = engine(config).getValue("dns").jsonObject.getValue("rule_sets").jsonArray.single().jsonObject
        assertEquals(listOf("ads"), rule.getValue("enabled_categories").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, rule.getValue("required").jsonPrimitive.content.toBoolean())
        assertSame(config, requireDnsFilterRuntimePolicy(selectedSettings(), session(ProtocolHint.SHADOWSOCKS), config))
        assertThrows(DnsFilterPolicyUnavailableException::class.java) {
            requireDnsFilterRuntimePolicy(
                selectedSettings().copy(dns = selectedSettings().dns.copy(blockTrackers = true)),
                session(ProtocolHint.SHADOWSOCKS),
                config
            )
        }
    }

    @Test fun packetTunnelWithoutItsOwnResolverRemainsExistingTypedRefusal() {
        val error = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translate(
                hint = ProtocolHint.WIREGUARD,
                primary = wireGuard(),
                dnsRules = listOf(categoryRule("ads")),
                dnsRuleSetBootstrap = bootstrap()
            )
        }
        assertEquals(FoxCoreConfigRejection.PACKET_TUNNEL_DNS_MISSING, error.rejection)
    }

    @Test fun disablingAllCategoriesDoesNotInventFilteringOrBlockPacketTunnel() {
        val config = translate(
            hint = ProtocolHint.WIREGUARD,
            primary = wireGuard(),
            dnsServers = managedDnsServers() + wireGuardDnsServer("10.17.0.1")
        )
        assertSame(
            config,
            requireDnsFilterRuntimePolicy(
                selectedSettings().copy(dns = DnsSettings()),
                session(ProtocolHint.WIREGUARD),
                config
            )
        )
    }

    private fun bootstrap() = FoxCoreDnsRuleSetBootstrap(
        "foxhole-dns-normal-medium",
        "owned",
        "00".repeat(32),
        "owned",
        categoryVariant = "normal-medium",
        availableCategories = DnsFilterCategory.entries.toSet(),
        categoryMinimumSequence = 0uL,
    )

    private fun selectedSettings() = Settings(
        dns = DnsSettings(
            filteringEnabled = true,
            blockAds = true,
            blockTrackers = false,
            blockAppTelemetry = false,
            blockMaliciousDomains = false
        )
    )
    private fun session(protocol: ProtocolHint) = VpnSession(
        profileId = 1,
        profileName = "owned",
        protocolHint = protocol,
        configJson = "{}",
        correlationId = "owned"
    )
    private fun categoryRule(category: String) = buildJsonObject {
        put("rule_set", JsonArray(listOf(JsonPrimitive("foxhole-dns-$category"))))
        put("action", "predefined")
        put("rcode", "NXDOMAIN")
    }

    private fun wireGuard() = primary("wireguard") {
        put("private_key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        put("address", JsonArray(listOf(JsonPrimitive("10.17.0.2/32"))))
        put("mtu", 1420)
        put(
            "peers",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("address", "198.51.100.20")
                        put("port", 51820)
                        put("public_key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                        put("allowed_ips", JsonArray(listOf(JsonPrimitive("0.0.0.0/0"))))
                    }
                )
            }
        )
    }
    private fun translate(
        hint: ProtocolHint,
        primary: JsonObject,
        dnsServers: List<JsonObject> = managedDnsServers(),
        dnsRules: List<JsonObject> = emptyList(),
        dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
    ): FoxCoreSessionConfig {
        val packetTunnel = primary.getValue("type").jsonPrimitive.content == "wireguard"
        val root = buildJsonObject {
            put(
                "dns",
                buildJsonObject {
                    put("servers", JsonArray(dnsServers))
                    if (dnsRules.isNotEmpty()) put("rules", JsonArray(dnsRules))
                    put("strategy", "prefer_ipv4")
                    put("final", "dns-remote")
                }
            )
            put("inbounds", buildJsonArray { add(managedTun()) })
            if (packetTunnel) put("endpoints", buildJsonArray { add(primary) })
            put(
                "outbounds",
                buildJsonArray {
                    if (!packetTunnel) add(primary)
                    add(
                        buildJsonObject {
                            put("type", "selector")
                            put("tag", "proxy")
                            put("default", "node")
                            put("outbounds", buildJsonArray { add(JsonPrimitive("node")) })
                        }
                    )
                    add(taggedOutbound("direct", "direct"))
                    add(taggedOutbound("block", "block"))
                }
            )
            put(
                "route",
                buildJsonObject {
                    put("final", "proxy")
                    put("default_domain_resolver", "dns-direct")
                    put("auto_detect_interface", true)
                    put(
                        "rules",
                        buildJsonArray {
                            add(buildJsonObject { put("action", "sniff") })
                            add(
                                buildJsonObject {
                                    put("port", 53)
                                    put("action", "hijack-dns")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("protocol", "dns")
                                    put("action", "hijack-dns")
                                }
                            )
                        }
                    )
                }
            )
        }
        return FoxCoreConfigTranslator().translate(
            session(hint).copy(configJson = root.toString()),
            dnsRuleSetBootstrap = dnsRuleSetBootstrap
        )
    }

    private fun engine(config: FoxCoreSessionConfig): JsonObject = Json.parseToJsonElement(
        config.engineConfigJson
    ).jsonObject
    private fun primary(
        type: String,
        fields: JsonObjectBuilder.() -> Unit
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("tag", "node")
        fields()
    }
    private fun taggedOutbound(
        type: String,
        tag: String
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("tag", tag)
    }
    private fun managedDnsServers(): List<JsonObject> = listOf(
        taggedOutbound("local", "dns-local"),
        taggedOutbound("local", "dns-direct"),
        buildJsonObject {
            put(
                "tag",
                "dns-remote"
            )
            put(
                "type",
                "https"
            )
            put("server", "1.1.1.1")
            put("server_port", 443)
            put("path", "/dns-query")
            put("detour", "proxy")
        },
    )
    private fun wireGuardDnsServer(address: String): JsonObject = buildJsonObject {
        put(
            "tag",
            "dns-wireguard"
        )
        put("type", "udp")
        put("server", address)
        put("server_port", 53)
        put("detour", "proxy")
    }
    private fun managedTun(): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        put("interface_name", "foxhole")
        put("mtu", 1500)
        put("auto_route", true)
        put("strict_route", true)
        put("stack", "system")
        put(
            "address",
            buildJsonArray {
                add(JsonPrimitive("172.19.0.1/30"))
                add(JsonPrimitive("fdfe:dcba:9876::1/126"))
            }
        )
    }
}
