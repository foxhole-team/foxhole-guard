package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal enum class RouterEngineAvailability { AVAILABLE, NOT_RELEASED, UNSUPPORTED_TARGET }
internal enum class RouterEngineStorage { FLASH, RAM }

internal data class RouterEngineEntry(
    val protocol: RouterInstallProtocol,
    val availability: RouterEngineAvailability,
    val installed: Boolean,
    val storage: RouterEngineStorage?,
    val version: String?,
    val binarySha256: String?,
    val active: Boolean,
    val updateAvailable: Boolean,
    val archiveBytes: Long?,
    val installedBytes: Long?,
) {
    val canAdd: Boolean get() = !installed && availability == RouterEngineAvailability.AVAILABLE
    val canUpdate: Boolean get() = installed && updateAvailable && availability == RouterEngineAvailability.AVAILABLE
}

internal data class RouterEngineStatus(
    val routerId: String,
    val revision: Long,
    val catalogSha256: String,
    val activeEngine: RouterInstallProtocol?,
    val activeProfileId: String?,
    val entries: List<RouterEngineEntry>,
    val busy: Boolean,
    val activeOwnJob: RouterEngineJobRef?,
    val lastOwnJob: RouterEngineJobRef?,
)

internal fun parseRouterEngineStatus(value: JsonObject): RouterEngineStatus {
    value.engineSchema()
    val entries = value.engineObjects("entries", 12).map { item ->
        RouterEngineEntry(
            item.engineProtocol("id"), item.engineEnum("availability"), item.engineBoolean("installed"),
            item.engineNullableText("storage")?.let { engineEnumValue<RouterEngineStorage>(it) },
            item.engineNullableText("version", 96), item.engineNullableHash("binarySha256"),
            item.engineBoolean("active"), item.engineBoolean("updateAvailable"),
            item.engineNullableNumber("archiveBytes"), item.engineNullableNumber("installedBytes"),
        )
    }
    if (entries.map { it.protocol }.distinct().size != entries.size) invalidEngineResponse()
    val active = value.engineNullableText("activeEngineId")?.let(::engineProtocol)
    if (active != null && entries.none { it.protocol == active && it.active && it.installed }) invalidEngineResponse()
    if (entries.count { it.active } > 1 || entries.any { it.active && it.protocol != active }) invalidEngineResponse()
    return RouterEngineStatus(value.engineId("routerId"), value.engineNumber("revision"), value.engineHash("catalogSha256"),
        active, value.engineNullableId("activeProfileId"), entries, value.engineBoolean("busy"),
        value.engineNullableObject("activeOwnJob")?.let(::parseRouterEngineJobRef),
        value.engineNullableObject("lastOwnJob")?.let(::parseRouterEngineJobRef))
}

internal fun invalidEngineResponse(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
internal fun JsonObject.engineSchema() { if (engineNumber("schema") != 1L) invalidEngineResponse() }
internal fun JsonObject.engineText(name: String, maximum: Int = 160): String = engineNullableText(name, maximum) ?: invalidEngineResponse()
internal fun JsonObject.engineNullableText(name: String, maximum: Int = 160): String? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalidEngineResponse()
    if (text.isEmpty() || text.length > maximum || text.any(Char::isISOControl)) invalidEngineResponse()
    return text
}
internal fun JsonObject.engineId(name: String): String = engineText(name).also(::requireEngineId)
internal fun JsonObject.engineNullableId(name: String): String? = engineNullableText(name)?.also(::requireEngineId)
internal fun requireEngineId(value: String) {
    if (!value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}"))) invalidEngineResponse()
}
internal fun JsonObject.engineHash(name: String): String = engineText(name).also(::requireEngineHash)
internal fun JsonObject.engineNullableHash(name: String): String? = engineNullableText(name)?.also(::requireEngineHash)
internal fun requireEngineHash(value: String) { if (!value.matches(Regex("[a-f0-9]{64}"))) invalidEngineResponse() }
internal fun JsonObject.engineNumber(name: String): Long = engineNullableNumber(name) ?: invalidEngineResponse()
internal fun JsonObject.engineNullableNumber(name: String): Long? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: invalidEngineResponse()
    if (number !in 0..9007199254740991L) invalidEngineResponse()
    return number
}
internal fun JsonObject.engineBoolean(name: String): Boolean = engineNullableBoolean(name) ?: invalidEngineResponse()
internal fun JsonObject.engineNullableBoolean(name: String): Boolean? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalidEngineResponse()
}
internal fun JsonObject.engineObjects(name: String, maximum: Int): List<JsonObject> {
    val array = get(name) as? JsonArray ?: invalidEngineResponse()
    if (array.size > maximum) invalidEngineResponse()
    return array.map { it as? JsonObject ?: invalidEngineResponse() }
}
internal fun JsonObject.engineNullableObject(name: String): JsonObject? = when (val value = get(name)) {
    null, JsonNull -> null
    is JsonObject -> value
    else -> invalidEngineResponse()
}
internal fun JsonObject.engineProtocol(name: String): RouterInstallProtocol =
    com.foxhole.guard.router.engineProtocol(engineText(name))
internal fun engineProtocol(value: String): RouterInstallProtocol =
    RouterInstallProtocol.entries.singleOrNull { it.canonical == value } ?: invalidEngineResponse()
internal inline fun <reified T : Enum<T>> engineEnumValue(value: String): T =
    enumValues<T>().singleOrNull { it.name == value } ?: invalidEngineResponse()
internal inline fun <reified T : Enum<T>> JsonObject.engineEnum(name: String): T = engineEnumValue(engineText(name))
internal fun engineStrings(value: JsonElement?, maximum: Int): List<String> {
    val array = value as? JsonArray ?: invalidEngineResponse()
    if (array.size > maximum) invalidEngineResponse()
    return array.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: invalidEngineResponse() }
}
