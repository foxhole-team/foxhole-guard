package com.foxhole.guard.core.settings

import com.foxhole.core.model.DEFAULT_DNS_FILTER_UPDATE_URL
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.network.ensurePublicHttpsUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun DnsSettings.runtimeRequiresExplicitTunnel(): Boolean =
    filteringEnabled ||
        useVpnProviderDns ||
        dnsThroughVpn ||
        blockOutsideTunnel ||
        interceptDnsRequests ||
        replaceSystemDns ||
        appBypassPackages.isNotEmpty() ||
        domainBypassRules.isNotEmpty()

internal fun normalizedDnsServer(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        return DEFAULT_DNS_SERVER
    }
    val url = trimmed.toHttpUrlOrNull()
    return url?.host ?: trimmed
        .removePrefix("https://")
        .removePrefix("tls://")
        // udp:// and quic:// carry no host for toHttpUrlOrNull, so strip them too — otherwise the
        // scheme survives into the resolver's server field and is misread as a hostname.
        .removePrefix("udp://")
        .removePrefix("quic://")
        .removeSuffix("/dns-query")
        .trim()
        .ifBlank { DEFAULT_DNS_SERVER }
}

internal fun normalizeDnsFilterUpdateUrl(value: String): String =
    runCatching {
        val url = value.trim().ensurePublicHttpsUrl()
        when {
            url.isOfficialFoxholeDnsRepositoryUrl() -> DEFAULT_DNS_FILTER_UPDATE_URL
            url.isOfficialFoxholeDnsPagesDirectoryUrl() -> DEFAULT_DNS_FILTER_UPDATE_URL
            url.pathSegments.lastOrNull().orEmpty().endsWith(".json", ignoreCase = true) -> url.toString()
            else -> url.newBuilder().addPathSegment("manifest.json").build().toString()
        }
    }.getOrDefault(DEFAULT_DNS_FILTER_UPDATE_URL)

private fun HttpUrl.isOfficialFoxholeDnsRepositoryUrl(): Boolean {
    val normalizedPath = encodedPath.trim('/').removeSuffix(".git")
    return host.equals("github.com", ignoreCase = true) &&
        normalizedPath.equals("foxhole-team/foxhole-dns", ignoreCase = true)
}

private fun HttpUrl.isOfficialFoxholeDnsPagesDirectoryUrl(): Boolean {
    val normalizedPath = encodedPath.trim('/').removeSuffix("/")
    return host.equals("foxhole-team.github.io", ignoreCase = true) &&
        normalizedPath.equals("foxhole-dns", ignoreCase = true)
}

private const val DEFAULT_DNS_SERVER = "1.1.1.1"
