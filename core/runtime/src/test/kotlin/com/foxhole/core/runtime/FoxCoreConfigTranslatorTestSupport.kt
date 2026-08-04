package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal open class FoxCoreConfigTranslatorTestSupport {
    protected val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = false
        }
    protected val translator = FoxCoreConfigTranslator(json)

    protected fun translate(
        hint: ProtocolHint,
        primary: JsonObject,
        expectedPolicyRevision: Long? = null,
        torActive: Boolean = false,
        extraOutbounds: List<JsonObject> = emptyList(),
        routeRules: List<JsonObject> = managedRouteRules(),
        routeFinal: String = "proxy",
        dnsServers: List<JsonObject> = managedDnsServers(),
        dnsRules: List<JsonObject> = emptyList(),
        dnsFinal: String = DNS_REMOTE_TAG,
        dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
        inbounds: List<JsonObject> = listOf(managedTun()),
        rootExtension: JsonObjectBuilder.() -> Unit = {},
    ): FoxCoreTranslatedConfig =
        translator.translate(
            session =
            VpnSession(
                profileId = 7L,
                profileName = "contract fixture",
                protocolHint = hint,
                configJson =
                legacyConfig(
                    primary = primary,
                    extraOutbounds = extraOutbounds,
                    routeRules = routeRules,
                    routeFinal = routeFinal,
                    dnsServers = dnsServers,
                    dnsRules = dnsRules,
                    dnsFinal = dnsFinal,
                    inbounds = inbounds,
                    rootExtension = rootExtension,
                ).toString(),
                correlationId = "contract-correlation",
                torActive = torActive,
            ),
            expectedPolicyRevision = expectedPolicyRevision,
            dnsRuleSetBootstrap = dnsRuleSetBootstrap,
        )

    protected fun translateConfig(
        hint: ProtocolHint,
        config: JsonObject,
        torActive: Boolean = false,
    ): FoxCoreTranslatedConfig =
        translator.translate(
            VpnSession(
                profileId = 8L,
                profileName = "negative contract fixture",
                protocolHint = hint,
                configJson = config.toString(),
                correlationId = "negative-contract-correlation",
                torActive = torActive,
            ),
        )

    protected fun engine(result: FoxCoreTranslatedConfig): JsonObject =
        json.parseToJsonElement(result.engineConfigJson).jsonObject

    protected fun policy(result: FoxCoreTranslatedConfig): JsonObject =
        json.parseToJsonElement(result.policyConfigJson).jsonObject

    protected fun legacyConfig(
        primary: JsonObject,
        extraOutbounds: List<JsonObject> = emptyList(),
        routeRules: List<JsonObject> = managedRouteRules(),
        routeFinal: String = "proxy",
        dnsServers: List<JsonObject> = managedDnsServers(),
        dnsRules: List<JsonObject> = emptyList(),
        dnsFinal: String = DNS_REMOTE_TAG,
        inbounds: List<JsonObject> = listOf(managedTun()),
        rootExtension: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject {
        val primaryType = primary.getValue("type").jsonPrimitive.content
        val primaryTag = primary.getValue("tag").jsonPrimitive.content
        return buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("level", "warn")
                    put("timestamp", true)
                },
            )
            put(
                "dns",
                buildJsonObject {
                    put("servers", JsonArray(dnsServers))
                    if (dnsRules.isNotEmpty()) {
                        put("rules", JsonArray(dnsRules))
                    }
                    put("strategy", "prefer_ipv4")
                    put("final", dnsFinal)
                },
            )
            put("inbounds", JsonArray(inbounds))
            if (primaryType == "wireguard") {
                put("endpoints", buildJsonArray { add(primary) })
            }
            put(
                "outbounds",
                buildJsonArray {
                    if (primaryType != "wireguard") {
                        add(primary)
                    }
                    extraOutbounds.forEach(::add)
                    add(
                        buildJsonObject {
                            put("type", "selector")
                            put("tag", "proxy")
                            put("default", primaryTag)
                            put("outbounds", buildJsonArray { add(JsonPrimitive(primaryTag)) })
                        },
                    )
                    add(taggedOutbound("direct", "direct"))
                    add(taggedOutbound("block", "block"))
                },
            )
            put(
                "route",
                buildJsonObject {
                    put("rules", JsonArray(routeRules))
                    put("final", routeFinal)
                    put("default_domain_resolver", "dns-direct")
                    put("auto_detect_interface", true)
                },
            )
            rootExtension()
        }
    }

    protected fun primary(
        type: String,
        content: JsonObjectBuilder.() -> Unit,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            put("tag", "node")
            content()
        }

    protected fun tls(
        serverName: String = "edge.example",
        content: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            put("enabled", true)
            put("server_name", serverName)
            content()
        }

    protected fun managedTun(): JsonObject =
        buildJsonObject {
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
                },
            )
        }

    protected fun managedRuntimeInbound(
        port: Int = 18_809,
        password: String = "ephemeral-test-password",
    ): JsonObject =
        buildJsonObject {
            put("type", "http")
            put("tag", RUNTIME_LOOPBACK_PROXY_INBOUND_TAG)
            put("listen", "127.0.0.1")
            put("listen_port", port)
            put(
                "users",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("username", "foxhole-runtime")
                            put("password", password)
                        },
                    )
                },
            )
        }

    protected fun managedDnsServers(): List<JsonObject> =
        listOf(
            taggedOutbound("local", "dns-local"),
            taggedOutbound("local", "dns-direct"),
            buildJsonObject {
                put("tag", "dns-remote")
                put("type", "https")
                put("server", "1.1.1.1")
                put("server_port", 443)
                put("path", "/dns-query")
                put("detour", "proxy")
            },
        )

    /**
     * The DNS server a WireGuard `DNS =` line becomes on import.
     *
     * A packet-tunnel profile is refused without it: nothing intercepts DNS on that shape, so this
     * entry is the only resolver there is to hand to Android.
     */
    protected fun wireGuardDnsServer(address: String = "10.17.0.1"): JsonObject =
        buildJsonObject {
            put("tag", WIREGUARD_DNS_TAG)
            put("type", "udp")
            put("server", address)
            put("server_port", 53)
            put("detour", "proxy")
        }

    protected fun managedRouteRules(): List<JsonObject> =
        listOf(
            buildJsonObject { put("action", "sniff") },
            buildJsonObject {
                put("port", 53)
                put("action", "hijack-dns")
            },
            buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            },
        )

    protected fun taggedOutbound(
        type: String,
        tag: String,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            put("tag", tag)
        }

    protected fun JsonObject.withRootValue(
        key: String,
        value: JsonElement,
    ): JsonObject =
        buildJsonObject {
            this@withRootValue.forEach { (sourceKey, sourceValue) ->
                put(sourceKey, sourceValue)
            }
            put(key, value)
        }
}
