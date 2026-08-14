package com.foxhole.guard.core.data

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

internal fun stableSubscriptionProfileFingerprint(
    protocolHint: ProtocolHint,
    normalizedConfigJson: String?,
    protocolOptions: List<StoredProfileProtocolOption>,
): String? {
    val material =
        if (protocolOptions.isNotEmpty()) {
            protocolOptions
                .mapNotNull { option ->
                    option.normalizedConfigJson
                        .takeIf(String::isNotBlank)
                        ?.let { config -> "${option.protocolHint.name}:${canonicalConfig(config)}" }
                }
                .sorted()
                .joinToString(separator = "|", prefix = "options:")
                .takeIf { it != "options:" }
        } else {
            normalizedConfigJson
                ?.takeIf(String::isNotBlank)
                ?.let { config -> "profile:${protocolHint.name}:${canonicalConfig(config)}" }
        } ?: return null
    return "sha256:${sha256Hex(material)}"
}

internal fun stableRawImportFingerprint(rawInput: String): String =
    "sha256:${sha256Hex(canonicalConfig(rawInput.trim()))}"

private fun canonicalConfig(rawConfig: String): String =
    runCatching {
        canonicalJson(fingerprintJson.parseToJsonElement(rawConfig))
    }.getOrElse {
        rawConfig.trim()
    }

private fun canonicalJson(element: JsonElement): String =
    when (element) {
        is JsonObject ->
            element.entries
                .sortedBy { entry -> entry.key }
                .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                    "${JsonPrimitive(key)}:${canonicalJson(value)}"
                }
        is JsonArray ->
            element.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
                canonicalJson(item)
            }
        else -> element.toString()
    }

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val fingerprintJson = Json
