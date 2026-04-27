package com.foxhole.beta.vpn

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object VpnDnsServerSelector {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun advertisedDnsServerAddress(
        configJson: String?,
        fallbackServerAddress: String,
    ): String =
        advertisedDnsServerAddresses(
            configJson = configJson,
            fallbackServerAddress = fallbackServerAddress,
        ).first()

    fun advertisedDnsServerAddresses(
        configJson: String?,
        fallbackServerAddress: String?,
    ): List<String> {
        val remoteDnsServers = remoteDnsServerAddresses(configJson).filter { it.isNotBlank() }.distinct()
        if (remoteDnsServers.isNotEmpty()) {
            return remoteDnsServers
        }
        val localTunDns = fallbackServerAddress?.takeIf { it.isNotBlank() }
        if (localTunDns != null) {
            return listOf(localTunDns)
        }
        return emptyList()
    }

    fun remoteDnsServerAddresses(configJson: String?): List<String> =
        runCatching {
            if (configJson == null) {
                return emptyList()
            }
            val root = json.parseToJsonElement(configJson).jsonObject
            val servers = root["dns"]?.jsonObject?.get("servers")?.jsonArray.orEmpty()
            servers
                .map { it.jsonObject }
                .filterNot { server ->
                    server["tag"]?.jsonPrimitive?.content == "dns-local" ||
                        server["type"]?.jsonPrimitive?.content == "local" ||
                        server["address"]?.jsonPrimitive?.content == "local"
                }.mapNotNull { server ->
                    server["server"]?.jsonPrimitive?.content?.let(::extractIpLiteral)
                        ?: server["address"]?.jsonPrimitive?.content?.let(::extractIpLiteral)
                }.distinct()
        }.getOrDefault(emptyList())

    private fun extractIpLiteral(endpoint: String): String? =
        extractHost(endpoint)?.takeIf(::isIpLiteral)

    private fun extractHost(endpoint: String): String? {
        val trimmed = endpoint.trim()
        if (trimmed.isBlank()) {
            return null
        }
        trimmed.toHttpUrlOrNull()?.host?.let(::normalizeHost)?.let { return it }
        if (trimmed.contains("://")) {
            runCatching { URI(trimmed).host }
                .getOrNull()
                ?.let(::normalizeHost)
                ?.let { return it }
        }
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            return trimmed.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
        }
        if (trimmed.count { it == ':' } == 1) {
            val host = trimmed.substringBefore(':')
            val port = trimmed.substringAfter(':').toIntOrNull()
            if (IPV4_REGEX.matches(host) && port in 1..65535) {
                return host
            }
        }
        return normalizeHost(trimmed)
    }

    private fun normalizeHost(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return if (trimmed.startsWith("[") && trimmed.contains("]")) {
            trimmed.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
        } else {
            trimmed
        }
    }

    private fun isIpLiteral(value: String): Boolean =
        IPV4_REGEX.matches(value) || isIpv6Literal(value)

    private fun isIpv6Literal(value: String): Boolean {
        if (!value.contains(':') || !IPV6_CANDIDATE_REGEX.matches(value)) {
            return false
        }
        val address = value.substringBefore('%')
        return runCatching { InetAddress.getByName(address) }
            .getOrNull() is Inet6Address
    }

    private val IPV4_REGEX =
        Regex(
            pattern = """^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""",
        )
    private val IPV6_CANDIDATE_REGEX = Regex("""^[0-9A-Fa-f:.]+(%[0-9A-Za-z_.-]+)?$""")
}
