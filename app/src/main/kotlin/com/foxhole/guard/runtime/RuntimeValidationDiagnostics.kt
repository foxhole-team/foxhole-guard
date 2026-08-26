package com.foxhole.guard.runtime

import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.VpnHealthProbeTargetSelector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val tunnelValidationJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal fun redactedRuntimeDnsShape(configJson: String?): String =
    runCatching {
        if (configJson.isNullOrBlank()) {
            "unavailable"
        } else {
            val dnsObject = tunnelValidationJson.parseToJsonElement(configJson).jsonObject["dns"]?.jsonObject
            val servers = dnsObject?.get("servers")?.jsonArray
            if (servers.isNullOrEmpty()) {
                "missing"
            } else {
                servers
                    .map { server -> server.jsonObject.redactedDnsServerShape() }
                    .joinToString(separator = "|")
            }
        }
    }.getOrDefault("unparseable")

private fun JsonObject.redactedDnsServerShape(): String =
    listOf(
        redactedDnsTag(),
        redactedDnsType(),
        redactedDnsPort(),
        redactedDnsDetour(),
    ).joinToString(separator = ":")

private fun JsonObject.redactedDnsTag(): String =
    when (this["tag"]?.jsonPrimitive?.contentOrNull) {
        "dns-local" -> "dns-local"
        "dns-direct" -> "dns-direct"
        "dns-remote" -> "dns-remote"
        null -> "untagged"
        else -> "custom"
    }

private fun JsonObject.redactedDnsType(): String =
    when (this["type"]?.jsonPrimitive?.contentOrNull ?: this["address"]?.jsonPrimitive?.contentOrNull) {
        "local" -> "platform"
        "udp" -> "udp"
        "tcp" -> "tcp"
        "https" -> "https"
        null -> "default"
        else -> "custom"
    }

private fun JsonObject.redactedDnsPort(): String =
    this["server_port"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { value -> value.all(Char::isDigit) }
        ?: "default"

private fun JsonObject.redactedDnsDetour(): String =
    when (this["detour"]?.jsonPrimitive?.contentOrNull) {
        "proxy" -> "proxy"
        "direct" -> "direct"
        null -> "no_detour"
        else -> "custom_detour"
    }

internal data class RuntimeValidationDiagnosticFields(
    val protocolHint: String?,
    val dnsShape: String,
    val probeTransport: String,
)

internal fun runtimeValidationDiagnosticFields(session: VpnSession?): RuntimeValidationDiagnosticFields {
    val probeTransport =
        VpnHealthProbeTargetSelector
            .select(session?.configJson)
            ?.transport
            ?.name
            ?.lowercase()
            ?.let { "probe_transport=$it" }
            ?: "probe_transport=unavailable"
    return RuntimeValidationDiagnosticFields(
        protocolHint = session?.protocolHint?.name?.lowercase()?.let { "protocol_hint=$it" },
        dnsShape = "dns_shape=${redactedRuntimeDnsShape(session?.configJson)}",
        probeTransport = probeTransport,
    )
}

internal fun Throwable?.isEndpointConnectRefusal(): Boolean {
    var cursor = this
    while (cursor != null) {
        if (cursor.message?.contains("Connection refused", ignoreCase = true) == true) {
            return true
        }
        cursor = cursor.cause
    }
    return false
}
