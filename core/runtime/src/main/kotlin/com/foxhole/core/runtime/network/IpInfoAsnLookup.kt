package com.foxhole.core.runtime.network

// Team Cymru ASN fallback: resolves an origin-AS provider name over DoH TXT records when the
// IP-info payload carried no ISP. Pure helpers — split from IpInfoRepository.kt.
internal fun lookupAsnProvider(ip: String): String? {
    val asn = lookupOriginAsn(ip) ?: return null
    val asName =
        runCatching {
            PublicDohDnsFallback
                .lookupTxt("AS$asn.asn.cymru.com")
                .asSequence()
                .mapNotNull(::parseCymruAsName)
                .firstOrNull()
        }.getOrNull()
    return asName?.takeIf(String::isNotBlank) ?: "AS$asn"
}

internal fun lookupOriginAsn(ip: String): String? {
    val originQuery =
        when {
            isIpv4Address(ip) -> ip.split('.').asReversed().joinToString(separator = ".") + ".origin.asn.cymru.com"
            else -> return null
        }
    return PublicDohDnsFallback
        .lookupTxt(originQuery)
        .asSequence()
        .mapNotNull(::parseCymruOriginAsn)
        .firstOrNull()
}

internal fun parseCymruOriginAsn(record: String): String? =
    record
        .split('|')
        .firstOrNull()
        ?.trim()
        ?.removePrefix("AS")
        ?.takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) }

internal fun parseCymruAsName(record: String): String? =
    record
        .split('|')
        .lastOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
