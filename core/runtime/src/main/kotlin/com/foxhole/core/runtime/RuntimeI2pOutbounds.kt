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

// I2P (.i2p) routing patch for RuntimeConfigAssembler. Applied to the final assembled config after
// the standard build, so it never threads through the deep route/dns builders. i2pd only serves
// eepsites, so ONLY `.i2p` names are diverted to the local i2pd SOCKS proxy; everything else keeps
// its existing outbound. `.i2p` is not DNS-resolvable, so a fakeip DNS server hands the eepsite
// hostname (via a mapped fake IP) to i2pd — which resolves it inside I2P. Pure JSON transform.

internal const val I2P_OUTBOUND_TAG = "i2p"
internal const val I2P_FAKEIP_DNS_TAG = "dns-fakeip"
internal const val I2P_DOMAIN_SUFFIX = ".i2p"
internal const val I2P_FAKEIP_INET4_RANGE = "198.18.0.0/15"

internal fun i2pSocksOutbound(port: Int): JsonObject =
    buildJsonObject {
        put("type", "socks")
        put("tag", I2P_OUTBOUND_TAG)
        put("server", "127.0.0.1")
        put("server_port", port)
        put("version", "5")
        put("network", "tcp")
        I2pdSocksProxy.endpoint
            ?.takeIf { endpoint -> endpoint.port == port }
            ?.let { endpoint ->
                put("username", endpoint.username)
                put("password", endpoint.password)
            }
    }

/**
 * Adds the i2p outbound, a `.i2p` route rule (ahead of every existing rule so it wins over the Tor
 * rules and the final outbound), and a fakeip DNS server + `.i2p` DNS rule. A null/out-of-range
 * port is a no-op. Skipped under STRICT Private DNS, where fakeip + an enforced DoT resolver is a
 * known-fatal combination. Idempotent: a config that already carries the i2p outbound is returned
 * unchanged.
 */
internal fun JsonObject.withI2pRouting(
    i2pSocksPort: Int?,
    privateDnsMode: PrivateDnsMode?,
): JsonObject {
    val port = i2pSocksPort?.takeIf { it in 1..65535 } ?: return this
    if (privateDnsMode == PrivateDnsMode.STRICT) {
        return this
    }
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

// Insert the i2p outbound just before the terminal direct/block outbounds so it reads naturally.
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
