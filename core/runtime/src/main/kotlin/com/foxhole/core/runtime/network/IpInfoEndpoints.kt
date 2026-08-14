@file:Suppress("MatchingDeclarationName")

package com.foxhole.core.runtime.network

import com.foxhole.core.runtime.BuildConfig
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

// Endpoint resolution for the IP-info fetch: which hosts each mode races, in what order, and the
// per-family narrowing, plus the endpoint tables. Split from IpInfoRepository.kt.
internal enum class AddressFamilyPreference {
    ANY,
    IPV4,
    IPV6,
}

internal fun effectiveEndpoints(endpoint: String): List<String> {
    val primary = primaryEndpoint(endpoint)
    return buildList {
        add(primary)
        FALLBACK_ENDPOINTS.forEach { candidate ->
            if (!candidate.equals(primary, ignoreCase = true)) {
                add(candidate)
            }
        }
    }
}

internal fun quickEndpoints(endpoint: String): List<String> {
    val primary = primaryEndpoint(endpoint)
    return buildList {
        QUICK_FALLBACK_ENDPOINTS.forEach { candidate ->
            if (!candidate.equals(primary, ignoreCase = true) && candidate !in this) {
                add(candidate)
            }
            if (size == 1 && primary !in this) {
                add(primary)
            }
        }
        if (primary !in this) {
            add(primary)
        }
    }
}

internal fun geoEnrichmentEndpoints(endpoint: String): List<String> {
    val primary = primaryEndpoint(endpoint)
    return buildList {
        add(GEO_ENRICHMENT_PRIMARY_ENDPOINT)
        if (!primary.equals(GEO_ENRICHMENT_PRIMARY_ENDPOINT, ignoreCase = true)) {
            add(primary)
        }
        GEO_ENRICHMENT_FALLBACK_ENDPOINTS.forEach { candidate ->
            if (!candidate.equals(primary, ignoreCase = true) && candidate !in this) {
                add(candidate)
            }
        }
    }
}

internal fun prioritize(
    addresses: List<InetAddress>,
    preference: AddressFamilyPreference,
): List<InetAddress> =
    when (preference) {
        AddressFamilyPreference.ANY -> addresses
        AddressFamilyPreference.IPV4 -> addresses.filterIsInstance<Inet4Address>()
        AddressFamilyPreference.IPV6 -> addresses.filterIsInstance<Inet6Address>()
    }

internal fun primaryEndpoint(endpoint: String): String = endpoint.trim().ifBlank {
    BuildConfig.DEFAULT_IP_INFO_ENDPOINT
}

// ipapi.co resolves cities noticeably better than ipwhois.app did; the old provider (and
// its ipwho.is twin) stay in the race only as fallbacks.
internal const val GEO_ENRICHMENT_PRIMARY_ENDPOINT = "https://ipapi.co/json/"
internal val FALLBACK_ENDPOINTS =
    listOf(
        BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
        DNS_INDEPENDENT_IP_INFO_ENDPOINT,
        "https://1.0.0.1/cdn-cgi/trace",
        "https://cloudflare.com/cdn-cgi/trace",
        "https://ipwhois.app/json/",
        "https://ipinfo.io/json",
        "https://ifconfig.co/json",
        "https://api64.ipify.org?format=json",
        "https://api.ipify.org?format=json",
    )
internal val QUICK_FALLBACK_ENDPOINTS =
    listOf(
        DNS_INDEPENDENT_IP_INFO_ENDPOINT,
        "https://1.0.0.1/cdn-cgi/trace",
        "https://ipinfo.io/json",
    )

// Geo enrichment races these in parallel and takes the first success, so every entry must be
// city-capable — otherwise a fast country-only endpoint (e.g. the Cloudflare trace) wins the
// race and the city stays blank. Only reliable HTTPS providers that return a city are listed.
internal val GEO_ENRICHMENT_FALLBACK_ENDPOINTS =
    listOf(
        GEO_ENRICHMENT_PRIMARY_ENDPOINT,
        "https://ipinfo.io/json",
        "https://ipwho.is/",
        "https://ifconfig.co/json",
        "https://get.geojs.io/v1/ip/geo.json",
    )
internal val IPV4_FALLBACK_ENDPOINTS =
    listOf(
        DNS_INDEPENDENT_IP_INFO_ENDPOINT,
        "https://1.0.0.1/cdn-cgi/trace",
        "https://api.ipify.org?format=json",
    )
internal val IPV6_FALLBACK_ENDPOINTS =
    listOf(
        "https://api6.ipify.org?format=json",
    )
