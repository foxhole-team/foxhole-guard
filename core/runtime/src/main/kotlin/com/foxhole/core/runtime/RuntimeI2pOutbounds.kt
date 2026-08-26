package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

internal const val I2P_OUTBOUND_TAG = "i2p"
internal const val I2P_FAKEIP_DNS_TAG = "dns-fakeip"
internal const val I2P_DOMAIN_SUFFIX = ".i2p"
internal const val I2P_FAKEIP_INET4_RANGE = "198.18.0.0/15"

class I2pPrivateDnsConflictException : IllegalStateException(
    "i2p_private_dns_conflict: strict Android Private DNS cannot carry the I2P fake-IP lane",
)

class I2pEndpointUnavailableException : IllegalStateException(
    "i2p_endpoint_unavailable: authenticated app-owned i2pd endpoint is unavailable",
)

fun requireI2pPrivateDnsCompatibility(
    i2pActive: Boolean,
    privateDnsMode: PrivateDnsMode?,
) {
    if (i2pActive && privateDnsMode == PrivateDnsMode.STRICT) {
        throw I2pPrivateDnsConflictException()
    }
}

internal fun i2pSocksOutbound(port: Int): JsonObject =
    I2pdSocksProxy.endpoint
        ?.takeIf { endpoint ->
            endpoint.port == port && endpoint.username.isNotBlank() && endpoint.password.isNotBlank()
        }?.let { endpoint ->
            buildJsonObject {
                put("type", "socks")
                put("tag", I2P_OUTBOUND_TAG)
                put("server", "127.0.0.1")
                put("server_port", port)
                put("version", "5")
                put("network", "tcp")
                put("username", endpoint.username)
                put("password", endpoint.password)
            }
        } ?: throw I2pEndpointUnavailableException()

internal fun JsonObject.withI2pRouting(
    i2pSocksPort: Int?,
    privateDnsMode: PrivateDnsMode?,
): JsonObject {
    val port = i2pSocksPort?.takeIf { it in 1..65535 } ?: return this
    requireI2pPrivateDnsCompatibility(i2pActive = true, privateDnsMode = privateDnsMode)
    val outbounds = this["outbounds"]?.jsonArray.orEmpty()
    if (outbounds.any { it.jsonObject.tagEquals(I2P_OUTBOUND_TAG) }) {
        return this
    }
    return buildJsonObject {
        this@withI2pRouting.forEach { (key, value) ->
            when (key) {
                "outbounds" -> put("outbounds", outbounds.withI2pOutbound(port))
                "dns" -> put("dns", value.jsonObject.withI2pFakeIpDns())
                "route" -> put("route", value.jsonObject.withI2pRouteRule())
                else -> put(key, value)
            }
        }
    }
}

private fun JsonObject.tagEquals(tag: String): Boolean =
    this["tag"]?.jsonPrimitive?.contentOrNull == tag

private fun List<JsonElement>.withI2pOutbound(port: Int): JsonArray =
    buildJsonArray {
        var inserted = false
        forEach { element ->
            val type = element.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            if (!inserted && (type == "direct" || type == "block")) {
                add(i2pSocksOutbound(port))
                inserted = true
            }
            add(element)
        }
        if (!inserted) {
            add(i2pSocksOutbound(port))
        }
    }

private fun JsonObject.withI2pRouteRule(): JsonObject {
    val i2pRule =
        buildJsonObject {
            putJsonArray("domain_suffix") { add(JsonPrimitive(I2P_DOMAIN_SUFFIX)) }
            put("action", "route")
            put("outbound", I2P_OUTBOUND_TAG)
        }
    val existingRules = this["rules"]?.jsonArray.orEmpty()
    return buildJsonObject {
        this@withI2pRouteRule.forEach { (key, value) ->
            if (key != "rules") {
                put(key, value)
            }
        }
        put(
            "rules",
            buildJsonArray {
                add(i2pRule)
                existingRules.forEach(::add)
            }
        )
    }
}

private fun JsonObject.withI2pFakeIpDns(): JsonObject {
    val fakeIpServer =
        buildJsonObject {
            put("tag", I2P_FAKEIP_DNS_TAG)
            put("type", "fakeip")
            put("inet4_range", I2P_FAKEIP_INET4_RANGE)
        }
    val i2pDnsRule =
        buildJsonObject {
            putJsonArray("domain_suffix") { add(JsonPrimitive(I2P_DOMAIN_SUFFIX)) }
            put("server", I2P_FAKEIP_DNS_TAG)
        }
    val servers = this["servers"]?.jsonArray.orEmpty()
    val rules = this["rules"]?.jsonArray.orEmpty()
    return buildJsonObject {
        this@withI2pFakeIpDns.forEach { (key, value) ->
            if (key != "servers" && key != "rules") {
                put(key, value)
            }
        }
        put(
            "servers",
            buildJsonArray {
                servers.forEach(::add)
                add(fakeIpServer)
            }
        )
        put(
            "rules",
            buildJsonArray {
                add(i2pDnsRule)
                rules.forEach(::add)
            }
        )
    }
}
