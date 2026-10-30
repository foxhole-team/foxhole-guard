package com.foxhole.guard.core.settings

import com.foxhole.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.core.model.Settings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun decodeStoredSettingsPayload(
    payload: String,
    json: Json,
    migrateCurrentPayload: (String) -> String = { it },
): Settings {
    val root = json.parseToJsonElement(payload) as? JsonObject
        ?: throw SerializationException("settings payload must be an object")
    val legacy = validateSettingsPayloadShape(root)
    if (legacy) {
        return json.decodeFromString(LegacyFlatSettings.serializer(), payload).toCurrent().normalized()
    }
    root["schemaVersion"]?.let { version ->
        val primitive = version as? JsonPrimitive
        val supported = primitive?.intOrNull?.let { it in 1..SETTINGS_SCHEMA_VERSION } == true
        if (primitive == null || primitive.isString || !supported) {
            throw SerializationException("unsupported settings schema version")
        }
    }
    return json.decodeFromString(Settings.serializer(), migrateCurrentPayload(payload)).normalized()
}

private fun validateSettingsPayloadShape(root: JsonObject): Boolean {
    val modern = root.keys.any { it in modernSettingsKeys }
    val legacy = root.keys.any { it in legacySettingsKeys }
    val invalidShape = when {
        modern && legacy -> "settings payload mixes modern and legacy fields"
        !modern && !legacy && root.isNotEmpty() -> "unrecognized settings payload"
        legacy && root.keys.any { it !in legacySettingsKeys } -> "unrecognized legacy settings fields"
        else -> null
    }
    if (invalidShape != null) throw SerializationException(invalidShape)
    return legacy
}

@OptIn(ExperimentalSerializationApi::class)
private val modernSettingsKeys = Settings.serializer().descriptor.let { descriptor ->
    (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
}

@OptIn(ExperimentalSerializationApi::class)
private val legacySettingsKeys = LegacyFlatSettings.serializer().descriptor.let { descriptor ->
    (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
}
