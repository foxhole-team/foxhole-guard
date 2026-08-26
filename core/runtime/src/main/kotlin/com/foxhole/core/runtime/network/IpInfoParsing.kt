package com.foxhole.core.runtime.network
import com.foxhole.core.model.IpInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.util.Locale

internal fun parseIpInfoResponse(
    body: String,
    json: Json,
    requireTorExitProof: Boolean = false,
): IpInfo {
    if (!requireTorExitProof) {
        parseCloudflareTraceResponse(body)?.let { return it }
    }
    val objectValue = json.parseToJsonElement(body).jsonObject
    require(objectValue.boolean("success") != false) {
        objectValue.string("message") ?: "ip info request failed"
    }

    val isTor = objectValue.boolean("IsTor")
    require(isTor != false) { "ip info response did not egress through tor" }
    if (requireTorExitProof) {
        require(isTor == true) { "ip info response did not prove tor egress" }
    }
    val ip =
        firstNonBlank(objectValue.string("ip"), objectValue.string("IP"))
            ?.takeIf(String::isNotBlank) ?: error("ip info response missing ip")
    val connection = objectValue.jsonObjectOrNull("connection")
    val asInfo = objectValue.jsonObjectOrNull("as")
    val asnInfo = objectValue.jsonObjectOrNull("asn")
    val company = objectValue.jsonObjectOrNull("company")
    val traits = objectValue.jsonObjectOrNull("traits")
    val country = objectValue.string("country")
    val explicitCountryName =
        firstNonBlank(
            objectValue.string("country_name"),
            objectValue.string("countryName"),
        )
    val countryCode =
        firstNonBlank(
            objectValue.string("country_code"),
            objectValue.string("country_iso"),
            objectValue.string("cc"),
            country?.takeIf { it.trim().length == ISO_COUNTRY_CODE_LENGTH },
            explicitCountryName?.takeIf { it.trim().length == ISO_COUNTRY_CODE_LENGTH },
        )?.toIsoCountryCodeOrNull()
    val countryName =
        explicitCountryName?.toCountryDisplayNameOrNull()
            ?: country?.trim()?.takeIf { it.isNotBlank() && it.length != ISO_COUNTRY_CODE_LENGTH }
            ?: countryCode?.let(::countryDisplayName)
    return IpInfo(
        ip = ip,
        ipv4 = ip.takeIf(::isIpv4Address),
        ipv6 = ip.takeIf(::isIpv6Address),
        countryCode = countryCode,
        countryName = countryName,
        city =
        firstNonBlank(
            objectValue.string("city"),
            objectValue.string("cityName"),
            objectValue.string("city_name"),
        ),
        isp =
        firstNonBlank(
            objectValue.string("isp"),
            objectValue.string("organization"),
            objectValue.string("organization_name"),
            objectValue.string("asn_org"),
            objectValue.string("as_org"),
            objectValue.string("org"),
            objectValue.string("provider"),
            connection?.string("isp"),
            connection?.string("org"),
            connection?.string("organization"),
            connection?.string("asn_org"),
            connection?.string("name"),
            asInfo?.string("name"),
            asInfo?.string("org"),
            asInfo?.string("organization"),
            asnInfo?.string("name"),
            asnInfo?.string("org"),
            asnInfo?.string("organization"),
            company?.string("name"),
            traits?.string("isp"),
            traits?.string("organization"),
        ),
        fetchedAt = System.currentTimeMillis(),
    )
}

private fun parseCloudflareTraceResponse(body: String): IpInfo? {
    val values =
        body
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }.toMap()
    val ip = values["ip"]?.takeIf(String::isNotBlank) ?: return null
    val countryCode = values["loc"].toIsoCountryCodeOrNull()
    return IpInfo(
        ip = ip,
        ipv4 = ip.takeIf(::isIpv4Address),
        ipv6 = ip.takeIf(::isIpv6Address),
        countryCode = countryCode,
        countryName = countryCode?.let(::countryDisplayName),
        city = null,
        isp = null,
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun String?.toIsoCountryCodeOrNull(): String? {
    val normalized = this?.trim()?.uppercase(Locale.US) ?: return null
    return normalized.takeIf { value ->
        value.length == ISO_COUNTRY_CODE_LENGTH && value.all { char -> char in 'A'..'Z' }
    }
}

internal fun countryDisplayName(countryCode: String): String? {
    val normalized = countryCode.toIsoCountryCodeOrNull() ?: return null
    return runCatching {
        Locale.Builder()
            .setRegion(normalized)
            .build()
            .getDisplayCountry(Locale.US)
    }.getOrNull()
        ?.takeIf { value -> value.isNotBlank() && !value.equals(normalized, ignoreCase = true) }
}

private fun String.toCountryDisplayNameOrNull(): String? {
    val value = trim()
    return when {
        value.isBlank() -> null
        value.length == ISO_COUNTRY_CODE_LENGTH -> value.toIsoCountryCodeOrNull()?.let(::countryDisplayName)
        else -> value
    }
}

fun mergeIpInfo(
    primary: IpInfo,
    ipv4: IpInfo?,
    ipv6: IpInfo?,
): IpInfo {
    mergeWithConflictingIpv4Geo(primary, ipv4, ipv6)?.let { return it }
    mergeWithForeignSilentIpv4(primary, ipv4, ipv6)?.let { return it }
    return primary.copy(
        ip = ipv4?.ipv4 ?: primary.ip,
        ipv4 = ipv4?.ipv4 ?: primary.ipv4,
        ipv6 = ipv6?.ipv6 ?: primary.ipv6,
        countryCode = primary.countryCode ?: ipv4?.countryCode ?: ipv6?.countryCode,
        countryName = primary.countryName ?: ipv4?.countryName ?: ipv6?.countryName,
        city = primary.city ?: ipv4?.city ?: ipv6?.city,
        isp = primary.isp ?: ipv4?.isp ?: ipv6?.isp,
    )
}

private fun mergeWithForeignSilentIpv4(
    primary: IpInfo,
    ipv4: IpInfo?,
    ipv6: IpInfo?,
): IpInfo? {
    val probeIpv4 = ipv4?.ipv4 ?: return null
    if (ipv4.countryCode != null) return null
    val primaryIpv4 = primary.ipv4 ?: primary.ip.takeIf(::isIpv4Address) ?: return null
    if (probeIpv4.equals(primaryIpv4, ignoreCase = true)) return null
    return primary.copy(
        ipv4 = primaryIpv4,
        ipv6 = ipv6?.ipv6 ?: primary.ipv6,
    )
}

private fun mergeWithConflictingIpv4Geo(
    primary: IpInfo,
    ipv4: IpInfo?,
    ipv6: IpInfo?,
): IpInfo? {
    val ipv4Address = ipv4?.ipv4 ?: return null
    val primaryCountry = primary.countryCode ?: return null
    val ipv4Country = ipv4.countryCode ?: return null
    if (primaryCountry.equals(ipv4Country, ignoreCase = true)) {
        return null
    }
    return primary.copy(
        ip = ipv4Address,
        ipv4 = ipv4Address,
        ipv6 = ipv6?.ipv6 ?: primary.ipv6,
        countryCode = ipv4Country,
        countryName = ipv4.countryName ?: countryDisplayName(ipv4Country),
        city = ipv4.city,
        isp = ipv4.isp,
    )
}

internal fun shouldStopIpInfoCandidateScan(
    mode: IpInfoFetchMode,
    info: IpInfo,
): Boolean =
    when (mode) {
        IpInfoFetchMode.FULL,
        IpInfoFetchMode.GEO_ENRICHMENT,
        -> info.hasFullIpInfoDetails()
        IpInfoFetchMode.ENTRY_QUICK -> info.hasEntryQuickIpInfoDetails()
    }

internal fun selectBetterFullIpInfoCandidate(
    current: IpInfo?,
    candidate: IpInfo,
): IpInfo =
    current?.takeIf { it.fullIpInfoQualityScore() >= candidate.fullIpInfoQualityScore() } ?: candidate

private fun IpInfo.hasFullIpInfoDetails(): Boolean =
    (countryName?.isNotBlank() == true || countryCode?.isNotBlank() == true) &&
        city?.isNotBlank() == true &&
        isp?.isNotBlank() == true

private fun IpInfo.hasEntryQuickIpInfoDetails(): Boolean =
    ip.isNotBlank() &&
        (countryName?.isNotBlank() == true || countryCode?.isNotBlank() == true)

fun IpInfo.fullIpInfoQualityScore(): Int =
    listOf(
        countryName?.takeIf(String::isNotBlank) ?: countryCode?.takeIf(String::isNotBlank),
        city?.takeIf(String::isNotBlank),
        isp?.takeIf(String::isNotBlank),
        ipv4?.takeIf(String::isNotBlank),
        ipv6?.takeIf(String::isNotBlank),
        ip.takeIf(String::isNotBlank),
    ).count { it != null }

private fun Map<String, JsonElement>.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun Map<String, JsonElement>.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun Map<String, JsonElement>.jsonObjectOrNull(key: String): Map<String, JsonElement>? =
    runCatching { this[key]?.jsonObject }.getOrNull()

private fun firstNonBlank(vararg values: String?): String? =
    values
        .asSequence()
        .mapNotNull { value -> value?.trim() }
        .firstOrNull { value -> value.isNotEmpty() }

internal fun isIpv4Address(value: String): Boolean = value.contains('.') && !value.contains(':')

private fun isIpv6Address(value: String): Boolean = value.contains(':')

private const val ISO_COUNTRY_CODE_LENGTH = 2

internal fun publicResolvedAddressesOrNetworkFallback(
    publicAddresses: Result<List<InetAddress>>,
    networkFallback: () -> List<InetAddress>,
): List<InetAddress> =
    publicAddresses.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching(networkFallback).getOrNull()?.takeIf { it.isNotEmpty() }
        ?: publicAddresses.getOrThrow()
