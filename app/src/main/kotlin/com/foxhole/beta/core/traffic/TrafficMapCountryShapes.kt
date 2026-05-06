package com.foxhole.beta.core.traffic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

data class TrafficMapGeoPoint(
    val lat: Double,
    val lon: Double,
)

data class TrafficMapCountryShape(
    val countryCode: String,
    val rings: List<List<TrafficMapGeoPoint>>,
)

class TrafficMapCountryGeoJsonParser(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    fun parse(raw: String): List<TrafficMapCountryShape> {
        val root = json.parseToJsonElement(raw).jsonObject
        val features = root["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull(::parseFeature)
    }

    private fun parseFeature(featureElement: JsonElement): TrafficMapCountryShape? =
        runCatching {
            val feature = featureElement.jsonObject
            val countryCode = feature["properties"]?.jsonObject?.isoCountryCode()
            val rings = feature["geometry"]?.jsonObject?.countryRings().orEmpty()
            if (countryCode == null || rings.isEmpty()) {
                null
            } else {
                TrafficMapCountryShape(
                    countryCode = countryCode,
                    rings = rings,
                )
            }
        }.getOrNull()

    private fun JsonObject.countryRings(): List<List<TrafficMapGeoPoint>> {
        val coordinates = this["coordinates"] ?: return emptyList()
        return when (this["type"]?.jsonPrimitive?.contentOrNull) {
            "Polygon" -> coordinates.polygonRings()
            "MultiPolygon" -> coordinates.jsonArray.flatMap { polygon -> polygon.polygonRings() }
            else -> emptyList()
        }
    }

    private fun JsonElement.polygonRings(): List<List<TrafficMapGeoPoint>> =
        jsonArray.mapNotNull { ringElement ->
            val ring =
                ringElement.jsonArray.mapNotNull { coordinateElement ->
                    val coordinate = coordinateElement.jsonArray
                    val lon = coordinate.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val lat = coordinate.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    TrafficMapGeoPoint(lat = lat, lon = lon)
                }
            ring.takeIf { points -> points.size >= MinPolygonRingPoints }
        }

    private fun JsonObject.isoCountryCode(): String? =
        CountryCodePropertyNames.firstNotNullOfOrNull { propertyName ->
            this[propertyName]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.uppercase(Locale.US)
                ?.takeIf(::isIsoCountryCode)
        }

    private fun isIsoCountryCode(value: String): Boolean =
        value.length == IsoCountryCodeLength && value.all { character -> character in 'A'..'Z' }

    private companion object {
        const val IsoCountryCodeLength = 2
        const val MinPolygonRingPoints = 3
        val CountryCodePropertyNames = listOf("ISO_A2", "ISO_A2_EH", "WB_A2", "POSTAL")
    }
}
