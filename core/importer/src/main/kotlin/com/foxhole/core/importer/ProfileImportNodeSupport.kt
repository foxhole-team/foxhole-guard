package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryReport
import com.foxhole.core.model.SubscriptionEntryStatus
import com.foxhole.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

internal open class ProfileImportNodeSupport(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportConfigBuildSupport(json, remoteHostResolver) {
    internal fun parseNodeLines(
        raw: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ParsedNodeLines =
        parseNodeLinesWithCapabilities(
            raw = raw,
            normalize = ::normalizeInput,
            parseSupported = { line ->
                parseSingleNode(line, allowPrivateOutboundHosts, allowInsecureTls)
            },
        )

    internal fun parseSingleNode(
        value: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode {
        val scheme = value.substringBefore("://").lowercase()
        return when (scheme) {
            "vless" -> parseVlessUri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "trojan" -> parseTrojanUri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "naive", "naive+https" -> parseNaiveUri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "ss", "outline" -> parseShadowsocksUri(value, allowPrivateOutboundHosts)
            "vmess" -> parseVmessUri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "hy2", "hysteria2" -> parseHysteria2Uri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "tuic" -> parseTuicUri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "anytls" -> parseAnytlsUri(value, allowPrivateOutboundHosts, allowInsecureTls)
            "wireguard", "wg", "awg", "amnezia", "amneziawg" ->
                parseWireGuardUri(value, allowPrivateOutboundHosts)

            else -> error("unsupported share uri")
        }
    }

    internal fun parseVlessUri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode {
        val uri = parseLenientUri(value)
        val query = parseQueryParameters(uri.rawQuery)
        val host = uri.host ?: error("missing host")
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = uri.port.takeIf { it > 0 } ?: 443
        val displayName = displayNameFromUri(uri, host)
        val encryption = readClientVlessEncryption(query)
        val outbound =
            buildJsonObject {
                put("type", "vless")
                put("tag", tagFor(displayName, "vless", host, port, uri.userInfo))
                put("server", host)
                put("server_port", port)
                put("uuid", uri.userInfo?.takeIf(String::isNotBlank) ?: error("missing uuid"))
                encryption?.let { put("encryption", it) }
                query["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                buildVlessNetwork(query)?.let { put("network", it) }
                query.firstValue(
                    "packetEncoding",
                    "packet_encoding",
                    "packet-encoding"
                )?.let { put("packet_encoding", it) }
                buildTls(query, host, allowInsecureTls = allowInsecureTls)?.let { put("tls", it) }
                buildTransport(query)?.let { put("transport", it) }
            }
        return ProxyNode(
            displayName = displayName,
            protocolHint = ProtocolHint.VLESS,
            outbound = outbound,
            subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
        )
    }

    internal fun parseTrojanUri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode {
        val uri = parseLenientUri(value)
        val query = parseQueryParameters(uri.rawQuery)
        val host = uri.host ?: error("missing host")
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = uri.port.takeIf { it > 0 } ?: 443
        val displayName = displayNameFromUri(uri, host)
        val outbound =
            buildJsonObject {
                put("type", "trojan")
                put("tag", tagFor(displayName, "trojan", host, port, uri.userInfo))
                put("server", host)
                put("server_port", port)
                put("password", uri.userInfo?.takeIf(String::isNotBlank) ?: error("missing password"))
                buildTls(
                    query = query,
                    host = host,
                    tlsDefault = !query["security"].equals("none", ignoreCase = true),
                    allowInsecureTls = allowInsecureTls,
                )?.let { put("tls", it) }
                buildTransport(query)?.let { put("transport", it) }
            }
        return ProxyNode(
            displayName = displayName,
            protocolHint = ProtocolHint.TROJAN,
            outbound = outbound,
            subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
        )
    }

    internal fun parseNaiveUri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode {
        val uri = parseLenientUri(value)
        val query = parseQueryParameters(uri.rawQuery)
        val host = uri.host ?: error("missing host")
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = uri.port.takeIf { it > 0 } ?: 443
        val displayName = displayNameFromUri(uri, host)
        val userInfo = uri.rawUserInfo?.let(::uriDecode).orEmpty()
        val username = userInfo.substringBefore(':', missingDelimiterValue = "")
        val password = userInfo.substringAfter(':', missingDelimiterValue = "")
        require(username.isNotBlank() || password.isNotBlank()) { "naive credentials are missing" }
        val outbound =
            buildJsonObject {
                put("type", "naive")
                put("tag", tagFor(displayName, "naive", host, port, "$username:$password"))
                put("server", host)
                put("server_port", port)
                username.takeIf(String::isNotBlank)?.let { put("username", it) }
                password.takeIf(String::isNotBlank)?.let { put("password", it) }
                query.booleanValue("quic")?.let { put("quic", it) }
                query.booleanValue("udp_over_tcp", "udpOverTcp", "uot")?.let { put("udp_over_tcp", it) }
                query.firstValue("quic_congestion_control", "quicCongestionControl", "congestion")
                    ?.takeIf { it in setOf("bbr", "bbr2", "cubic", "reno") }
                    ?.let { put("quic_congestion_control", it) }
                put(
                    "tls",
                    buildJsonObject {
                        put("enabled", true)
                        put("server_name", query["sni"]?.takeIf(String::isNotBlank) ?: host)
                        val insecureTls =
                            listOfNotNull(query.firstValue("allowInsecure", "allow_insecure"), query["insecure"])
                                .firstNotNullOfOrNull { item ->
                                    item.toFlexibleBoolean()
                                } ?: false
                        require(allowInsecureTls || !insecureTls) { "INSECURE TLS is not allowed" }
                        require(!insecureTls) { "insecure is not supported on naive outbound" }
                        query.booleanValue("ech")?.let { echEnabled ->
                            putJsonObject("ech") {
                                put("enabled", echEnabled)
                            }
                        }
                    },
                )
            }
        return ProxyNode(
            displayName = displayName,
            protocolHint = ProtocolHint.NAIVE,
            outbound = outbound,
            subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
        )
    }

    internal fun parseShadowsocksUri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
    ): ProxyNode {
        val normalizedValue =
            if (value.startsWith("outline://", ignoreCase = true)) {
                decodeOutlineAccessKey(value)
            } else {
                value
            }
        val payload = normalizedValue.removePrefix("ss://")
        val fragment = payload.substringAfter('#', missingDelimiterValue = "")
        val base = payload.substringBefore('#')
        val decodedPart: String
        val hostPortPart: String
        if (base.contains("@")) {
            val credentials = uriDecode(base.substringBefore('@'))
            decodedPart = decodeBase64IfNeeded(credentials)
            hostPortPart = base.substringAfter('@').substringBefore('?').removeSuffix("/")
        } else {
            val decoded = decodeBase64Url(base.substringBefore('?'))
            val credentialsAndHost = decoded.substringBeforeLast('@')
            decodedPart = credentialsAndHost.substringBeforeLast('@')
            hostPortPart = decoded.substringAfterLast('@')
        }
        val method = decodedPart.substringBefore(':')
        val password = decodedPart.substringAfter(':')
        val endpoint = parseRemoteEndpoint(hostPortPart)
        val host = endpoint.host
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = endpoint.port ?: error("shadowsocks port is missing")
        val queryPart = base.substringAfter('?', missingDelimiterValue = "")
        val carrier =
            sip003Carrier(
                queryPart,
                parseQueryParameters(queryPart),
                ::uriDecode,
                { item -> item.toFlexibleBoolean() },
            )
        val udpEnabled = parseQueryParameters(queryPart)["udp"]?.let {
            it.toFlexibleBoolean() ?: error("invalid shadowsocks UDP option")
        } ?: (carrier == null)
        val displayName = fragment.takeIf { it.isNotBlank() }?.let(::uriDecode) ?: host
        val hint =
            if (value.contains("outline", ignoreCase = true)) {
                ProtocolHint.OUTLINE
            } else {
                ProtocolHint.SHADOWSOCKS
            }
        val outbound =
            buildJsonObject {
                put("type", "shadowsocks")
                put("tag", tagFor(displayName, "shadowsocks", host, port, "$method:$password"))
                put("server", host)
                put("server_port", port)
                put("method", method)
                put("password", password)
                if (!udpEnabled) put("network", "tcp")
                carrier?.let { (transport, tls) ->
                    put("transport", transport)
                    put("tls", tls)
                }
            }
        return ProxyNode(displayName, hint, outbound)
    }

    internal fun buildVlessNetwork(query: Map<String, String>): String? =
        query["network"]?.trim()?.lowercase()?.takeIf { it in setOf("tcp", "udp") }

    internal fun decodeOutlineAccessKey(value: String): String {
        val payload = value.removePrefix("outline://")
        val base = payload.substringBefore('#')
        val query = base.substringAfter('?', missingDelimiterValue = "")
        val encoded = base.substringBefore('?')
        val decoded = decodeBase64Url(encoded)
        if (query.isBlank()) {
            return decoded
        }
        return if (decoded.contains('?')) {
            "$decoded&$query"
        } else {
            "$decoded?$query"
        }
    }

    internal fun parseVmessUri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode {
        val decoded = decodeBase64Url(value.removePrefix("vmess://"))
        val objectValue = json.parseToJsonElement(decoded).jsonObject
        val host = objectValue["add"]?.jsonPrimitive?.contentOrNull ?: error("missing host")
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = objectValue["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 443
        val displayName = objectValue["ps"]?.jsonPrimitive?.contentOrNull ?: host
        val requestedNetwork = objectValue["net"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val network = normalizedVmessNetwork(requestedNetwork)
        validateVmessShareFlags(objectValue, network)
        val security = objectValue["tls"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val query =
            buildMap {
                put("type", network)
                put("path", objectValue["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("host", objectValue["host"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("serviceName", objectValue["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
                if (network.lowercase() in setOf("grpc", "gun")) {
                    val mode = objectValue["type"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                    require(mode in setOf("", "none", "gun", "multi")) { "unsupported grpc mode" }
                    put("mode", if (mode == "multi") "multi" else "gun")
                    objectValue["host"]?.jsonPrimitive?.contentOrNull?.takeIf(
                        String::isNotEmpty
                    )?.let { put("authority", it) }
                    for (key in VMESS_GRPC_TUNING_FIELDS) {
                        objectValue[key]?.jsonPrimitive?.let { element ->
                            put(key, element.content)
                        }
                    }
                }
                put("security", security)
                put("sni", objectValue["sni"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("fp", objectValue["fp"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("alpn", objectValue["alpn"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("allowInsecure", objectValue["allowInsecure"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("insecure", objectValue["insecure"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        val outbound =
            buildJsonObject {
                put("type", "vmess")
                put("tag", tagFor(displayName, "vmess", host, port, objectValue["id"]?.jsonPrimitive?.contentOrNull))
                put("server", host)
                put("server_port", port)
                put("uuid", objectValue["id"]?.jsonPrimitive?.contentOrNull ?: error("missing id"))
                objectValue["aid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { put("alter_id", it) }
                objectValue["scy"]?.jsonPrimitive?.contentOrNull?.let { put("security", it) }
                buildTls(query, host, allowInsecureTls = allowInsecureTls)?.let { put("tls", it) }
                buildTransport(query)?.let { put("transport", it) }
            }
        return ProxyNode(
            displayName = displayName,
            protocolHint = ProtocolHint.VMESS,
            outbound = outbound,
            subscriptionExpiresAt = subscriptionExpirationFromJson(objectValue),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun parseHysteria2Uri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode {
        val uri = parseLenientUri(value)
        val query = parseQueryParameters(uri.rawQuery)
        val host = uri.host ?: error("missing host")
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = uri.port.takeIf { it > 0 } ?: 443
        val serverPorts = parseHysteriaServerPorts(query["server_ports"] ?: query["mport"])
        val displayName = displayNameFromUri(uri, host)
        val password = uri.userInfo?.takeIf(String::isNotBlank) ?: error("missing password")
        val outbound =
            buildJsonObject {
                put("type", "hysteria2")
                put("tag", tagFor(displayName, "hysteria2", host, port, password))
                put("server", host)
                if (serverPorts.isEmpty()) {
                    put("server_port", port)
                } else {
                    put("server_ports", buildStringArray(serverPorts))
                }
                query
                    .firstNonBlank("hop_interval", "hopinterval", "hop-interval")
                    ?.let { put("hop_interval", it) }
                put("password", password)
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", query["sni"]?.takeIf { it.isNotBlank() } ?: host)
                    val insecureTls = query["insecure"]?.toFlexibleBoolean() ?: false
                    require(allowInsecureTls || !insecureTls) { "INSECURE TLS is not allowed" }
                    if (insecureTls) {
                        put("insecure", true)
                    }
                    query["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                        put(
                            "alpn",
                            buildStringArray(alpn.split(',').map(String::trim).filter(String::isNotBlank)),
                        )
                    }
                    parseHysteriaCertificatePins(query.firstNonBlank("pinsha256", "pin-sha256"))
                        .takeIf(List<String>::isNotEmpty)
                        ?.let { pins -> put("certificate_sha256", buildStringArray(pins)) }
                    parseHysteriaCertificatePins(query["pinned_spki_sha256"])
                        .takeIf(List<String>::isNotEmpty)
                        ?.let { pins -> put("certificate_public_key_sha256", buildStringArray(pins)) }
                }
                buildHysteria2Obfs(
                    query["obfs"],
                    query.firstNonBlank("obfs-password", "obfs_password", "obfspassword"),
                )?.let { put("obfs", it) }
                parseHysteriaBandwidthMbps(query.firstNonBlank("upmbps", "up_mbps", "up"))?.let { put("up_mbps", it) }
                parseHysteriaBandwidthMbps(
                    query.firstNonBlank("downmbps", "down_mbps", "down"),
                )?.let { put("down_mbps", it) }
            }
        return ProxyNode(
            displayName = displayName,
            protocolHint = ProtocolHint.HYSTERIA2,
            outbound = outbound,
            subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
        )
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    internal fun parseHysteria2YamlClientConfig(
        raw: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ProxyNode? {
        if (looksLikeJson(raw) || raw.contains("[Interface]", ignoreCase = true)) {
            return null
        }
        val fields = parseYamlScalarPaths(raw)
        val serverValue = fields["server"] ?: return null
        if (serverValue.startsWith("hysteria2://", ignoreCase = true) || serverValue.startsWith("hy2://", ignoreCase = true)) {
            return parseHysteria2Uri(serverValue, allowPrivateOutboundHosts, allowInsecureTls)
        }
        val password = fields["auth"] ?: fields["auth.password"] ?: fields["auth_str"] ?: return null
        val endpoint = parseRemoteEndpoint(serverValue.substringBefore(',').trim(), defaultPort = 443)
        val host = endpoint.host
        validateOutboundHost(host, allowPrivateOutboundHosts)
        val port = endpoint.port ?: 443
        val serverPorts =
            parseHysteriaServerPorts(
                fields.firstNonBlank("server_ports", "serverports", "ports", "mport")
                    ?: serverValue.substringAfter(',', missingDelimiterValue = ""),
            )
        val displayName = fields["name"]?.takeIf(String::isNotBlank) ?: host
        val tlsSni = fields["tls.sni"]?.takeIf(String::isNotBlank) ?: host
        val insecureTls = fields["tls.insecure"]?.toFlexibleBoolean() ?: false
        require(allowInsecureTls || !insecureTls) { "INSECURE TLS is not allowed" }
        val outbound =
            buildJsonObject {
                put("type", "hysteria2")
                put("tag", tagFor(displayName, "hysteria2", host, port, password))
                put("server", host)
                if (serverPorts.isEmpty()) {
                    put("server_port", port)
                } else {
                    put("server_ports", buildStringArray(serverPorts))
                }
                fields
                    .firstNonBlank("hop_interval", "hopinterval", "hop-interval")
                    ?.let { put("hop_interval", it) }
                put("password", password)
                fields["transport.type"]
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it in setOf("tcp", "udp") }
                    ?.let { put("network", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", tlsSni)
                    if (insecureTls) {
                        put("insecure", true)
                    }
                    parseHysteriaCertificatePins(fields.firstNonBlank("tls.pinsha256", "tls.pin-sha256"))
                        .takeIf(List<String>::isNotEmpty)
                        ?.let { pins -> put("certificate_sha256", buildStringArray(pins)) }
                }
                buildHysteria2Obfs(
                    type = fields["obfs.type"],
                    password = fields["obfs.salamander.password"] ?: fields["obfs.password"],
                )?.let { put("obfs", it) }
                parseHysteriaBandwidthMbps(fields["bandwidth.up"] ?: fields["upmbps"])?.let { put("up_mbps", it) }
                parseHysteriaBandwidthMbps(fields["bandwidth.down"] ?: fields["downmbps"])?.let { put("down_mbps", it) }
            }
        return ProxyNode(
            displayName = displayName,
            protocolHint = ProtocolHint.HYSTERIA2,
            outbound = outbound,
        )
    }

    private fun buildHysteria2Obfs(
        type: String?,
        password: String?,
    ): JsonObject? {
        val normalizedType = type?.trim()?.takeIf(String::isNotBlank)
        val normalizedPassword = password?.trim()?.takeIf(String::isNotBlank)
        if (normalizedType == null && normalizedPassword == null) {
            return null
        }
        return buildJsonObject {
            put("type", normalizedType ?: "salamander")
            normalizedPassword?.let { put("password", it) }
        }
    }

    private fun Map<String, String>.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.trim()?.takeIf(String::isNotBlank) }

    private fun parseHysteriaServerPorts(value: String?): List<String> =
        value
            ?.split(',', ';', ' ', '\n', '\t')
            .orEmpty()
            .mapNotNull { item ->
                val normalized = item.trim().replace('-', ':').takeIf(String::isNotBlank) ?: return@mapNotNull null
                normalized.takeIf(::isHysteriaServerPortRange)
            }
            .distinct()

    private fun isHysteriaServerPortRange(candidate: String): Boolean =
        candidate.matches(hysteriaServerPortsPattern) &&
            candidate
                .split(':')
                .all { part ->
                    part
                        .toIntOrNull()
                        ?.let { port -> port in 1..65535 } == true
                }

    private fun parseHysteriaCertificatePins(value: String?): List<String> =
        value
            ?.split(',', ';', ' ', '\n', '\t')
            .orEmpty()
            .mapNotNull { item -> normalizeHysteriaCertificatePin(item) }
            .distinct()

    private fun normalizeHysteriaCertificatePin(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val hex = trimmed.replace(":", "").replace("-", "")
        return if (hex.length == 64 && hex.all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' }) {
            val bytes =
                hex.chunked(2)
                    .map { octet -> octet.toInt(16).toByte() }
                    .toByteArray()
            Base64.getEncoder().encodeToString(bytes)
        } else {
            trimmed
        }
    }

    private fun parseYamlScalarPaths(raw: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val stack = mutableListOf<Pair<Int, String>>()
        raw.lineSequence().forEach { sourceLine ->
            val line = sourceLine.stripYamlComment()
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("- ")) {
                return@forEach
            }
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) {
                return@forEach
            }
            val indent = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
            while (stack.isNotEmpty() && indent <= stack.last().first) {
                stack.removeAt(stack.lastIndex)
            }
            val key = trimmed.substring(0, separatorIndex).trim().lowercase()
            val value = trimmed.substring(separatorIndex + 1).unquoteYamlScalar()
            val path = (stack.map { it.second } + key).joinToString(".")
            if (value.isBlank()) {
                stack += indent to key
            } else {
                values[path] = value
            }
        }
        return values
    }

    private fun String.stripYamlComment(): String {
        var inSingleQuote = false
        var inDoubleQuote = false
        forEachIndexed { index, char ->
            when (char) {
                '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                '#' -> {
                    if (isYamlCommentStart(index, inSingleQuote, inDoubleQuote)) {
                        return substring(0, index)
                    }
                }
            }
        }
        return this
    }

    private fun String.isYamlCommentStart(
        index: Int,
        inSingleQuote: Boolean,
        inDoubleQuote: Boolean,
    ): Boolean =
        !inSingleQuote &&
            !inDoubleQuote &&
            (
                index == 0 ||
                    this[index - 1].isWhitespace()
                )

    private fun String.unquoteYamlScalar(): String {
        val trimmed = trim()
        return when {
            trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' ->
                trimmed.substring(1, trimmed.lastIndex)
            trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' ->
                trimmed.substring(1, trimmed.lastIndex)
            else -> trimmed
        }
    }

    private fun parseHysteriaBandwidthMbps(value: String?): Int? {
        val trimmed = value?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val match = hysteriaBandwidthRegex.matchEntire(trimmed)
        return if (match == null) {
            trimmed.toIntOrNull()
        } else {
            val amount = match.groupValues[1].toDoubleOrNull()
            val multiplier =
                when (match.groupValues[2]) {
                    "k" -> 0.001
                    "m", "" -> 1.0
                    "g" -> 1_000.0
                    "t" -> 1_000_000.0
                    else -> null
                }
            if (amount != null && multiplier != null) {
                (amount * multiplier).toInt().takeIf { it > 0 }
            } else {
                null
            }
        }
    }

    private val hysteriaServerPortsPattern = Regex("""\d{1,5}(:\d{1,5})?""")
}

internal data class ParsedNodeLines(
    val nodes: List<ProfileImportCoreSupport.ProxyNode>,
    val reports: List<SubscriptionEntryReport>,
)

private fun parseNodeLinesWithCapabilities(
    raw: String,
    normalize: (String) -> String,
    parseSupported: (String) -> ProfileImportCoreSupport.ProxyNode,
): ParsedNodeLines {
    val lines =
        raw.lineSequence()
            .map(normalize)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(MAX_SUBSCRIPTION_ENTRY_LINES + 1)
            .toList()
    if (lines.isEmpty()) {
        return ParsedNodeLines(emptyList(), emptyList())
    }
    require(lines.size <= MAX_SUBSCRIPTION_ENTRY_LINES) {
        "subscription declares more than $MAX_SUBSCRIPTION_ENTRY_LINES entries"
    }
    val parsed = mutableListOf<ProfileImportCoreSupport.ProxyNode>()
    val reports = mutableListOf<SubscriptionEntryReport>()
    val malformedSupportedEntries = mutableListOf<String>()
    lines.forEachIndexed { index, line ->
        val scheme = line.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme !in SUPPORTED_SHARE_URI_SCHEMES) {
            reports +=
                SubscriptionEntryReport(
                    protocolLabel = unsupportedProtocolLabel(scheme),
                    protocolHint = ProtocolHint.UNKNOWN,
                    status = SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
                    sourceLine = index + 1,
                    reason = "unsupported protocol",
                )
            return@forEachIndexed
        }
        runCatching { parseSupported(line) }
            .onSuccess { node ->
                parsed += node
                reports +=
                    SubscriptionEntryReport(
                        protocolLabel = acceptedShareProtocolLabel(scheme, node),
                        protocolHint = node.protocolHint,
                        status = SubscriptionEntryStatus.ACCEPTED,
                        sourceLine = index + 1,
                    )
            }.onFailure { error ->
                val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
                malformedSupportedEntries += "line ${index + 1}: $reason"
            }
    }
    require(malformedSupportedEntries.isEmpty()) {
        "invalid supported subscription entries: ${malformedSupportedEntries.size} rejected " +
            "(${malformedSupportedEntries.take(3).joinToString("; ")})"
    }
    return ParsedNodeLines(parsed, reports)
}

private fun unsupportedProtocolLabel(scheme: String): String =
    when (scheme) {
        "tg", "mtproto" -> "MTPROTO"
        else ->
            scheme
                .take(MAX_PROTOCOL_LABEL_LENGTH)
                .filter { character -> character.isLetterOrDigit() || character in "+-." }
                .uppercase()
                .ifBlank { "UNKNOWN" }
    }

private fun acceptedShareProtocolLabel(
    scheme: String,
    node: ProfileImportCoreSupport.ProxyNode,
): String =
    when {
        node.protocolHint == ProtocolHint.OUTLINE -> "OUTLINE"
        node.carriesAmneziaObfuscation() -> AMNEZIAWG_PROTOCOL_LABEL
        else -> shareProtocolLabel(scheme)
    }

private fun shareProtocolLabel(scheme: String): String =
    when (scheme) {
        "naive", "naive+https" -> "NAIVE"
        "ss" -> "SHADOWSOCKS"
        "outline" -> "OUTLINE"
        "hy2", "hysteria2" -> "HYSTERIA2"
        "tuic" -> "TUIC"
        "anytls" -> "ANYTLS"
        "awg", "amnezia", "amneziawg" -> "WIREGUARD"
        else -> scheme.uppercase()
    }

private val SUPPORTED_SHARE_URI_SCHEMES =
    setOf(
        "vless",
        "trojan",
        "naive",
        "naive+https",
        "ss",
        "outline",
        "vmess",
        "hy2",
        "hysteria2",
        "tuic",
        "anytls",
        "wireguard",
        "wg",
        "awg",
        "amnezia",
        "amneziawg",
    )

private const val MAX_PROTOCOL_LABEL_LENGTH = 32

private val hysteriaBandwidthRegex = Regex("""^(\d+(?:\.\d+)?)\s*([kmgt]?)(?:bps|b|bit/s|bits/s)?$""")

private fun sip003Carrier(
    queryPart: String,
    query: Map<String, String>,
    decode: (String) -> String,
    parseBoolean: (String) -> Boolean?,
): Pair<JsonObject, JsonObject>? {
    val encoded = query["plugin"] ?: return null
    require(query.keys.all { it.lowercase() in setOf("plugin", "udp") }) { "unsupported SIP003 query option" }
    val keys = queryPart.split(
        '&'
    ).filter(String::isNotBlank).map { decode(it.substringBefore('=')).trim().lowercase() }
    require(keys.distinct().size == keys.size) { "duplicate SIP003 query option" }
    val fields = parseSip003Fields(encoded)
    require(fields["mode"] == null || fields["mode"] == "websocket") { "unsupported SIP003 mode" }
    require(fields["mux"]?.toIntOrNull() == 0) { "SIP003 requires explicit mux=0 for the native carrier" }
    require(query["udp"]?.let(parseBoolean) != true) { "SIP003 stream carrier cannot enable UDP" }
    val host = fields["host"] ?: "cloudfront.com"
    val path = fields["path"] ?: "/"
    require(validSip003Host(host)) { "invalid SIP003 host" }
    require(validSip003Path(path)) { "invalid SIP003 path" }
    val transport = buildJsonObject {
        put("type", "ws")
        put("path", path)
        putJsonObject("headers") { put("Host", host) }
    }
    val tls = buildJsonObject {
        put("enabled", "tls" in fields)
        if ("tls" in fields) put("server_name", host)
    }
    return transport to tls
}

private fun parseSip003Fields(value: String): Map<String, String?> {
    require(value.toByteArray(Charsets.UTF_8).size <= 8192) { "SIP003 options too large" }
    require(value.none(Char::isISOControl)) { "invalid SIP003 option" }
    val fields = sip003Tokens(value)
    require(fields.firstOrNull() == ("v2ray-plugin" to null)) { "unsupported SIP003 plugin" }
    val result = linkedMapOf<String, String?>()
    for ((key, item) in fields.drop(1)) {
        if (key.isEmpty() && item == null) continue
        require(key in setOf("mode", "mux", "tls", "host", "path")) { "unsupported SIP003 option" }
        require(key !in result) { "duplicate SIP003 option" }
        require(key == "tls" || !item.isNullOrEmpty()) { "missing SIP003 option value" }
        result[key] = item
    }
    return result
}

private fun sip003Tokens(value: String): List<Pair<String, String?>> {
    val result = mutableListOf<Pair<String, String?>>()
    val token = StringBuilder()
    var key: String? = null
    var escaped = false
    for (char in value) {
        when {
            escaped -> {
                require(char in ";=\\") { "invalid SIP003 escape" }
                token.append(char)
                escaped = false
            }
            char == '\\' -> escaped = true
            char == '=' -> {
                require(key == null) { "unescaped SIP003 option delimiter" }
                key = token.toString()
                token.setLength(0)
            }
            char == ';' -> {
                result += sip003Field(key, token.toString())
                key = null
                token.setLength(0)
            }
            else -> token.append(char)
        }
    }
    require(!escaped) { "unfinished SIP003 escape" }
    result += sip003Field(key, token.toString())
    return result
}

private fun sip003Field(key: String?, tail: String): Pair<String, String?> =
    if (key == null) tail to null else key to tail

internal fun validSip003Host(value: String): Boolean =
    value.length in 1..255 && value.all { validSip003Ascii(it, "/?#@") }

internal fun validSip003Path(value: String): Boolean =
    value.startsWith('/') && value.length <= 2048 && value.all { validSip003Ascii(it, "#") }

private fun validSip003Ascii(value: Char, forbidden: String): Boolean = value.code in 0x21..0x7e && value !in forbidden

internal fun isClientVlessEncryptionSpec(value: String): Boolean {
    if (value.length > 65536 || value.any { it.isISOControl() || it.isWhitespace() }) return false
    val fields = value.split('.')
    if (!validClientEncryptionPrefix(fields)) return false
    val rest = fields.drop(3)
    val padding = rest.takeWhile { it.length < 20 }
    val keys = rest.drop(padding.size)
    return keys.isNotEmpty() && validClientEncryptionPadding(padding) && keys.all(::validClientEncryptionKey)
}

private fun validClientEncryptionPrefix(fields: List<String>): Boolean {
    if (fields.size < 4 || fields[0] != "mlkem768x25519plus") return false
    if (fields[1] !in setOf("native", "xorpub", "random")) return false
    return fields[2] in setOf("0rtt", "1rtt")
}

private fun validClientEncryptionPadding(fields: List<String>): Boolean {
    var total = 0L
    for ((index, field) in fields.withIndex()) {
        val values = clientEncryptionPaddingValues(field) ?: return false
        if (index == 0 && !validFirstClientPadding(values)) return false
        if (index % 2 == 0) total += maxOf(values[1], values[2])
    }
    return total <= 65553L
}

private fun clientEncryptionPaddingValues(field: String): List<Long>? {
    val parts = field.split('-')
    if (parts.size != 3) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    return values.takeIf { numbers -> numbers.all { it in 0..4294967295L } }
}

private fun validFirstClientPadding(values: List<Long>): Boolean =
    values[0] >= 100 && values[1] >= 35 && values[2] >= 35

private fun validClientEncryptionKey(value: String): Boolean {
    if (!value.matches(Regex("[A-Za-z0-9_-]+"))) return false
    val bytes = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return false
    return bytes.size in setOf(32, 1184) && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
}

private fun readClientVlessEncryption(query: Map<String, String>): String? {
    val encryption = query["encryption"]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) }
    require(encryption == null || isClientVlessEncryptionSpec(encryption)) {
        "unsupported vless encryption: invalid or unsupported grammar"
    }
    require(encryption == null || query["flow"].isNullOrBlank()) { "VLESS Encryption with Vision is unsupported" }
    return encryption
}

private fun validateVmessShareFlags(document: JsonObject, network: String) {
    val muxValue = document["mux"]
    if (muxValue != null && muxValue != JsonNull) {
        val mux = requireNotNull(muxValue as? JsonPrimitive) { "invalid VMess mux option" }
        require(mux.content.lowercase() in setOf("0", "false", "no")) { "unsupported VMess mux option" }
    }
    if (network.lowercase() in setOf("grpc", "gun")) return
    require(document.keys.none { it in VMESS_GRPC_TUNING_FIELDS }) { "VMess gRPC options require gRPC transport" }
    val headerValue = document["type"]
    if (headerValue != null && headerValue != JsonNull) {
        val header = requireNotNull(headerValue as? JsonPrimitive) { "invalid VMess header type" }
        require(header.isString && header.content in setOf("", "none")) { "unsupported VMess header type" }
    }
}

private fun normalizedVmessNetwork(value: String): String = if (value.equals("h2", ignoreCase = true)) "http" else value

private val VMESS_GRPC_TUNING_FIELDS =
    listOf(
        "rpc_path",
        "user_agent",
        "idle_timeout",
        "idle_timeout_ms",
        "health_check_timeout",
        "ping_timeout",
        "health_check_timeout_ms",
        "permit_without_stream",
        "initial_windows_size",
    )
