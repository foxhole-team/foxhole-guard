package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal fun parseRouterInstalledProtocols(value: JsonElement?): Set<RouterInstallProtocol>? {
    if (value == null || value == JsonNull) return null
    fun invalid(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    val facts = value as? JsonObject ?: invalid()
    if (facts.keys != setOf("schema", "protocols")) invalid()
    val schema = facts["schema"] as? JsonPrimitive ?: invalid()
    if (schema.isString || schema.longOrNull != 1L) invalid()
    val names = facts["protocols"] as? JsonArray ?: invalid()
    if (names.size > RouterInstallProtocol.entries.size) invalid()
    val protocols = names.map { element ->
        val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalid()
        RouterInstallProtocol.entries.singleOrNull { it.canonical == name } ?: invalid()
    }
    if (protocols.distinct().size != protocols.size) invalid()
    return protocols.toSet()
}

internal fun routerInstalledProtocols(facts: Set<RouterInstallProtocol>?, entries: List<RouterEngineEntry>?): List<RouterInstallProtocol>? {
    val observed = facts.orEmpty() + entries.orEmpty().filter { it.installed }.map { it.protocol }
    // An empty modular inventory cannot prove that an unreported legacy runtime is absent.
    if (facts == null && observed.isEmpty()) return null
    return RouterInstallProtocol.entries.filter { it in observed }
}
