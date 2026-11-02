package com.foxhole.guard.router

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Only authenticated default-uplink facts qualify; missing legacy data stays unknown.
internal fun parseRouterPublicIpv4Evidence(value: JsonElement?): Boolean? {
    if (value == null) return null
    fun invalid(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    val facts = value as? JsonObject ?: invalid()
    if (facts.keys != setOf("schema", "source", "publicIpv4Available")) invalid()
    val schema = facts["schema"] as? JsonPrimitive ?: invalid()
    val source = facts["source"] as? JsonPrimitive ?: invalid()
    if (schema.isString || schema.longOrNull != 1L || !source.isString || source.contentOrNull != "default-uplink") invalid()
    val available = facts["publicIpv4Available"]
    if (available == JsonNull) return null
    return (available as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid()
}
