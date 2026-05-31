package com.foxhole.beta.core.traffic

import android.content.Context
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal class TorGeoIpCountryResolver(
    context: Context,
    private val resolveIpv6: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val ipv4Ranges by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadIpv4Ranges() }
    private val ipv6Ranges by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadIpv6Ranges() }

    fun countryCodeForDestination(destination: String): String? =
        destination
            .hostFromConnectionDestination()
            ?.numericInetAddressOrNull()
            ?.let { address ->
                when (address) {
                    is Inet4Address -> ipv4Ranges.countryCodeFor(address.ipv4ToLong())
                    is Inet6Address -> if (resolveIpv6) {
                        val value = address.ipv6ToLongPair()
                        ipv6Ranges.countryCodeFor(value.high, value.low)
                    } else {
                        null
                    }
                    else -> null
                }
            }

    private fun loadIpv4Ranges(): Ipv4CountryRanges =
        synchronized(SharedGeoIpLock) {
            sharedIpv4Ranges ?: readFirstAssetLines(Ipv4GeoIpAssetCandidates) { lines ->
                lines.toIpv4CountryRanges()
            }
                .orEmptyIpv4Ranges()
                .also { ranges -> sharedIpv4Ranges = ranges }
        }

    private fun loadIpv6Ranges(): Ipv6CountryRanges =
        synchronized(SharedGeoIpLock) {
            sharedIpv6Ranges ?: readFirstAssetLines(Ipv6GeoIpAssetCandidates) { lines ->
                lines.toIpv6CountryRanges()
            }
                .orEmptyIpv6Ranges()
                .also { ranges -> sharedIpv6Ranges = ranges }
        }

    private fun <T> readFirstAssetLines(
        candidates: List<String>,
        transform: (Sequence<String>) -> T,
    ): T? =
        candidates.firstNotNullOfOrNull { assetPath ->
            runCatching {
                appContext.assets.open(assetPath).bufferedReader().use { reader ->
                    transform(reader.lineSequence())
                }
            }.getOrNull()
        }

    internal companion object {
        private val SharedGeoIpLock = Any()

        @Volatile
        private var sharedIpv4Ranges: Ipv4CountryRanges? = null

        @Volatile
        private var sharedIpv6Ranges: Ipv6CountryRanges? = null

        private val Ipv4GeoIpAssetCandidates =
            listOf(
                "tor/arm64-v8a/data/geoip",
                "tor/x86_64/data/geoip",
                "tor/armeabi-v7a/data/geoip",
                "tor/x86/data/geoip",
            )
        private val Ipv6GeoIpAssetCandidates =
            listOf(
                "tor/arm64-v8a/data/geoip6",
                "tor/x86_64/data/geoip6",
                "tor/armeabi-v7a/data/geoip6",
                "tor/x86/data/geoip6",
            )

        fun parseIpv4Range(line: String): Ipv4CountryRange? =
            line.geoIpSlicesOrNull()?.let { parts ->
                val start = line.parseLongFieldOrNull(parts.first)
                val end = line.parseLongFieldOrNull(parts.second)
                val countryCode = line.countryCodeFieldOrNull(parts.third)
                if (start != null && end != null && countryCode != null) {
                    Ipv4CountryRange(start = start, end = end, countryCode = countryCode)
                } else {
                    null
                }
            }

        fun parseIpv6Range(line: String): Ipv6CountryRange? =
            line.geoIpSlicesOrNull()?.let { parts ->
                val start = line.substring(parts.first.start, parts.first.end).ipv6LiteralToBigIntegerOrNull()
                val end = line.substring(parts.second.start, parts.second.end).ipv6LiteralToBigIntegerOrNull()
                val countryCode = line.countryCodeFieldOrNull(parts.third)
                if (start != null && end != null && countryCode != null) {
                    Ipv6CountryRange(start = start, end = end, countryCode = countryCode)
                } else {
                    null
                }
            }
    }
}

internal data class Ipv4CountryRange(
    val start: Long,
    val end: Long,
    val countryCode: String,
)

internal data class Ipv6CountryRange(
    val start: BigInteger,
    val end: BigInteger,
    val countryCode: String,
)

private class Ipv4CountryRanges(
    private val starts: LongArray,
    private val ends: LongArray,
    private val countryCodeIds: IntArray,
) {
    fun countryCodeFor(value: Long): String? {
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            when {
                value < starts[mid] -> high = mid - 1
                value > ends[mid] -> low = mid + 1
                else -> return countryCodeIds[mid].toCountryCode()
            }
        }
        return null
    }
}

private class Ipv6CountryRanges(
    private val startHighs: LongArray,
    private val startLows: LongArray,
    private val endHighs: LongArray,
    private val endLows: LongArray,
    private val countryCodeIds: IntArray,
) {
    fun countryCodeFor(
        high: Long,
        low: Long,
    ): String? {
        var searchLow = 0
        var searchHigh = startHighs.lastIndex
        while (searchLow <= searchHigh) {
            val mid = (searchLow + searchHigh).ushr(1)
            when {
                compareUnsigned128(high, low, startHighs[mid], startLows[mid]) < 0 -> searchHigh = mid - 1
                compareUnsigned128(high, low, endHighs[mid], endLows[mid]) > 0 -> searchLow = mid + 1
                else -> return countryCodeIds[mid].toCountryCode()
            }
        }
        return null
    }
}

private class Ipv4CountryRangesBuilder {
    private var starts = LongArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var ends = LongArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var countryCodeIds = IntArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var size = 0

    fun add(
        start: Long,
        end: Long,
        countryCodeId: Int,
    ) {
        ensureCapacity(size + 1)
        starts[size] = start
        ends[size] = end
        countryCodeIds[size] = countryCodeId
        size += 1
    }

    fun build(): Ipv4CountryRanges =
        Ipv4CountryRanges(
            starts = starts.copyOf(size),
            ends = ends.copyOf(size),
            countryCodeIds = countryCodeIds.copyOf(size),
        )

    private fun ensureCapacity(required: Int) {
        if (required <= starts.size) {
            return
        }
        val nextCapacity = maxOf(required, starts.size * 2)
        starts = starts.copyOf(nextCapacity)
        ends = ends.copyOf(nextCapacity)
        countryCodeIds = countryCodeIds.copyOf(nextCapacity)
    }
}

private class Ipv6CountryRangesBuilder {
    private var startHighs = LongArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var startLows = LongArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var endHighs = LongArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var endLows = LongArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var countryCodeIds = IntArray(INITIAL_GEO_IP_RANGE_CAPACITY)
    private var size = 0

    fun add(
        startHigh: Long,
        startLow: Long,
        endHigh: Long,
        endLow: Long,
        countryCodeId: Int,
    ) {
        ensureCapacity(size + 1)
        startHighs[size] = startHigh
        startLows[size] = startLow
        endHighs[size] = endHigh
        endLows[size] = endLow
        countryCodeIds[size] = countryCodeId
        size += 1
    }

    fun build(): Ipv6CountryRanges =
        Ipv6CountryRanges(
            startHighs = startHighs.copyOf(size),
            startLows = startLows.copyOf(size),
            endHighs = endHighs.copyOf(size),
            endLows = endLows.copyOf(size),
            countryCodeIds = countryCodeIds.copyOf(size),
        )

    private fun ensureCapacity(required: Int) {
        if (required <= startHighs.size) {
            return
        }
        val nextCapacity = maxOf(required, startHighs.size * 2)
        startHighs = startHighs.copyOf(nextCapacity)
        startLows = startLows.copyOf(nextCapacity)
        endHighs = endHighs.copyOf(nextCapacity)
        endLows = endLows.copyOf(nextCapacity)
        countryCodeIds = countryCodeIds.copyOf(nextCapacity)
    }
}

private fun Sequence<String>.toIpv4CountryRanges(): Ipv4CountryRanges {
    val builder = Ipv4CountryRangesBuilder()
    forEach { line ->
        val parts = line.geoIpSlicesOrNull() ?: return@forEach
        val start = line.parseLongFieldOrNull(parts.first)
        val end = line.parseLongFieldOrNull(parts.second)
        val countryCodeId = line.countryCodeIdFieldOrNull(parts.third)
        if (start != null && end != null && countryCodeId != null) {
            builder.add(start = start, end = end, countryCodeId = countryCodeId)
        }
    }
    return builder.build()
}

private fun Sequence<String>.toIpv6CountryRanges(): Ipv6CountryRanges {
    val builder = Ipv6CountryRangesBuilder()
    forEach { line ->
        val parts = line.geoIpSlicesOrNull() ?: return@forEach
        val start = line.substring(parts.first.start, parts.first.end).ipv6LiteralToLongPairOrNull()
        val end = line.substring(parts.second.start, parts.second.end).ipv6LiteralToLongPairOrNull()
        val countryCodeId = line.countryCodeIdFieldOrNull(parts.third)
        if (start != null && end != null && countryCodeId != null) {
            builder.add(
                startHigh = start.high,
                startLow = start.low,
                endHigh = end.high,
                endLow = end.low,
                countryCodeId = countryCodeId,
            )
        }
    }
    return builder.build()
}

private fun Ipv4CountryRanges?.orEmptyIpv4Ranges(): Ipv4CountryRanges =
    this ?: Ipv4CountryRanges(LongArray(0), LongArray(0), IntArray(0))

private fun Ipv6CountryRanges?.orEmptyIpv6Ranges(): Ipv6CountryRanges =
    this ?: Ipv6CountryRanges(LongArray(0), LongArray(0), LongArray(0), LongArray(0), IntArray(0))

private data class GeoIpLineSlices(
    val first: GeoIpFieldSlice,
    val second: GeoIpFieldSlice,
    val third: GeoIpFieldSlice,
)

private data class GeoIpFieldSlice(
    val start: Int,
    val end: Int,
)

@Suppress("ReturnCount")
private fun String.geoIpSlicesOrNull(): GeoIpLineSlices? {
    val contentStart = indexOfFirstNonWhitespace()
    if (contentStart < 0 || this[contentStart] == '#') {
        return null
    }
    val firstComma = indexOf(',', startIndex = contentStart)
    if (firstComma < 0) {
        return null
    }
    val secondComma = indexOf(',', startIndex = firstComma + 1)
    if (secondComma < 0 || indexOf(',', startIndex = secondComma + 1) >= 0) {
        return null
    }
    return GeoIpLineSlices(
        first = trimmedFieldOrNull(contentStart, firstComma) ?: return null,
        second = trimmedFieldOrNull(firstComma + 1, secondComma) ?: return null,
        third = trimmedFieldOrNull(secondComma + 1, length) ?: return null,
    )
}

private fun String.indexOfFirstNonWhitespace(): Int {
    for (index in indices) {
        if (!this[index].isWhitespace()) {
            return index
        }
    }
    return -1
}

private fun String.trimmedFieldOrNull(
    start: Int,
    end: Int,
): GeoIpFieldSlice? {
    var fieldStart = start
    var fieldEnd = end
    while (fieldStart < fieldEnd && this[fieldStart].isWhitespace()) {
        fieldStart += 1
    }
    while (fieldEnd > fieldStart && this[fieldEnd - 1].isWhitespace()) {
        fieldEnd -= 1
    }
    return if (fieldStart < fieldEnd) {
        GeoIpFieldSlice(fieldStart, fieldEnd)
    } else {
        null
    }
}

private fun String.parseLongFieldOrNull(field: GeoIpFieldSlice): Long? {
    var value = 0L
    var index = field.start
    while (index < field.end) {
        val digit = this[index] - '0'
        if (digit !in 0..9) {
            return null
        }
        value = value * 10L + digit
        index += 1
    }
    return value
}

private fun String.countryCodeFieldOrNull(field: GeoIpFieldSlice): String? {
    val id = countryCodeIdFieldOrNull(field) ?: return null
    return id.toCountryCode()
}

@Suppress("ReturnCount")
private fun String.countryCodeIdFieldOrNull(field: GeoIpFieldSlice): Int? {
    if (field.end - field.start != COUNTRY_CODE_LENGTH) {
        return null
    }
    val first = this[field.start].uppercaseAsciiOrNull() ?: return null
    val second = this[field.start + 1].uppercaseAsciiOrNull() ?: return null
    return ((first.code and COUNTRY_CODE_BYTE_MASK) shl BYTE_BITS) or (second.code and COUNTRY_CODE_BYTE_MASK)
}

private fun Char.uppercaseAsciiOrNull(): Char? =
    when (this) {
        in 'A'..'Z' -> this
        in 'a'..'z' -> this - ('a' - 'A')
        else -> null
    }

private fun String.hostFromConnectionDestination(): String? {
    val value = trim().removePrefix("/")
    return value.takeIf(String::isNotBlank)?.let { destination ->
        if (destination.startsWith("[")) {
            destination.substringAfter("[").substringBefore("]").takeIf(String::isNotBlank)
        } else {
            val colonCount = destination.count { character -> character == ':' }
            when {
                colonCount == 0 -> destination
                colonCount == 1 -> destination.substringBefore(":").takeIf(String::isNotBlank)
                else -> destination.substringBefore("%").takeIf(String::isNotBlank)
            }
        }
    }
}

private fun String.numericInetAddressOrNull(): InetAddress? {
    if (!looksLikeNumericIpAddress()) {
        return null
    }
    return runCatching { InetAddress.getByName(this) }.getOrNull()
}

private fun String.looksLikeNumericIpAddress(): Boolean =
    all { character -> character.isDigit() || character == '.' } ||
        any { character -> character == ':' } &&
        all { character -> character.isDigit() || character in 'a'..'f' || character in 'A'..'F' || character == ':' || character == '.' }

private fun Inet4Address.ipv4ToLong(): Long =
    address.fold(0L) { value, byte -> (value shl BYTE_BITS) or (byte.toLong() and BYTE_MASK) }

private fun Inet6Address.ipv6ToBigInteger(): BigInteger = BigInteger(1, address)

private data class Ipv6LongPair(
    val high: Long,
    val low: Long,
)

private fun Inet6Address.ipv6ToLongPair(): Ipv6LongPair =
    address.toIpv6LongPair()

private fun String.ipv6LiteralToLongPairOrNull(): Ipv6LongPair? =
    runCatching { InetAddress.getByName(this) as? Inet6Address }
        .getOrNull()
        ?.ipv6ToLongPair()

private fun ByteArray.toIpv6LongPair(): Ipv6LongPair =
    Ipv6LongPair(
        high = toLongAt(0),
        low = toLongAt(8),
    )

private fun ByteArray.toLongAt(offset: Int): Long {
    var value = 0L
    for (index in offset until offset + IPV6_LONG_BYTES) {
        value = (value shl BYTE_BITS) or (this[index].toLong() and BYTE_MASK)
    }
    return value
}

private fun compareUnsigned128(
    leftHigh: Long,
    leftLow: Long,
    rightHigh: Long,
    rightLow: Long,
): Int {
    val high = java.lang.Long.compareUnsigned(leftHigh, rightHigh)
    return if (high != 0) {
        high
    } else {
        java.lang.Long.compareUnsigned(leftLow, rightLow)
    }
}

private fun String.ipv6LiteralToBigIntegerOrNull(): BigInteger? =
    runCatching { InetAddress.getByName(this) as? Inet6Address }
        .getOrNull()
        ?.ipv6ToBigInteger()

private fun Int.toCountryCode(): String =
    String(
        charArrayOf(
            ((this ushr BYTE_BITS) and COUNTRY_CODE_BYTE_MASK).toChar(),
            (this and COUNTRY_CODE_BYTE_MASK).toChar(),
        ),
    )

private const val COUNTRY_CODE_LENGTH = 2
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xffL
private const val COUNTRY_CODE_BYTE_MASK = 0xff
private const val IPV6_LONG_BYTES = 8
private const val INITIAL_GEO_IP_RANGE_CAPACITY = 4096
