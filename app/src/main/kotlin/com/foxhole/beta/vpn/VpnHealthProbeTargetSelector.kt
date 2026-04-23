package com.foxhole.beta.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal enum class VpnHealthProbeTransport {
    TCP,
    UDP,
}

internal data class VpnHealthProbeTarget(
    val host: String,
    val port: Int,
    val transport: VpnHealthProbeTransport,
)

internal object VpnHealthProbeTargetSelector {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun select(configJson: String?): VpnHealthProbeTarget? =
        runCatching {
            if (configJson == null) {
                return null
            }
            val root = json.parseToJsonElement(configJson).jsonObject
            val outbounds = root["outbounds"]?.jsonArray.orEmpty().map { it.jsonObject }
            val outboundsByTag =
                outbounds.mapNotNull { outbound ->
                    outbound["tag"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { it to outbound }
                }.toMap()
            resolveTag(
                tag = PREFERRED_PROXY_TAG,
                outboundsByTag = outboundsByTag,
                visitedTags = mutableSetOf(),
            ) ?: outbounds.firstNotNullOfOrNull(::resolveDirectTarget)
        }.getOrNull()

    private fun resolveTag(
        tag: String,
        outboundsByTag: Map<String, JsonObject>,
        visitedTags: MutableSet<String>,
    ): VpnHealthProbeTarget? {
        if (!visitedTags.add(tag)) {
            return null
        }
        val outbound = outboundsByTag[tag] ?: return null
        resolveDirectTarget(outbound)?.let { return it }
        if (outbound["type"]?.jsonPrimitive?.contentOrNull !in SELECTOR_TYPES) {
            return null
        }
        return outbound["outbounds"]?.jsonArray.orEmpty().firstNotNullOfOrNull { candidate ->
            candidate.jsonPrimitive.contentOrNull?.let { resolveTag(it, outboundsByTag, visitedTags) }
        }
    }

    private fun resolveDirectTarget(outbound: JsonObject): VpnHealthProbeTarget? {
        val type = outbound["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type in LOCAL_OR_SELECTOR_TYPES) {
            return null
        }
        val wireGuardPeer =
            outbound["peers"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
        val host =
            outbound["server"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: wireGuardPeer
                    ?.get("server")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: return null
        val port =
            outbound["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: wireGuardPeer?.get("server_port")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: return null
        return VpnHealthProbeTarget(
            host = host,
            port = port,
            transport =
                if (type in UDP_TRANSPORT_TYPES) {
                    VpnHealthProbeTransport.UDP
                } else {
                    VpnHealthProbeTransport.TCP
                },
        )
    }

    private val UDP_TRANSPORT_TYPES = setOf("hysteria", "hysteria2", "wireguard")
    private val SELECTOR_TYPES = setOf("selector", "urltest")
    private val LOCAL_OR_SELECTOR_TYPES = SELECTOR_TYPES + setOf("direct", "block", "dns")
    private const val PREFERRED_PROXY_TAG = "proxy"
}
