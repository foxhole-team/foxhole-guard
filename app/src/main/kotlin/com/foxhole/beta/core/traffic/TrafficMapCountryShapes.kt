package com.foxhole.beta.core.traffic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
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

class TrafficMapCountryShapeAssetParser {
    fun parse(raw: String): List<TrafficMapCountryShape> =
        TrafficMapPreprocessedAssetScanner(raw).parse()

    fun parse(inputStream: InputStream): List<TrafficMapCountryShape> =
        inputStream.bufferedReader(Charsets.UTF_8).use { reader -> parse(reader.readText()) }

    internal companion object {
        const val IsoCountryCodeLength = 2
        const val MinPolygonRingPoints = 3

        internal fun isIsoCountryCode(value: String): Boolean =
            value.length == IsoCountryCodeLength && value.all { character -> character in 'A'..'Z' }
    }
}

@Suppress("TooManyFunctions")
private class TrafficMapPreprocessedAssetScanner(
    private val raw: String,
) {
    private var index = 0

    fun parse(): List<TrafficMapCountryShape> {
        if (!seekCountriesArray()) {
            return emptyList()
        }
        val countries = mutableListOf<TrafficMapCountryShape>()
        while (hasNextArrayElement()) {
            parseCountry()?.let(countries::add)
        }
        return countries
    }

    private fun seekCountriesArray(): Boolean {
        val countriesIndex = raw.indexOf("\"countries\"")
        if (countriesIndex < 0) {
            return false
        }
        index = countriesIndex + "\"countries\"".length
        skipWhitespace()
        if (!consume(':')) {
            return false
        }
        skipWhitespace()
        return consume('[')
    }

    private fun parseCountry(): TrafficMapCountryShape? {
        if (!consume('{')) {
            skipValue()
            return null
        }
        var countryCode: String? = null
        var rings: List<List<TrafficMapGeoPoint>> = emptyList()
        while (index < raw.length) {
            skipWhitespace()
            if (consume('}')) {
                break
            }
            val key = parseStringOrNull() ?: return null
            skipWhitespace()
            if (!consume(':')) {
                return null
            }
            skipWhitespace()
            when (key) {
                "code" -> countryCode = parseStringOrNull()?.trim()?.uppercase(Locale.US)
                "rings" -> rings = parseRings()
                else -> skipValue()
            }
            skipWhitespace()
            consume(',')
        }
        return countryCode
            ?.takeIf(TrafficMapCountryShapeAssetParser::isIsoCountryCode)
            ?.let { code ->
                TrafficMapCountryShape(countryCode = code, rings = rings)
                    .takeIf { shape -> shape.rings.isNotEmpty() }
            }
    }

    private fun parseRings(): List<List<TrafficMapGeoPoint>> {
        if (!consume('[')) {
            skipValue()
            return emptyList()
        }
        val rings = mutableListOf<List<TrafficMapGeoPoint>>()
        while (hasNextArrayElement()) {
            parseRing()?.let(rings::add)
        }
        return rings
    }

    private fun parseRing(): List<TrafficMapGeoPoint>? {
        if (!consume('[')) {
            skipValue()
            return null
        }
        val points = mutableListOf<TrafficMapGeoPoint>()
        while (hasNextArrayElement()) {
            parsePoint()?.let(points::add)
        }
        return points.takeIf { it.size >= TrafficMapCountryShapeAssetParser.MinPolygonRingPoints }
    }

    private fun parsePoint(): TrafficMapGeoPoint? {
        if (!consume('[')) {
            skipValue()
            return null
        }
        val lat = parseNumberOrNull()
        skipWhitespace()
        consume(',')
        val lon = parseNumberOrNull()
        skipUntilArrayEnd()
        return if (lat != null && lon != null) {
            TrafficMapGeoPoint(lat = lat, lon = lon)
        } else {
            null
        }
    }

    private fun hasNextArrayElement(): Boolean {
        skipWhitespace()
        if (consume(']')) {
            return false
        }
        if (peek() == ',') {
            index += 1
            skipWhitespace()
            if (consume(']')) {
                return false
            }
        }
        return index < raw.length
    }

    private fun skipValue() {
        skipWhitespace()
        when (peek()) {
            '"' -> parseStringOrNull()
            '{' -> skipBalanced(open = '{', close = '}')
            '[' -> skipBalanced(open = '[', close = ']')
            else -> skipPrimitive()
        }
    }

    private fun skipBalanced(
        open: Char,
        close: Char,
    ) {
        if (!consume(open)) {
            return
        }
        var depth = 1
        while (index < raw.length && depth > 0) {
            when (raw[index]) {
                '"' -> parseStringOrNull()
                open -> {
                    depth += 1
                    index += 1
                }
                close -> {
                    depth -= 1
                    index += 1
                }
                else -> index += 1
            }
        }
    }

    private fun skipPrimitive() {
        while (index < raw.length && raw[index] !in PrimitiveTerminators) {
            index += 1
        }
    }

    private fun skipUntilArrayEnd() {
        while (index < raw.length) {
            when (raw[index]) {
                '"' -> parseStringOrNull()
                ']' -> {
                    index += 1
                    return
                }
                else -> index += 1
            }
        }
    }

    private fun parseNumberOrNull(): Double? {
        skipWhitespace()
        val start = index
        while (index < raw.length && raw[index] in NumberCharacters) {
            index += 1
        }
        return raw.substring(start, index).toDoubleOrNull()
    }

    private fun parseStringOrNull(): String? {
        if (!consume('"')) {
            return null
        }
        val builder = StringBuilder()
        while (index < raw.length) {
            when (val character = raw[index++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscapedCharacterOrNull() ?: return null)
                else -> builder.append(character)
            }
        }
        return null
    }

    private fun parseEscapedCharacterOrNull(): Char? {
        if (index >= raw.length) {
            return null
        }
        return when (val escaped = raw[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscapeOrNull()
            else -> null
        }
    }

    private fun parseUnicodeEscapeOrNull(): Char? {
        if (index + UnicodeEscapeLength > raw.length) {
            return null
        }
        val value = raw.substring(index, index + UnicodeEscapeLength).toIntOrNull(16) ?: return null
        index += UnicodeEscapeLength
        return value.toChar()
    }

    private fun skipWhitespace() {
        while (index < raw.length && raw[index].isWhitespace()) {
            index += 1
        }
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) {
            return false
        }
        index += 1
        return true
    }

    private fun peek(): Char? =
        raw.getOrNull(index)

    private companion object {
        const val UnicodeEscapeLength = 4
        val PrimitiveTerminators = charArrayOf(',', '}', ']')
        const val NumberCharacters = "-+.eE0123456789"
    }
}

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
