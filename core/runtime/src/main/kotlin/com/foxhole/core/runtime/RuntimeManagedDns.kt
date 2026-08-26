package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Suppress("CyclomaticComplexMethod")
internal fun isFoxholeManagedDns(dns: JsonObject): Boolean {
    val servers = dns["servers"]?.jsonArray ?: return false
    val byTag =
        servers
            .mapNotNull { server ->
                val objectValue = server.jsonObject
                objectValue["tag"]?.jsonPrimitive?.contentOrNull?.let { it to objectValue }
            }.toMap()
    val local = byTag[DNS_LOCAL_TAG] ?: return false
    val remote = byTag[DNS_REMOTE_TAG] ?: return false
    val localMatches =
        (local["type"]?.jsonPrimitive?.contentOrNull == "local") ||
            (local["address"]?.jsonPrimitive?.contentOrNull == "local")
    val direct = byTag[DNS_DIRECT_TAG]
    val directMatches =
        direct == null ||
            direct["type"]?.jsonPrimitive?.contentOrNull == "local" ||
            direct["address"]?.jsonPrimitive?.contentOrNull == "local" ||
            isFoxholeBootstrapDnsServer(direct)
    val remoteMatches =
        remote.matchesFoxholeRemoteDns(type = "tcp", port = "53") ||
            remote.matchesFoxholeRemoteDns(type = "udp", port = "53") ||
            remote.matchesFoxholeRemoteDns(type = "https", port = "443", path = "/dns-query") ||
            remote["address"]?.jsonPrimitive?.contentOrNull == FOXHOLE_DOH_ADDRESS
    val remoteDetourMatches =
        remote["detour"]?.jsonPrimitive?.contentOrNull == null ||
            remote["detour"]?.jsonPrimitive?.contentOrNull == "proxy"
    return localMatches &&
        directMatches &&
        remoteMatches &&
        remoteDetourMatches
}

private fun isFoxholeBootstrapDnsServer(server: JsonObject): Boolean {
    if (server.containsKey("detour")) {
        return false
    }
    val type = server["type"]?.jsonPrimitive?.contentOrNull
    val address = server["address"]?.jsonPrimitive?.contentOrNull
    if (address == FOXHOLE_DOH_ADDRESS) {
        return true
    }
    if (server["server"]?.jsonPrimitive?.contentOrNull != FOXHOLE_REMOTE_DNS_SERVER) {
        return false
    }
    val port = server["server_port"]?.jsonPrimitive?.contentOrNull
    return when (type) {
        "udp", "tcp" -> port == "53"
        "https" -> port == "443" && server["path"]?.jsonPrimitive?.contentOrNull == "/dns-query"
        else -> false
    }
}

private fun JsonObject.matchesFoxholeRemoteDns(
    type: String,
    port: String,
    path: String? = null,
): Boolean =
    this["type"]?.jsonPrimitive?.contentOrNull == type &&
        this["server"]?.jsonPrimitive?.contentOrNull == FOXHOLE_REMOTE_DNS_SERVER &&
        this["server_port"]?.jsonPrimitive?.contentOrNull == port &&
        (path == null || this["path"]?.jsonPrimitive?.contentOrNull == path)
