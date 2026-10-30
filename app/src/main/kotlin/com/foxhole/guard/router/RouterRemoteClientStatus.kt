package com.foxhole.guard.router

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class RouterRemoteClientStatus(val id: String, val online: Boolean?)

internal fun parseRouterWanCountry(value: JsonObject): String? {
    val sample = value["sample"] as? JsonObject ?: return null
    val wan = sample["wan"] as? JsonObject ?: return null
    val country = (wan["country"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return country?.takeIf { it in Locale.getISOCountries().toSet() }
}

internal fun unknownRouterClientStatuses(snapshot: RouterControlSnapshot): List<RouterRemoteClientStatus> =
    snapshot.clients.map { RouterRemoteClientStatus(requireNotNull((it["id"] as? JsonPrimitive)?.contentOrNull), null) }

internal fun parseRouterClientStatuses(value: JsonObject, snapshot: RouterControlSnapshot): List<RouterRemoteClientStatus> {
    fun number(key: String) = (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    if (number("schema") != 1L || number("revision") != snapshot.revision) invalidClientStatus()
    val rows = value["clients"] as? JsonArray ?: invalidClientStatus()
    if (rows.size > 256) invalidClientStatus()
    val expected = unknownRouterClientStatuses(snapshot).map { it.id }.toSet()
    val seen = mutableSetOf<String>()
    val actual = rows.map { raw ->
        val item = raw as? JsonObject ?: invalidClientStatus()
        val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalidClientStatus()
        if (id !in expected || !seen.add(id)) invalidClientStatus()
        val field = item["online"]
        val online = if (field == null || field == JsonNull) null else
            (field as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalidClientStatus()
        RouterRemoteClientStatus(id, online)
    }
    if (seen != expected) invalidClientStatus()
    return actual
}

private fun invalidClientStatus(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
