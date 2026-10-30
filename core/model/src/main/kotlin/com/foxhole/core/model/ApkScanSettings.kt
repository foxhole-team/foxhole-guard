package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class ApkScanCost { ECONOMY, BALANCED }

@Serializable(with = ApkScanSettingsSerializer::class)
@Immutable
data class ApkScanSettings(
    val dexEnabled: Boolean = false,
    val yaraEnabled: Boolean = false,
    val notifyFindings: Boolean = false,
    val automaticNewUpdatedApps: Boolean = false,
    val chargingOnly: Boolean = true,
    val batteryNotLow: Boolean = true,
    val cost: ApkScanCost = ApkScanCost.ECONOMY,
)

internal object ApkScanSettingsSerializer : KSerializer<ApkScanSettings> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ApkScanSettings")
    private val booleans = setOf(
        "dexEnabled",
        "yaraEnabled",
        "notifyFindings",
        "automaticNewUpdatedApps",
        "chargingOnly",
        "batteryNotLow",
    )
    private val fields = booleans + "cost"

    override fun deserialize(decoder: Decoder): ApkScanSettings {
        require(decoder is JsonDecoder)
        val value = validatedObject(decoder) ?: return ApkScanSettings()
        val cost = value.cost() ?: return ApkScanSettings()
        return ApkScanSettings(
            dexEnabled = value.flag("dexEnabled", false),
            yaraEnabled = value.flag("yaraEnabled", false),
            notifyFindings = value.flag("notifyFindings", false),
            automaticNewUpdatedApps = value.flag("automaticNewUpdatedApps", false),
            chargingOnly = value.flag("chargingOnly", true),
            batteryNotLow = value.flag("batteryNotLow", true),
            cost = cost,
        )
    }

    override fun serialize(encoder: Encoder, value: ApkScanSettings) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(
            buildJsonObject {
                put("dexEnabled", value.dexEnabled)
                put("yaraEnabled", value.yaraEnabled)
                put("notifyFindings", value.notifyFindings)
                put("automaticNewUpdatedApps", value.automaticNewUpdatedApps)
                put("chargingOnly", value.chargingOnly)
                put("batteryNotLow", value.batteryNotLow)
                put("cost", value.cost.name)
            }
        )
    }

    private fun validatedObject(decoder: JsonDecoder): JsonObject? {
        val value = decoder.decodeJsonElement() as? JsonObject ?: return null
        val invalid = value.keys.any { it !in fields } || booleans.any { key -> key in value && !value.hasBoolean(key) }
        return value.takeUnless { invalid }
    }

    private fun JsonObject.cost(): ApkScanCost? {
        if ("cost" !in this) return ApkScanCost.ECONOMY
        val primitive = get("cost") as? JsonPrimitive ?: return null
        return ApkScanCost.entries.firstOrNull { primitive.isString && it.name == primitive.content }
    }

    private fun JsonObject.hasBoolean(key: String): Boolean {
        val primitive = get(key) as? JsonPrimitive ?: return false
        return !primitive.isString && primitive.booleanOrNull != null
    }

    private fun JsonObject.flag(key: String, default: Boolean): Boolean =
        (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
}
