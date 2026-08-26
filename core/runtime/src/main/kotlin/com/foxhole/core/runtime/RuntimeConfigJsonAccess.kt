package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.stringField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

internal fun JsonObject.networkField(): String? =
    networkValues()?.joinToString(",")

internal fun JsonObject.networkValues(): List<String>? =
    when (val value = this["network"]) {
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let(::listOf)
        else -> null
    }?.takeIf { it.isNotEmpty() }

internal fun JsonObject.isTcpOnlyNetwork(): Boolean =
    networkValues() == listOf("tcp")

internal fun JsonObject.enabledField(default: Boolean): Boolean =
    stringField("enabled")?.toBooleanStrictOrNull() ?: default
