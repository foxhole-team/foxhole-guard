package com.foxhole.guard.traffic

import android.content.Context
import android.os.Trace
import androidx.compose.runtime.Immutable
import java.io.InputStream
import java.util.Locale
import kotlin.math.pow

internal const val TRAFFIC_MAP_COUNTRY_SHAPES_ASSET = "maps/ne_50m_admin_0_countries_preprocessed.json"

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

    fun registry(): TrafficMapCountryRegistry {
        return loadResult().registry
    }

    internal fun loadResult(): TrafficMapCountryRegistryLoadResult {
        return TrafficMapCountryRegistryAssetCache.load {
            appContext.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET)
        }
    }
}

internal enum class TrafficMapCountryRegistryLoadStatus {
    LOADED,
    FALLBACK_MISSING_OR_UNREADABLE,
    FALLBACK_EMPTY_OR_MALFORMED,
}

internal data class TrafficMapCountryRegistryLoadResult(
    val registry: TrafficMapCountryRegistry,
    val status: TrafficMapCountryRegistryLoadStatus,
)

internal fun loadTrafficMapCountryRegistry(
    openAsset: () -> InputStream,
    parser: TrafficMapCountryShapeAssetParser = TrafficMapCountryShapeAssetParser(),
    locale: Locale = Locale.getDefault(),
): TrafficMapCountryRegistryLoadResult =
    runCatching {
        val shapes = openAsset().use { inputStream -> parser.parse(inputStream) }
        if (shapes.isEmpty()) {
            TrafficMapCountryRegistryLoadResult(
                registry = TrafficMapCountryRegistry.legacyFallback(),
                status = TrafficMapCountryRegistryLoadStatus.FALLBACK_EMPTY_OR_MALFORMED,
            )
        } else {
            TrafficMapCountryRegistryLoadResult(
                registry = TrafficMapCountryRegistry.fromShapes(shapes, locale),
                status = TrafficMapCountryRegistryLoadStatus.LOADED,
            )
        }
    }.getOrElse {
        TrafficMapCountryRegistryLoadResult(
            registry = TrafficMapCountryRegistry.legacyFallback(),
            status = TrafficMapCountryRegistryLoadStatus.FALLBACK_MISSING_OR_UNREADABLE,
        )
    }

private object TrafficMapCountryRegistryAssetCache {
    @Volatile
    private var cachedLoadResult: TrafficMapCountryRegistryLoadResult? = null

    fun load(openAsset: () -> InputStream): TrafficMapCountryRegistryLoadResult {
        cachedLoadResult?.let { result -> return result }
        return synchronized(this) {
            cachedLoadResult?.let { result -> return@synchronized result }
            loadTrafficMapCountryRegistry(openAsset).also { result ->
                cachedLoadResult = result
            }
        }
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
        var found = countriesIndex >= 0
        if (found) {
            index = countriesIndex + "\"countries\"".length
            skipWhitespace()
            found = consume(':')
        }
        if (found) {
            skipWhitespace()
            found = consume('[')
        }
        return found
    }

    private fun parseCountry(): TrafficMapCountryShape? {
        var shape: TrafficMapCountryShape? = null
        if (consume('{')) {
            shape = parseCountryObject()
        } else {
            skipValue()
        }
        return shape
    }

    private fun parseCountryObject(): TrafficMapCountryShape? {
        val fields = CountryObjectFields()
        var closed = false
        while (!fields.malformed && !closed && index < raw.length) {
            skipWhitespace()
            if (consume('}')) {
                closed = true
            } else {
                parseCountryObjectEntry(fields)
            }
        }
        val parsedShape = if (fields.malformed) {
            null
        } else {
            fields.countryCode?.let { code -> trafficMapCountryShapeOrNull(countryCode = code, rings = fields.rings) }
        }
        return parsedShape?.takeIf { shape -> shape.rings.isNotEmpty() }
    }

    private fun parseCountryObjectEntry(fields: CountryObjectFields) {
        val key = parseStringOrNull()
        skipWhitespace()
        if (key == null || !consume(':')) {
            fields.malformed = true
            return
        }
        skipWhitespace()
        when (key) {
            "code" -> fields.countryCode = parseStringOrNull()?.trim()?.uppercase(Locale.US)
            "rings" -> fields.rings = parseRings()
            else -> skipValue()
        }
        skipWhitespace()
        consume(',')
    }

    private class CountryObjectFields {
        var countryCode: String? = null
        var rings: List<List<TrafficMapGeoPoint>> = emptyList()
        var malformed = false
    }

    private fun trafficMapCountryShapeOrNull(
        countryCode: String,
        rings: List<List<TrafficMapGeoPoint>> = emptyList(),
    ): TrafficMapCountryShape? =
        countryCode
            .takeIf(TrafficMapCountryShapeAssetParser::isIsoCountryCode)
            ?.let { code -> TrafficMapCountryShape(countryCode = code, rings = rings) }

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
        var hasNext = !consume(']')
        if (hasNext && peek() == ',') {
            index += 1
            skipWhitespace()
            hasNext = !consume(']')
        }
        return hasNext && index < raw.length
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
        val sign = consumeNumberSign()
        val mantissa = parseNumberMantissa()
        if (!mantissa.hasDigit) {
            index = start
            return null
        }
        return sign * mantissa.value * parseExponentMultiplier()
    }

    private fun consumeNumberSign(): Double =
        when (raw.getOrNull(index)) {
            '-' -> {
                index += 1
                -1.0
            }
            '+' -> {
                index += 1
                1.0
            }
            else -> 1.0
        }

    private fun parseNumberMantissa(): ParsedNumberMantissa {
        var parsed = ParsedNumberMantissa(value = 0.0, hasDigit = false)
        parsed = parseWholeNumberDigits(parsed)
        if (consume('.')) {
            parsed = parseFractionDigits(parsed)
        }
        return parsed
    }

    private fun parseWholeNumberDigits(initial: ParsedNumberMantissa): ParsedNumberMantissa {
        var value = initial.value
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
        return ParsedNumberMantissa(value = value, hasDigit = initial.hasDigit || hasDigit)
    }

    private fun parseFractionDigits(initial: ParsedNumberMantissa): ParsedNumberMantissa {
        var value = initial.value
        var hasDigit = false
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
        return ParsedNumberMantissa(value = value, hasDigit = initial.hasDigit || hasDigit)
    }

    private fun parseExponentMultiplier(): Double {
        var multiplier = 1.0
        val exponentMarker = raw.getOrNull(index)
        if (exponentMarker == 'e' || exponentMarker == 'E') {
            index += 1
            val exponentSign = consumeExponentSign()
            val exponent = parseExponentDigits()
            if (exponent.hasDigit) {
                multiplier = 10.0.pow(exponentSign * exponent.value)
            }
        }
        return multiplier
    }

    private fun consumeExponentSign(): Int {
        var sign = 1
        if (raw.getOrNull(index) == '-' || raw.getOrNull(index) == '+') {
            if (raw[index] == '-') {
                sign = -1
            }
            index += 1
        }
        return sign
    }

    private fun parseExponentDigits(): ParsedExponent {
        var value = 0
        var hasDigit = false
        while (index < raw.length) {
            val digit = raw[index].fastDigitOrMinusOne()
            if (digit < 0) {
                break
            }
            hasDigit = true
            value = (value * 10) + digit
            index += 1
        }
        return ParsedExponent(value = value, hasDigit = hasDigit)
    }

    private fun parseStringOrNull(): String? {
        var result: String? = null
        if (consume('"')) {
            result = parseStringBodyOrNull()
        }
        return result
    }

    private fun parseStringBodyOrNull(): String? {
        val builder = StringBuilder()
        var closed = false
        var valid = true
        while (valid && !closed && index < raw.length) {
            when (val character = raw[index++]) {
                '"' -> closed = true
                '\\' -> valid = appendEscapedCharacter(builder)
                else -> builder.append(character)
            }
        }
        return builder.toString().takeIf { closed && valid }
    }

    private fun appendEscapedCharacter(builder: StringBuilder): Boolean {
        val escaped = parseEscapedCharacterOrNull()
        if (escaped != null) {
            builder.append(escaped)
        }
        return escaped != null
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
        var character: Char? = null
        if (index + UnicodeEscapeLength <= raw.length) {
            raw.substring(index, index + UnicodeEscapeLength)
                .toIntOrNull(16)
                ?.let { value ->
                    index += UnicodeEscapeLength
                    character = value.toChar()
                }
        }
        return character
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

private data class ParsedNumberMantissa(
    val value: Double,
    val hasDigit: Boolean,
)

private data class ParsedExponent(
    val value: Int,
    val hasDigit: Boolean,
)

private fun Char.fastDigitOrMinusOne(): Int {
    val digit = code - '0'.code
    return if (digit in 0..9) digit else -1
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

private const val MAX_TRACE_SECTION_NAME_LENGTH = 127
