package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun JsonObject.withTcpReliabilityOutbounds(): JsonObject {
    val patchedOutbounds = patchTcpReliabilityOutbounds(this["outbounds"]?.jsonArray) ?: return this
    return buildJsonObject {
        this@withTcpReliabilityOutbounds.forEach { (key, value) ->
            if (key == "outbounds") {
                put(key, patchedOutbounds)
            } else {
                put(key, value)
            }
        }
    }
}

internal fun JsonObject.withDirectOutboundIfNeeded(splitPlan: RuntimeSplitPlan): JsonObject {
    if (splitPlan.vpnMode == VpnAppSelectionMode.FULL_DEVICE) {
        return this
    }
    val outbounds = this["outbounds"]?.jsonArray.orEmpty()
    val hasDirect =
        outbounds.any { outbound ->
            outbound.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == "direct"
        }
    if (hasDirect) {
        return this
    }
    val patchedOutbounds =
        buildJsonArray {
            outbounds.forEach(::add)
            add(
                buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                },
            )
        }
    return buildJsonObject {
        this@withDirectOutboundIfNeeded.forEach { (key, value) ->
            if (key == "outbounds") {
                put(key, patchedOutbounds)
            } else {
                put(key, value)
            }
        }
        if (this@withDirectOutboundIfNeeded["outbounds"] == null) {
            put("outbounds", patchedOutbounds)
        }
    }
}

internal fun JsonObject.withTorPrivacyRouteOutbound(
    paths: TorRuntimePaths?,
    detourThroughVpn: Boolean,
): JsonObject {
    paths ?: return this
    val patchedOutbounds =
        buildJsonArray {
            this@withTorPrivacyRouteOutbound["outbounds"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .filterNot { outbound -> outbound["tag"]?.jsonPrimitive?.contentOrNull == TOR_OVER_VPN_OUTBOUND_TAG }
                .forEach(::add)
            add(torOverVpnOutbound(paths, detourThroughVpn))
        }
    return buildJsonObject {
        this@withTorPrivacyRouteOutbound.forEach { (key, value) ->
            if (key == "outbounds") {
                put(key, patchedOutbounds)
            } else {
                put(key, value)
            }
        }
        if (!containsKey("outbounds")) {
            put("outbounds", patchedOutbounds)
        }
    }
}

internal fun torOverVpnOutbound(
    paths: TorRuntimePaths,
    detourThroughVpn: Boolean,
): JsonObject =
    buildJsonObject {
        put("type", "tor")
        put("tag", TOR_OVER_VPN_OUTBOUND_TAG)
        if (!detourThroughVpn) {
            if (paths.bridges.isNotEmpty()) {
                putJsonArray("bridges") {
                    paths.bridges.forEach { bridge -> add(JsonPrimitive(bridge)) }
                }
            }
            if (paths.pluggableTransports.isNotEmpty()) {
                putJsonArray("pluggable_transports") {
                    paths.pluggableTransports.forEach { transport ->
                        add(
                            buildJsonObject {
                                putJsonArray("protocols") {
                                    transport.protocols.forEach { protocol -> add(JsonPrimitive(protocol)) }
                                }
                                put("path", transport.executablePath)
                                putJsonArray("arguments") {
                                    transport.arguments.forEach { argument -> add(JsonPrimitive(argument)) }
                                }
                                put("run_on_startup", true)
                            },
                        )
                    }
                }
            }
        }
        put("data_directory", paths.dataDirectory)
        if (detourThroughVpn) {
            put("detour", "proxy")
        }
    }

internal fun torPrimaryFoxCoreOutbound(paths: TorRuntimePaths): JsonObject =
    buildJsonObject {
        torOverVpnOutbound(paths, detourThroughVpn = false).forEach(::put)
        put("tag", "proxy")
    }

internal fun patchTcpReliabilityOutbounds(outbounds: JsonArray?): JsonArray? =
    outbounds?.let { source ->
        buildJsonArray {
            source.forEach { outbound ->
                add(patchTcpReliabilityOutbound(outbound.jsonObject))
            }
        }
    }

internal fun patchTcpReliabilityOutbound(outbound: JsonObject): JsonObject {
    val tcpReliabilityPatch = outbound.requiresTcpReliabilityPatch()
    val vlessUdpRelayPatch = outbound.requiresVlessUdpRelayPatch()
    if (!tcpReliabilityPatch && !vlessUdpRelayPatch) {
        return outbound
    }
    return buildJsonObject {
        outbound.forEach { (key, value) ->
            if (vlessUdpRelayPatch && key == "network") {
                return@forEach
            }
            put(key, value)
        }
        if (vlessUdpRelayPatch && !outbound.containsKey("packet_encoding")) {
            put("packet_encoding", "xudp")
        }
        if (tcpReliabilityPatch && outbound["disable_tcp_keep_alive"]?.jsonPrimitive?.contentOrNull != "true") {
            if (!outbound.containsKey("tcp_keep_alive")) {
                put("tcp_keep_alive", MOBILE_TCP_KEEP_ALIVE)
            }
            if (!outbound.containsKey("tcp_keep_alive_interval")) {
                put("tcp_keep_alive_interval", MOBILE_TCP_KEEP_ALIVE_INTERVAL)
            }
        }
    }
}

internal fun JsonObject.requiresTcpReliabilityPatch(): Boolean {
    val type = this["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
    val transportType =
        this["transport"]
            ?.jsonObject
            ?.get("type")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.lowercase()
            ?: "tcp"
    return type in TCP_RELIABILITY_OUTBOUND_TYPES &&
        !containsKey("detour") &&
        transportType in TCP_RELIABILITY_TRANSPORT_TYPES
}

internal fun JsonObject.requiresVlessUdpRelayPatch(): Boolean =
    this["type"]?.jsonPrimitive?.contentOrNull?.lowercase() == "vless" &&
        isTcpOnlyNetwork() &&
        !hasVlessFlow() &&
        stringField("packet_encoding")?.lowercase() in setOf(null, "xudp", "packetaddr")

internal fun JsonObject.hasVlessFlow(): Boolean =
    stringField("flow") != null
