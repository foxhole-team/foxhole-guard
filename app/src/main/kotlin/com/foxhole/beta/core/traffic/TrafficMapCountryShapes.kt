package com.foxhole.beta.core.traffic

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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

class TrafficMapCountryShapeAssetParser(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    fun parse(raw: String): List<TrafficMapCountryShape> {
        val asset = json.decodeFromString<TrafficMapPreprocessedAsset>(raw)
        return parse(asset)
    }

    private fun parse(asset: TrafficMapPreprocessedAsset): List<TrafficMapCountryShape> =
        asset.countries.mapNotNull(::parseCountry)

    private fun parseCountry(country: TrafficMapPreprocessedCountry): TrafficMapCountryShape? {
        val countryCode =
                country.code
                    ?.trim()
                    ?.uppercase(Locale.US)
                    ?.takeIf(::isIsoCountryCode)
                    ?: return null
        val rings = country.rings.mapNotNull(::parseRing)
        return TrafficMapCountryShape(
            countryCode = countryCode,
            rings = rings,
        ).takeIf { shape -> shape.rings.isNotEmpty() }
    }

    private fun parseRing(ring: List<List<Double>>): List<TrafficMapGeoPoint>? {
        val points =
            ring.mapNotNull { point ->
                val lat = point.getOrNull(0) ?: return@mapNotNull null
                val lon = point.getOrNull(1) ?: return@mapNotNull null
                TrafficMapGeoPoint(lat = lat, lon = lon)
            }
        return points.takeIf { it.size >= MinPolygonRingPoints }
    }

    private companion object {
        const val IsoCountryCodeLength = 2
        const val MinPolygonRingPoints = 3

        fun isIsoCountryCode(value: String): Boolean =
            value.length == IsoCountryCodeLength && value.all { character -> character in 'A'..'Z' }
    }
}

@Serializable
private data class TrafficMapPreprocessedAsset(
    val countries: List<TrafficMapPreprocessedCountry> = emptyList(),
)

@Serializable
private data class TrafficMapPreprocessedCountry(
    val code: String? = null,
    val rings: List<List<List<Double>>> = emptyList(),
)

internal fun TrafficMapCountryShape.toTrafficMapVisualShape(
    minRelativeRingArea: Double = TrafficMapVisualMinRelativeRingArea,
    minAbsoluteRingArea: Double = TrafficMapVisualMinAbsoluteRingArea,
    minShapeArea: Double = TrafficMapVisualMinShapeArea,
    maxPointsPerRing: Int = TrafficMapVisualMaxPointsPerRing,
): TrafficMapCountryShape? {
    val normalizedRings =
        rings
            .asSequence()
            .map(::normalizeTrafficMapRingLongitudes)
            .map { ring -> simplifyTrafficMapRing(ring, maxPointsPerRing) }
            .filter { ring -> ring.size >= TrafficMapVisualMinRingPoints }
            .toList()
    val areas = normalizedRings.map(::trafficMapRingArea)
    val largestArea = areas.maxOrNull() ?: 0.0
    val largestIndex = areas.indexOf(largestArea)
    val areaThreshold = max(largestArea * minRelativeRingArea, minAbsoluteRingArea)
    val visualRings =
        normalizedRings.filterIndexed { index, _ ->
            index == largestIndex || areas[index] >= areaThreshold
        }
    return copy(rings = visualRings).takeIf {
        normalizedRings.isNotEmpty() &&
            largestArea >= minShapeArea &&
            it.rings.isNotEmpty()
    }
}

internal fun normalizeTrafficMapRingLongitudes(ring: List<TrafficMapGeoPoint>): List<TrafficMapGeoPoint> {
    if (ring.size < TrafficMapVisualMinRingPoints) {
        return ring
    }
    val normalizedLongitudes = ring.map { point -> normalizeTrafficMapLongitude(point.lon) }
    val sorted = normalizedLongitudes.distinct().sorted()
    if (sorted.size <= 1) {
        return ring.mapIndexed { index, point -> point.copy(lon = normalizedLongitudes[index]) }
    }
    var largestGap = Double.NEGATIVE_INFINITY
    var intervalStart = sorted.first()
    sorted.forEachIndexed { index, longitude ->
        val next = if (index == sorted.lastIndex) sorted.first() + FullLongitudeDegrees else sorted[index + 1]
        val gap = next - longitude
        if (gap > largestGap) {
            largestGap = gap
            intervalStart = if (index == sorted.lastIndex) sorted.first() else sorted[index + 1]
        }
    }
    return ring.mapIndexed { index, point ->
        val longitude = normalizedLongitudes[index]
        point.copy(lon = if (longitude < intervalStart) longitude + FullLongitudeDegrees else longitude)
    }
}

internal fun trafficMapRingArea(ring: List<TrafficMapGeoPoint>): Double {
    if (ring.size < TrafficMapVisualMinRingPoints) {
        return 0.0
    }
    var area = 0.0
    ring.indices.forEach { index ->
        val current = ring[index]
        val next = ring[(index + 1) % ring.size]
        area += current.lon * next.lat - next.lon * current.lat
    }
    return abs(area) / 2.0
}

internal fun simplifyTrafficMapRing(
    ring: List<TrafficMapGeoPoint>,
    maxPoints: Int = TrafficMapVisualMaxPointsPerRing,
): List<TrafficMapGeoPoint> {
    val openRing =
        if (ring.size > 1 && ring.first() == ring.last()) {
            ring.dropLast(1)
        } else {
            ring
        }
    if (openRing.size <= maxPoints) {
        return openRing
    }
    val byDistance = mutableListOf<TrafficMapGeoPoint>()
    openRing.forEach { point ->
        val previous = byDistance.lastOrNull()
        if (
            previous == null ||
            abs(point.lat - previous.lat) + abs(point.lon - previous.lon) >= TrafficMapVisualMinPointDeltaDegrees
        ) {
            byDistance += point
        }
    }
    val reduced = byDistance.takeIf { points -> points.size >= TrafficMapVisualMinRingPoints } ?: openRing
    if (reduced.size <= maxPoints) {
        return reduced
    }
    val stride = ceil(reduced.size.toDouble() / maxPoints.toDouble()).toInt().coerceAtLeast(1)
    val sampled = reduced.filterIndexed { index, _ -> index % stride == 0 }
    return sampled.takeIf { points -> points.size >= TrafficMapVisualMinRingPoints } ?: reduced.take(maxPoints)
}

private fun normalizeTrafficMapLongitude(lon: Double): Double {
    var normalized = lon % FullLongitudeDegrees
    if (normalized < -HalfLongitudeDegrees) {
        normalized += FullLongitudeDegrees
    }
    if (normalized > HalfLongitudeDegrees) {
        normalized -= FullLongitudeDegrees
    }
    return normalized
}

private const val TrafficMapVisualMinRingPoints = 3
private const val TrafficMapVisualMinRelativeRingArea = 0.02
private const val TrafficMapVisualMinAbsoluteRingArea = 0.5

@Suppress("TopLevelPropertyNaming")
private const val TrafficMapVisualMinShapeArea = 1.0

private const val TrafficMapVisualMaxPointsPerRing = 220
private const val TrafficMapVisualMinPointDeltaDegrees = 0.045
private const val HalfLongitudeDegrees = 180.0
private const val FullLongitudeDegrees = 360.0
