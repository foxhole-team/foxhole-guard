package com.foxhole.core.importer

import com.foxhole.core.importer.ProfileImportCoreSupport.ProxyNode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryReport
import com.foxhole.core.model.SubscriptionEntryStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun ProfileImportNodeSupport.parseSip008Servers(
    document: JsonObject,
    allowPrivateOutboundHosts: Boolean,
): ParsedNodeLines? {
    val servers = document["servers"]?.let { element -> runCatching { element.jsonArray }.getOrNull() } ?: return null
    require(document.keys.all { it in setOf("version", "servers", "bytes_used", "bytes_remaining") }) {
        "unsupported SIP008 document field"
    }
    require(document["version"] == null || document["version"]?.jsonPrimitive?.intOrNull == 1) {
        "unsupported SIP008 version"
    }
    require(servers.isNotEmpty() && servers.size <= MAX_SUBSCRIPTION_ENTRY_LINES) { "invalid SIP008 server count" }
    val nodes = mutableListOf<ProxyNode>()
    val reports = mutableListOf<SubscriptionEntryReport>()
    val rejected = mutableListOf<String>()
    servers.forEachIndexed { index, element ->
        val server = runCatching { element.jsonObject }.getOrNull()
        if (server == null) {
            rejected += "entry ${index + 1}: not an object"
            return@forEachIndexed
        }
        runCatching { parseSip008Server(server, allowPrivateOutboundHosts) }
            .onSuccess { node ->
                nodes += node
                reports +=
                    SubscriptionEntryReport(
                        protocolLabel = "SHADOWSOCKS",
                        protocolHint = node.protocolHint,
                        status = SubscriptionEntryStatus.ACCEPTED,
                        sourceLine = index + 1,
                    )
            }.onFailure {
                rejected += "entry ${index + 1}: invalid server definition"
            }
    }
    require(rejected.isEmpty()) {
        "invalid SIP008 entries: ${rejected.size} rejected (${rejected.take(3).joinToString("; ")})"
    }
    return ParsedNodeLines(nodes = nodes, reports = reports).takeIf { nodes.isNotEmpty() }
}

internal fun ProfileImportNodeSupport.parseOutlineServer(
    document: JsonObject,
    allowPrivateOutboundHosts: Boolean,
): ParsedNodeLines? {
    if (!document.keys.containsAll(setOf("server", "server_port", "method", "password"))) return null
    val node = parseSip008Server(document, allowPrivateOutboundHosts).copy(protocolHint = ProtocolHint.OUTLINE)
    return ParsedNodeLines(
        nodes = listOf(node),
        reports = listOf(SubscriptionEntryReport("OUTLINE", ProtocolHint.OUTLINE, SubscriptionEntryStatus.ACCEPTED, 1)),
    )
}

private fun ProfileImportNodeSupport.parseSip008Server(
    server: JsonObject,
    allowPrivateOutboundHosts: Boolean,
): ProxyNode {
    require(
        server.keys.all {
            it in setOf("id", "remarks", "server", "server_port", "method", "password", "plugin", "plugin_opts")
        }
    ) {
        "unsupported SIP008/Outline server field"
    }
    require(
        server["plugin"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
            server["plugin_opts"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
    ) {
        "shadowsocks plugins are not supported in v1"
    }
    val host = server.stringField("server") ?: error("missing server")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = server["server_port"]?.jsonPrimitive?.intOrNull ?: error("missing server_port")
    require(port in 1..65535) { "invalid server_port" }
    val method = server.stringField("method") ?: error("missing method")
    val password = server.stringField("password") ?: error("missing password")
    val displayName = server.stringField("remarks")?.take(MAX_NODE_DISPLAY_NAME_LENGTH) ?: host
    val outbound =
        buildJsonObject {
            put("type", "shadowsocks")
            put("tag", tagFor(displayName, "shadowsocks", host, port, "$method:$password"))
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
    return ProxyNode(
        displayName = displayName,
        protocolHint = ProtocolHint.SHADOWSOCKS,
        outbound = outbound,
    )
}

private fun JsonObject.stringField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
