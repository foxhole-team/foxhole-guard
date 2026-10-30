package com.foxhole.guard.router

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal data class RouterLanDevice(
    val id: String,
    val name: String,
    val address: String,
    val mac: String?,
    val online: Boolean?,
    val route: String?,
)

internal fun parseRouterLanDevices(value: JsonObject, snapshot: RouterControlSnapshot): List<RouterLanDevice> {
    val entries = value["devices"] as? JsonArray ?: invalidRouterDevice()
    if (entries.size > 256) invalidRouterDevice()
    val seen = mutableSetOf<String>()
    val observed = entries.map { entry ->
        val item = entry as? JsonObject ?: invalidRouterDevice()
        val name = item.deviceText("name")
        val address = item.deviceText("address")
        val mac = item.deviceText("mac").lowercase()
        if (!validDeviceName(name) || routerLanAddress(address) == null || !mac.matches(ROUTER_MAC)) invalidRouterDevice()
        if (!seen.add(mac)) invalidRouterDevice()
        val matches = snapshot.devices.mapNotNull { it as? JsonObject }.filter {
            it.deviceOptionalText("mac")?.lowercase() == mac || it.deviceOptionalText("address") == address
        }
        if (matches.size > 1) invalidRouterDevice()
        val saved = matches.singleOrNull()
        RouterLanDevice(saved?.deviceText("id") ?: deviceIdentity(mac), name, address, mac,
            item.deviceOnline(), saved?.deviceText("route"))
    }
    val observedIds = observed.map { it.id }.toSet()
    val stored = snapshot.devices.mapNotNull { it as? JsonObject }.mapNotNull { item ->
        val id = item.deviceText("id")
        val address = item.deviceOptionalText("address") ?: return@mapNotNull null
        if (id in observedIds || routerLanAddress(address) == null) return@mapNotNull null
        val name = item.deviceOptionalText("name").orEmpty().ifBlank { address }
        RouterLanDevice(id, name, address, item.deviceOptionalText("mac"), null, item.deviceText("route"))
    }
    return (observed + stored).also { if (it.map(RouterLanDevice::id).distinct().size != it.size) invalidRouterDevice() }
}

private val ROUTER_MAC = Regex("[a-f0-9]{2}(?::[a-f0-9]{2}){5}")
private fun validDeviceName(value: String): Boolean = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 64 && value.none(Char::isISOControl)
private fun deviceIdentity(mac: String): String = "device-" + MessageDigest.getInstance("SHA-256")
    .digest(mac.toByteArray(Charsets.US_ASCII)).take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun JsonObject.deviceText(field: String): String = deviceOptionalText(field) ?: invalidRouterDevice()
private fun JsonObject.deviceOptionalText(field: String): String? = get(field).let {
    if (it == null || it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { field -> field.isString }?.contentOrNull ?: invalidRouterDevice()
}
private fun JsonObject.deviceOnline(): Boolean? = get("online").let {
    if (it == null || it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { field -> !field.isString }?.booleanOrNull ?: invalidRouterDevice()
}
private fun invalidRouterDevice(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
