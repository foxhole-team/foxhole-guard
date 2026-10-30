package com.foxhole.core.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress

internal fun isWireGuardPacketRoute(root: JsonObject): Boolean {
    val endpoints = (root["endpoints"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val sources = definitions(root)
    if (sources.mapNotNull { it.string("tag") }.distinct().size != sources.size) return false
    val finalTag = (root["route"] as? JsonObject)?.string("final") ?: "proxy"
    val primary = sources.singleOrNull { it.string("tag") == finalTag } ?: return false
    return selectedPacketEndpoint(primary, endpoints)?.string("type") == "wireguard"
}

private fun selectedPacketEndpoint(primary: JsonObject, endpoints: List<JsonObject>): JsonObject? {
    if (primary.string("type") != "selector") return endpoints.singleOrNull { it == primary }
    val members = (primary["outbounds"] as? JsonArray).orEmpty()
    val target = members.singleOrNull()?.jsonPrimitive?.content ?: return null
    if (primary.string("default")?.let { it != target } == true) return null
    return endpoints.singleOrNull { it.string("tag") == target }
}

internal fun isWireGuardTunnelDnsRecord(server: JsonObject): Boolean {
    if (server.string("tag") != WIREGUARD_DNS_SERVER_TAG) return false
    if (server.string("type") != "udp" || server.string("detour") != "proxy") return false
    val port = server.string("server_port")?.toIntOrNull() ?: return false
    if (port !in 1..65535) return false
    return isTunnelResolverLiteral(server.string("server"))
}

private fun isTunnelResolverLiteral(value: String?): Boolean {
    val literal = value?.trim()?.removeSurrounding("[", "]") ?: return false
    val numeric = if (':' in literal) {
        literal.all { it in "0123456789abcdefABCDEF:." }
    } else {
        literal.count { it == '.' } == 3 && literal.all { it in '0'..'9' || it == '.' }
    }
    if (!numeric) return false
    val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
    return listOf(
        address.isLoopbackAddress,
        address.isAnyLocalAddress,
        address.isLinkLocalAddress,
        address.isMulticastAddress
    ).none {
        it
    }
}
