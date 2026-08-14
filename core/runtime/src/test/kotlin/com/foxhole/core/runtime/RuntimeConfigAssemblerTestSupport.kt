package com.foxhole.core.runtime

import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.RoutingPresetSource
import com.foxhole.core.model.RoutingRule
import com.foxhole.core.model.RoutingRuleAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

// Shared fixture of the RuntimeConfigAssembler suites: the assembler instance, fakes and
// the config builders/readers every slice uses. Split from RuntimeConfigAssemblerTest.kt.
internal open class RuntimeConfigAssemblerTestSupport {
    protected val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    protected val assembler = RuntimeConfigAssembler(json)

    protected class FakeLanProxyAddressProvider(
        var address: String? = null,
    ) : LanProxyAddressProvider {
        override fun currentWifiIpv4Address(): String? = address
    }

    protected fun preset(mode: RoutingPresetOverrideMode): RoutingPreset =
        RoutingPreset(
            id = 1,
            name = "local",
            source = RoutingPresetSource.LOCAL,
            overrideMode = mode,
            enabled = true,
            updatedAt = 1,
            rules =
            listOf(
                RoutingRule(
                    id = 1,
                    presetId = 1,
                    name = "local",
                    enabled = true,
                    order = 0,
                    action = RoutingRuleAction.PROXY,
                    matchDomains = listOf("local.example"),
                    matchIpCidrs = emptyList(),
                    matchPorts = emptyList(),
                    matchProtocols = emptyList(),
                    matchNetworks = emptyList(),
                ),
            ),
        )

    protected fun baseConfigWithRules(domain: String): String =
        buildJsonObject {
            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "tun")
                            put("tag", "tun-in")
                            put("interface_name", "foxhole")
                            put("mtu", 1500)
                            put("auto_route", true)
                            put("strict_route", true)
                            put("sniff", false)
                            put("stack", "system")
                            put(
                                "address",
                                buildJsonArray {
                                    add(JsonPrimitive("172.19.0.1/30"))
                                    add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                                }
                            )
                        },
                    )
                }
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "selector")
                            put("tag", "proxy")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "direct")
                            put("tag", "direct")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "block")
                            put("tag", "block")
                        }
                    )
                }
            )
            put("dns", buildJsonObject { put("strategy", "prefer_ipv4") })
            put(
                "route",
                buildJsonObject {
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("domain", buildJsonArray { add(JsonPrimitive(domain)) })
                                    put("action", "route")
                                    put("outbound", "direct")
                                },
                            )
                        }
                    )
                    put("final", "proxy")
                }
            )
        }.toString()

    protected fun baseConfigWithFinalOutbound(finalOutbound: String): String {
        val base = parse(baseConfigWithRules("profile.example"))
        val route = base["route"]!!.jsonObject
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key == "route") {
                    put(
                        key,
                        buildJsonObject {
                            route.forEach { (routeKey, routeValue) ->
                                if (routeKey == "final") {
                                    put("final", finalOutbound)
                                } else {
                                    put(routeKey, routeValue)
                                }
                            }
                        },
                    )
                } else {
                    put(key, value)
                }
            }
        }.toString()
    }

    protected fun baseConfigWithPlatformHttpProxy(): String {
        val base = parse(baseConfigWithRules("profile.example"))
        val sourceTun = base["inbounds"]!!.jsonArray.first().jsonObject
        val patchedTun =
            buildJsonObject {
                sourceTun.forEach { (key, value) -> put(key, value) }
                put(
                    "platform",
                    buildJsonObject {
                        put(
                            "http_proxy",
                            buildJsonObject {
                                put("enabled", true)
                                put("server", "127.0.0.1")
                                put("server_port", 10809)
                            },
                        )
                    },
                )
            }
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key == "inbounds") {
                    put("inbounds", buildJsonArray { add(patchedTun) })
                } else {
                    put(key, value)
                }
            }
        }.toString()
    }

    protected fun baseConfigWithWireGuardDns(selectedDefault: String): String {
        val base = parse(baseConfigWithRules("profile.example"))
        return buildJsonObject {
            put("inbounds", base["inbounds"]!!)
            put(
                "endpoints",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "wireguard")
                            put("tag", "wireguard-direct")
                            put("mtu", 1280)
                        },
                    )
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "vless")
                            put("tag", "vless-direct")
                            put("server", "edge.example")
                            put("server_port", 443)
                            put("uuid", "11111111-1111-1111-1111-111111111111")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "selector")
                            put("tag", "proxy")
                            put("default", selectedDefault)
                            put(
                                "outbounds",
                                buildJsonArray {
                                    add(JsonPrimitive("vless-direct"))
                                    add(JsonPrimitive("wireguard-direct"))
                                },
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "direct")
                            put("tag", "direct")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "block")
                            put("tag", "block")
                        }
                    )
                },
            )
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-local")
                                    put("type", "local")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-direct")
                                    put("type", "local")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-remote")
                                    put("type", "https")
                                    put("server", "1.1.1.1")
                                    put("server_port", 443)
                                    put("path", "/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-wireguard")
                                    put("type", "udp")
                                    put("server", "1.1.1.1")
                                    put("server_port", 53)
                                    put("detour", "proxy")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                    put("final", "dns-remote")
                },
            )
            put("route", base["route"]!!)
        }.toString()
    }

    protected fun baseConfigWithOutbounds(outbounds: JsonArray): String =
        buildJsonObject {
            val base = parse(baseConfigWithRules("profile.example"))
            put("inbounds", base["inbounds"]!!)
            put("outbounds", outbounds)
            put("dns", base["dns"]!!)
            put("route", base["route"]!!)
        }.toString()

    protected fun baseConfigWithLegacyFoxholeDns(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-remote")
                                    put("address", "https://1.1.1.1/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-local")
                                    put("address", "local")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                },
            )
            put(
                "route",
                buildJsonObject {
                    put("final", "proxy")
                    put("default_domain_resolver", "dns-remote")
                }
            )
        }.toString()

    protected fun baseConfigWithLegacyUdpBootstrapDns(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-local")
                                    put("type", "local")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-direct")
                                    put("type", "udp")
                                    put("server", "1.1.1.1")
                                    put("server_port", 53)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-remote")
                                    put("type", "https")
                                    put("server", "1.1.1.1")
                                    put("server_port", 443)
                                    put("path", "/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                    put("final", "dns-remote")
                },
            )
            put(
                "route",
                buildJsonObject {
                    put("final", "proxy")
                    put("default_domain_resolver", "dns-direct")
                }
            )
        }.toString()

    protected fun baseConfigWithCustomDns(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-custom")
                                    put("address", "https://dns.example/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                },
            )
            put("route", parse(baseConfigWithRules("profile.example"))["route"]!!)
        }.toString()

    protected fun baseConfigWithFoxholeManagedRoute(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put("dns", parse(baseConfigWithRules("profile.example"))["dns"]!!)
            put(
                "route",
                buildJsonObject {
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("protocol", "dns")
                                    put("port", 53)
                                    put("action", "hijack-dns")
                                },
                            )
                        },
                    )
                    put("final", "proxy")
                    put("default_domain_resolver", "dns-direct")
                    put("auto_detect_interface", true)
                },
            )
        }.toString()

    protected fun parse(raw: String) = json.parseToJsonElement(raw).jsonObject

    protected fun assertPortDnsHijack(rule: JsonObject) {
        assertEquals("hijack-dns", rule["action"]!!.jsonPrimitive.content)
        assertEquals("53", rule["port"]!!.jsonPrimitive.content)
        assertFalse(rule["port"]!!.jsonPrimitive.isString)
        assertFalse(rule.containsKey("protocol"))
    }

    protected fun assertSniffRule(rule: JsonObject) {
        assertEquals("sniff", rule["action"]!!.jsonPrimitive.content)
    }

    protected fun assertRuntimeProxyRoute(
        rule: JsonObject,
        outbound: String = "proxy",
        inbound: String = "foxhole-runtime-proxy-in",
    ) {
        assertEquals("route", rule["action"]!!.jsonPrimitive.content)
        assertEquals(listOf(inbound), rule["inbound"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("tcp", rule["network"]!!.jsonPrimitive.content)
        assertEquals(outbound, rule["outbound"]!!.jsonPrimitive.content)
    }

    protected fun assertProtocolDnsHijack(rule: JsonObject) {
        assertEquals("hijack-dns", rule["action"]!!.jsonPrimitive.content)
        assertEquals("dns", rule["protocol"]!!.jsonPrimitive.content)
        assertFalse(rule.containsKey("port"))
    }

    protected fun assertLocalGuardExcludesFoxHole(tunInbound: JsonObject) {
        assertEquals(
            listOf(BuildConfig.APPLICATION_ID),
            tunInbound.stringArray("exclude_package"),
        )
    }

    protected fun JsonObject.stringArray(key: String): List<String> =
        this[key]
            ?.jsonArray
            ?.map { value -> value.jsonPrimitive.content }
            .orEmpty()
}
