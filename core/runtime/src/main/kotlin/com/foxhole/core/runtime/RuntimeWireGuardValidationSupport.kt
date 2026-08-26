package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun shouldPreferIpv4TunnelValidation(
    protocolHint: ProtocolHint?,
    configJson: String?,
): Boolean =
    when {
        protocolHint != ProtocolHint.WIREGUARD -> false
        configJson.isNullOrBlank() -> true
        else ->
            runCatching {
                val root = wireGuardValidationJson.parseToJsonElement(configJson).jsonObject
                val wireGuardEndpoints =
                    root["endpoints"]
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonObject }
                        .filter { endpoint ->
                            endpoint["type"]?.jsonPrimitive?.contentOrNull.equals("wireguard", ignoreCase = true)
                        }
                wireGuardEndpoints.isEmpty() || wireGuardEndpoints.none(JsonObject::hasIpv6WireGuardAddress)
            }.getOrDefault(true)
    }

private val wireGuardValidationJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private fun JsonObject.hasIpv6WireGuardAddress(): Boolean =
    this["address"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .any { address -> address.substringBefore('/').contains(':') }
