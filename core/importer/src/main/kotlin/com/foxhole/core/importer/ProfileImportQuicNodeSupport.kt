package com.foxhole.core.importer

import com.foxhole.core.importer.ProfileImportCoreSupport.ProxyNode
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// QUIC-family share-URI parsers (TUIC, AnyTLS), split out of ProfileImportNodeSupport so that class
// stays under the size budget. They lean on the same internal URI/TLS/tag helpers as the other
// parsers, reached here as extensions on the receiver.

internal fun ProfileImportNodeSupport.parseTuicUri(
    value: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ProxyNode {
    val uri = parseLenientUri(value)
    val query = parseQueryParameters(uri.rawQuery)
    val host = uri.host ?: error("missing host")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = uri.port.takeIf { it > 0 } ?: DEFAULT_QUIC_PORT
    val displayName = displayNameFromUri(uri, host)
    val userInfo = uri.userInfo ?: error("missing tuic credentials")
    val uuid = userInfo.substringBefore(':')
    val password = userInfo.substringAfter(':', missingDelimiterValue = "")
    require(uuid.isNotBlank()) { "missing tuic uuid" }
    val outbound =
        buildJsonObject {
            put("type", "tuic")
            put("tag", tagFor(displayName, "tuic", host, port, userInfo))
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            password.takeIf(String::isNotBlank)?.let { put("password", it) }
            query.firstValue("congestion_control", "congestion")
                ?.takeIf { it in TUIC_CONGESTION_CONTROLS }
                ?.let { put("congestion_control", it) }
            query.firstValue("udp_relay_mode", "udpRelayMode", "udp_relay")
                ?.takeIf { it in TUIC_UDP_RELAY_MODES }
                ?.let { put("udp_relay_mode", it) }
            buildTls(query, host, tlsDefault = true, allowInsecureTls = allowInsecureTls)?.let { put("tls", it) }
        }
    return ProxyNode(
        displayName = displayName,
        protocolHint = ProtocolHint.TUIC,
        outbound = outbound,
        subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
    )
}

internal fun ProfileImportNodeSupport.parseAnytlsUri(
    value: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ProxyNode {
    val uri = parseLenientUri(value)
    val query = parseQueryParameters(uri.rawQuery)
    val host = uri.host ?: error("missing host")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = uri.port.takeIf { it > 0 } ?: DEFAULT_QUIC_PORT
    val displayName = displayNameFromUri(uri, host)
    val password = uri.userInfo ?: error("missing anytls password")
    require(password.isNotBlank()) { "missing anytls password" }
    val outbound =
        buildJsonObject {
            put("type", "anytls")
            put("tag", tagFor(displayName, "anytls", host, port, password))
            put("server", host)
            put("server_port", port)
            put("password", password)
            buildTls(query, host, tlsDefault = true, allowInsecureTls = allowInsecureTls)?.let { put("tls", it) }
        }
    return ProxyNode(
        displayName = displayName,
        protocolHint = ProtocolHint.ANYTLS,
        outbound = outbound,
        subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
    )
}

private const val DEFAULT_QUIC_PORT = 443
private val TUIC_CONGESTION_CONTROLS = setOf("cubic", "new_reno", "bbr")
private val TUIC_UDP_RELAY_MODES = setOf("native", "quic")
