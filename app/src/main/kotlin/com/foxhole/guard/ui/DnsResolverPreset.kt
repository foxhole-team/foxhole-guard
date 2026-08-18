package com.foxhole.guard.ui

import com.foxhole.core.model.SecureDnsMode

internal enum class DnsResolverPreset(
    val label: String,
    private val plainHost: String,
    private val dohHost: String,
    private val dotHost: String,
) {
    CLOUDFLARE("Cloudflare", "1.1.1.1", "cloudflare-dns.com", "one.one.one.one"),
    GOOGLE("Google", "8.8.8.8", "dns.google", "dns.google"),
    QUAD9("Quad9", "9.9.9.9", "dns.quad9.net", "dns.quad9.net"),
    OPENDNS("OpenDNS", "208.67.222.222", "doh.opendns.com", "dns.opendns.com"),
    CUSTOM("", "", "", ""),
    ;

    fun hostFor(mode: SecureDnsMode): String = when (mode) {
        SecureDnsMode.PLAIN -> plainHost
        SecureDnsMode.DOH -> dohHost
        SecureDnsMode.DOT -> dotHost
    }

    fun matches(server: String): Boolean = this != CUSTOM && server in setOf(plainHost, dohHost, dotHost)
}

internal fun dnsResolverPresetFor(server: String): DnsResolverPreset? =
    DnsResolverPreset.entries.firstOrNull { preset -> preset.matches(server.trim()) }

internal fun defaultDnsServerFor(mode: SecureDnsMode): String =
    DnsResolverPreset.CLOUDFLARE.hostFor(mode)

internal fun dnsServerHostForGeo(server: String): String? {
    val trimmed = server.trim()
    if (trimmed.isEmpty()) return null
    val withoutScheme = trimmed.substringAfter("://", trimmed)
    return withoutScheme.substringBefore('/').trim().takeIf(String::isNotBlank)
}
