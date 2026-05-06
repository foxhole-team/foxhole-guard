package com.foxhole.beta.core.traffic

import android.content.Context
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

internal class TorGeoIpCountryResolver(
    context: Context,
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
                    is Inet6Address -> ipv6Ranges.countryCodeFor(address.ipv6ToBigInteger())
                    else -> null
                }
            }

    private fun loadIpv4Ranges(): List<Ipv4CountryRange> =
        readFirstAsset(Ipv4GeoIpAssetCandidates)
            ?.lineSequence()
            ?.mapNotNull(::parseIpv4Range)
            ?.toList()
            .orEmpty()

    private fun loadIpv6Ranges(): List<Ipv6CountryRange> =
        readFirstAsset(Ipv6GeoIpAssetCandidates)
            ?.lineSequence()
            ?.mapNotNull(::parseIpv6Range)
            ?.toList()
            .orEmpty()

    private fun readFirstAsset(candidates: List<String>): String? =
        candidates.firstNotNullOfOrNull { assetPath ->
            runCatching {
                appContext.assets.open(assetPath).bufferedReader().use { reader -> reader.readText() }
            }.getOrNull()
        }

    internal companion object {
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
            line.geoIpPartsOrNull()?.let { parts ->
                val start = parts[0].toLongOrNull()
                val end = parts[1].toLongOrNull()
                val countryCode = parts[2].normalizedGeoIpCountryCode()
                if (start != null && end != null && countryCode != null) {
                    Ipv4CountryRange(start = start, end = end, countryCode = countryCode)
                } else {
                    null
                }
            }

        fun parseIpv6Range(line: String): Ipv6CountryRange? =
            line.geoIpPartsOrNull()?.let { parts ->
                val start = parts[0].ipv6LiteralToBigIntegerOrNull()
                val end = parts[1].ipv6LiteralToBigIntegerOrNull()
                val countryCode = parts[2].normalizedGeoIpCountryCode()
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

private fun List<Ipv4CountryRange>.countryCodeFor(value: Long): String? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high).ushr(1)
        val range = this[mid]
        when {
            value < range.start -> high = mid - 1
            value > range.end -> low = mid + 1
            else -> return range.countryCode
        }
    }
    return null
}

private fun List<Ipv6CountryRange>.countryCodeFor(value: BigInteger): String? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high).ushr(1)
        val range = this[mid]
        when {
            value < range.start -> high = mid - 1
            value > range.end -> low = mid + 1
            else -> return range.countryCode
        }
    }
    return null
}

private fun String.geoIpPartsOrNull(): List<String>? {
    if (isBlank() || startsWith("#")) {
        return null
    }
    val parts = split(',')
        .map(String::trim)
        .takeIf { value -> value.size == GEO_IP_PART_COUNT }
    return parts
}

private fun String.normalizedGeoIpCountryCode(): String? =
    uppercase(Locale.US)
        .takeIf { code -> code.length == COUNTRY_CODE_LENGTH && code.all { character -> character in 'A'..'Z' } }

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

private fun String.ipv6LiteralToBigIntegerOrNull(): BigInteger? =
    runCatching { InetAddress.getByName(this) as? Inet6Address }
        .getOrNull()
        ?.ipv6ToBigInteger()

private const val GEO_IP_PART_COUNT = 3
private const val COUNTRY_CODE_LENGTH = 2
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xffL
