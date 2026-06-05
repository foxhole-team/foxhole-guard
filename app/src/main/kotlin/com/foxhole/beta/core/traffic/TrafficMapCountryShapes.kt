package com.foxhole.beta.core.traffic

import android.content.Context
import android.os.Trace
import androidx.compose.runtime.Immutable
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
import kotlin.math.pow

internal const val TRAFFIC_MAP_COUNTRY_SHAPES_ASSET = "maps/ne_110m_admin_0_countries_preprocessed.json"

@Immutable
data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class TrafficMapGeoPoint(
    val lat: Double,
    val lon: Double,
)

data class TrafficMapCountryShape(
    val countryCode: String,
    val rings: List<List<TrafficMapGeoPoint>>,
)

@Immutable
data class TrafficMapCountryMeta(
    val countryCode: String,
    val label: String,
    val centroidLat: Double,
    val centroidLon: Double,
    val bounds: GeoBounds,
    val rings: List<List<TrafficMapGeoPoint>>,
)

class TrafficMapCountryRegistry private constructor(
    private val metasByCountryCode: Map<String, TrafficMapCountryMeta>,
) {
    val coordinates: Map<String, TrafficMapCountryCoordinate> =
        metasByCountryCode.mapValues { (_, meta) ->
            TrafficMapCountryCoordinate(
                countryCode = meta.countryCode,
                label = meta.label,
                lat = meta.centroidLat,
                lon = meta.centroidLon,
            )
        }

    fun meta(countryCode: String): TrafficMapCountryMeta? =
        metasByCountryCode[normalizeTrafficMapCountryCode(countryCode)]

    fun coordinate(countryCode: String): TrafficMapCountryCoordinate? =
        coordinates[normalizeTrafficMapCountryCode(countryCode)]

    fun contains(countryCode: String): Boolean =
        normalizeTrafficMapCountryCode(countryCode) in metasByCountryCode

    companion object {
        val LegacyCoordinates: Map<String, TrafficMapCountryCoordinate> =
            listOf(
                TrafficMapCountryCoordinate("AU", "Australia", -25.0, 133.0),
                TrafficMapCountryCoordinate("BR", "Brazil", -10.0, -55.0),
                TrafficMapCountryCoordinate("CA", "Canada", 56.0, -106.0),
                TrafficMapCountryCoordinate("CH", "Switzerland", 46.8, 8.2),
                TrafficMapCountryCoordinate("CN", "China", 35.0, 103.0),
                TrafficMapCountryCoordinate("DE", "Germany", 51.0, 10.0),
                TrafficMapCountryCoordinate("ES", "Spain", 40.0, -4.0),
                TrafficMapCountryCoordinate("FI", "Finland", 64.0, 26.0),
                TrafficMapCountryCoordinate("FR", "France", 46.0, 2.0),
                TrafficMapCountryCoordinate("GB", "United Kingdom", 54.0, -2.0),
                TrafficMapCountryCoordinate("HK", "Hong Kong", 22.3193, 114.1694),
                TrafficMapCountryCoordinate("ID", "Indonesia", -2.0, 118.0),
                TrafficMapCountryCoordinate("IE", "Ireland", 53.0, -8.0),
                TrafficMapCountryCoordinate("IN", "India", 22.0, 79.0),
                TrafficMapCountryCoordinate("IT", "Italy", 42.5, 12.5),
                TrafficMapCountryCoordinate("JP", "Japan", 37.0, 138.0),
                TrafficMapCountryCoordinate("KR", "South Korea", 36.0, 128.0),
                TrafficMapCountryCoordinate("MX", "Mexico", 23.0, -102.0),
                TrafficMapCountryCoordinate("NL", "Netherlands", 52.1, 5.3),
                TrafficMapCountryCoordinate("NO", "Norway", 61.0, 8.0),
                TrafficMapCountryCoordinate("PL", "Poland", 52.0, 19.0),
                TrafficMapCountryCoordinate("RO", "Romania", 45.8, 25.0),
                TrafficMapCountryCoordinate("RU", "Russia", 61.0, 105.0),
                TrafficMapCountryCoordinate("SE", "Sweden", 62.0, 15.0),
                TrafficMapCountryCoordinate("SG", "Singapore", 1.3521, 103.8198),
                TrafficMapCountryCoordinate("TR", "Turkey", 39.0, 35.0),
                TrafficMapCountryCoordinate("TW", "Taiwan", 23.7, 121.0),
                TrafficMapCountryCoordinate("UA", "Ukraine", 49.0, 32.0),
                TrafficMapCountryCoordinate("US", "United States", 39.8, -98.6),
                TrafficMapCountryCoordinate("ZA", "South Africa", -30.0, 24.0),
                TrafficMapCountryCoordinate("EU", "Europe", 50.0, 10.0),
            ).associateBy(TrafficMapCountryCoordinate::countryCode)

        fun fromShapes(
            shapes: List<TrafficMapCountryShape>,
            locale: Locale = Locale.getDefault(),
        ): TrafficMapCountryRegistry =
            traceTrafficMapCountryRegistrySection("TrafficMap/buildCountryRegistry") {
                TrafficMapCountryRegistry(
                    shapes
                        .asSequence()
                        .mapNotNull { shape ->
                            val countryCode = normalizeTrafficMapCountryCode(shape.countryCode) ?: return@mapNotNull null
                            val anchor = TrafficMapCountryAnchorOverrides[countryCode] ?: shape.visualCentroid()
                            TrafficMapCountryMeta(
                                countryCode = countryCode,
                                label = localizedTrafficMapCountryName(countryCode = countryCode, locale = locale),
                                centroidLat = anchor.lat,
                                centroidLon = anchor.lon,
                                bounds = shape.bounds(),
                                rings = shape.rings,
                            )
                        }
                        .distinctBy(TrafficMapCountryMeta::countryCode)
                        .associateBy(TrafficMapCountryMeta::countryCode),
                )
            }

        fun legacyFallback(): TrafficMapCountryRegistry =
            TrafficMapCountryRegistry(
                LegacyCoordinates.mapValues { (_, coordinate) ->
                    TrafficMapCountryMeta(
                        countryCode = coordinate.countryCode,
                        label = coordinate.label,
                        centroidLat = coordinate.lat,
                        centroidLon = coordinate.lon,
                        bounds = GeoBounds(
                            minLat = coordinate.lat,
                            maxLat = coordinate.lat,
                            minLon = coordinate.lon,
                            maxLon = coordinate.lon,
                        ),
                        rings = emptyList(),
                    )
                },
            )
    }
}

internal class AndroidTrafficMapCountryRegistryProvider(
    context: Context,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var cachedRegistry: TrafficMapCountryRegistry? = null

    fun registry(): TrafficMapCountryRegistry {
        cachedRegistry?.let { registry -> return registry }
        return synchronized(this) {
            cachedRegistry?.let { registry -> return@synchronized registry }
            runCatching {
                appContext.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET).use { inputStream ->
                    TrafficMapCountryRegistry.fromShapes(
                        TrafficMapCountryShapeAssetParser().parse(inputStream),
                    )
                }
            }
                .getOrElse { TrafficMapCountryRegistry.legacyFallback() }
                .also { registry -> cachedRegistry = registry }
        }
    }
}

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
        var sign = 1.0
        when (raw.getOrNull(index)) {
            '-' -> {
                sign = -1.0
                index += 1
            }
            '+' -> index += 1
        }

        var value = 0.0
        var hasDigit = false
        while (index < raw.length) {
            val digit = raw[index].fastDigitOrMinusOne()
            if (digit < 0) {
                break
            }
            hasDigit = true
            value = (value * 10.0) + digit
            index += 1
        }

        if (consume('.')) {
            var place = 0.1
            while (index < raw.length) {
                val digit = raw[index].fastDigitOrMinusOne()
                if (digit < 0) {
                    break
                }
                hasDigit = true
                value += digit * place
                place *= 0.1
                index += 1
            }
        }

        if (!hasDigit) {
            index = start
            return null
        }

        val exponentMarker = raw.getOrNull(index)
        if (exponentMarker == 'e' || exponentMarker == 'E') {
            index += 1
            var exponentSign = 1
            if (raw.getOrNull(index) == '-' || raw.getOrNull(index) == '+') {
                if (raw[index] == '-') {
                    exponentSign = -1
                }
                index += 1
            }
            var exponent = 0
            var hasExponentDigit = false
            while (index < raw.length) {
                val digit = raw[index].fastDigitOrMinusOne()
                if (digit < 0) {
                    break
                }
                hasExponentDigit = true
                exponent = (exponent * 10) + digit
                index += 1
            }
            if (hasExponentDigit) {
                value *= 10.0.pow(exponentSign * exponent)
            }
        }

        return sign * value
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
    }
}

private fun Char.fastDigitOrMinusOne(): Int {
    val digit = code - '0'.code
    return if (digit in 0..9) digit else -1
}

fun TrafficMapCountryShape.bounds(): GeoBounds {
    val points = rings.flatten()
    if (points.isEmpty()) {
        return GeoBounds(minLat = 0.0, maxLat = 0.0, minLon = 0.0, maxLon = 0.0)
    }
    return GeoBounds(
        minLat = points.minOf(TrafficMapGeoPoint::lat),
        maxLat = points.maxOf(TrafficMapGeoPoint::lat),
        minLon = points.minOf { point -> normalizeTrafficMapLongitude(point.lon) },
        maxLon = points.maxOf { point -> normalizeTrafficMapLongitude(point.lon) },
    )
}

fun TrafficMapCountryShape.visualCentroid(): TrafficMapGeoPoint {
    val largestRing =
        rings
            .asSequence()
            .map(::normalizeTrafficMapRingLongitudes)
            .filter { ring -> ring.size >= TrafficMapVisualMinRingPoints }
            .maxByOrNull(::trafficMapRingArea)
            ?: return TrafficMapGeoPoint(lat = 0.0, lon = 0.0)
    return trafficMapPolygonCentroid(largestRing)
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

@Suppress("ReturnCount")
private fun trafficMapPolygonCentroid(ring: List<TrafficMapGeoPoint>): TrafficMapGeoPoint {
    if (ring.size < TrafficMapVisualMinRingPoints) {
        return ring.firstOrNull() ?: TrafficMapGeoPoint(lat = 0.0, lon = 0.0)
    }
    var signedArea = 0.0
    var centroidLon = 0.0
    var centroidLat = 0.0
    ring.indices.forEach { index ->
        val current = ring[index]
        val next = ring[(index + 1) % ring.size]
        val cross = current.lon * next.lat - next.lon * current.lat
        signedArea += cross
        centroidLon += (current.lon + next.lon) * cross
        centroidLat += (current.lat + next.lat) * cross
    }
    if (abs(signedArea) < TRAFFIC_MAP_CENTROID_AREA_EPSILON) {
        return TrafficMapGeoPoint(
            lat = ring.map(TrafficMapGeoPoint::lat).average(),
            lon = normalizeTrafficMapLongitude(ring.map(TrafficMapGeoPoint::lon).average()),
        )
    }
    val area = signedArea * 0.5
    return TrafficMapGeoPoint(
        lat = centroidLat / (6.0 * area),
        lon = normalizeTrafficMapLongitude(centroidLon / (6.0 * area)),
    )
}

private fun localizedTrafficMapCountryName(
    countryCode: String,
    locale: Locale,
): String {
    val fallback = TrafficMapCountryRegistry.LegacyCoordinates[countryCode]?.label ?: countryCode
    return runCatching {
        Locale.Builder()
            .setRegion(countryCode)
            .build()
            .getDisplayCountry(locale)
            .takeIf { label -> label.isNotBlank() && label != countryCode }
    }.getOrNull() ?: fallback
}

private fun normalizeTrafficMapCountryCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { value ->
            value.length == TrafficMapCountryShapeAssetParser.IsoCountryCodeLength &&
                value.all { character -> character in 'A'..'Z' }
        }

private inline fun <T> traceTrafficMapCountryRegistrySection(
    sectionName: String,
    block: () -> T,
): T {
    val traceStarted =
        runCatching {
            Trace.beginSection(sectionName.take(MAX_TRACE_SECTION_NAME_LENGTH))
            true
        }.getOrDefault(false)
    return try {
        block()
    } finally {
        if (traceStarted) {
            runCatching { Trace.endSection() }
        }
    }
}

private val TrafficMapCountryAnchorOverrides =
    mapOf(
        "CA" to TrafficMapGeoPoint(lat = 56.0, lon = -106.0),
        "HK" to TrafficMapGeoPoint(lat = 22.3193, lon = 114.1694),
        "RU" to TrafficMapGeoPoint(lat = 58.5, lon = 82.0),
        "SG" to TrafficMapGeoPoint(lat = 1.3521, lon = 103.8198),
        "US" to TrafficMapGeoPoint(lat = 39.8, lon = -98.6),
    )

private const val TrafficMapVisualMinRingPoints = 3
private const val TrafficMapVisualMinRelativeRingArea = 0.02
private const val TrafficMapVisualMinAbsoluteRingArea = 0.5
private const val TRAFFIC_MAP_CENTROID_AREA_EPSILON = 0.000001
private const val MAX_TRACE_SECTION_NAME_LENGTH = 127

@Suppress("TopLevelPropertyNaming")
private const val TrafficMapVisualMinShapeArea = 1.0

private const val TrafficMapVisualMaxPointsPerRing = 220
private const val TrafficMapVisualMinPointDeltaDegrees = 0.045
private const val HalfLongitudeDegrees = 180.0
private const val FullLongitudeDegrees = 360.0
