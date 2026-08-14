package com.foxhole.core.network

import com.foxhole.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import java.security.MessageDigest
import java.util.Locale

data class NetworkFingerprint(
    val key: String,
    val schema: Int = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
    val transport: String,
    val isMetered: Boolean = false,
    val isRoaming: Boolean = false,
    val privateDnsActive: Boolean = false,
    val upstreamValidated: Boolean = false,
)

data class NetworkFingerprintSource(
    val transport: String,
    val isMetered: Boolean,
    val isRoaming: Boolean,
    val isValidated: Boolean = false,
    val interfaceName: String? = null,
    val dnsServers: List<String> = emptyList(),
    val routeGateways: List<String> = emptyList(),
    val privateDnsServerName: String? = null,
)

fun buildNetworkFingerprint(source: NetworkFingerprintSource): NetworkFingerprint? {
    val normalizedTransport = source.transport.normalizedNetworkToken(defaultValue = "other") ?: "other"
    val normalizedRouteGateways = source.routeGateways.normalizedNetworkTokens()
    val normalizedPrivateDns = source.privateDnsServerName?.normalizedNetworkToken()
    if (normalizedRouteGateways.isEmpty() && normalizedPrivateDns == null) {
        return null
    }
    val gatewayHash =
        normalizedRouteGateways
            .takeIf(List<String>::isNotEmpty)
            ?.let { gateways -> sha256Hex("$FINGERPRINT_GATEWAY_SALT|${gateways.joinToString(separator = ",")}") }
            ?: "none"
    val fingerprintMaterial =
        buildList {
            add("schema=$NETWORK_FINGERPRINT_SCHEMA_CURRENT")
            add("transport=$normalizedTransport")
            add("private_dns_enabled=${normalizedPrivateDns != null}")
            add("gateway_hash=$gatewayHash")
        }.joinToString(separator = "|")
    return NetworkFingerprint(
        key = sha256Hex(fingerprintMaterial),
        schema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
        transport = normalizedTransport,
        isMetered = source.isMetered,
        isRoaming = source.isRoaming,
        privateDnsActive = normalizedPrivateDns != null,
        upstreamValidated = source.isValidated,
    )
}

private fun List<String>.normalizedNetworkTokens(): List<String> =
    mapNotNull(String::normalizedNetworkToken)
        .distinct()
        .sorted()

private fun String.normalizedNetworkToken(defaultValue: String? = null): String? =
    trim()
        .lowercase(Locale.ROOT)
        .takeIf(String::isNotBlank)
        ?: defaultValue

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val FINGERPRINT_GATEWAY_SALT = "foxhole-network-fingerprint-v2"
