package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

internal fun translateFoxCoreProxyOutbound(
    source: JsonObject,
    path: String,
): JsonObject {
    val type = source.requiredString("type", path).lowercase()
    return when (type) {
        "vless" -> translateVless(source, path)
        "vmess" -> translateVmess(source, path)
        "trojan" -> translateTrojan(source, path)
        "shadowsocks" -> translateShadowsocks(source, path)
        "hysteria2" -> translateHysteria2(source, path)
        "tuic" -> translateTuic(source, path)
        "naive" -> translateNaive(source, path)
        "anytls" -> translateAnyTls(source, path)
        else -> rejectFoxCoreConfig(FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED, path)
    }
}

@Suppress("LongMethod")
private fun translateVless(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(VLESS_KEYS, path)
    validateManagedTcpReliability(source, path)
    source.optionalString("network", path)?.let { network ->
        if (network.lowercase() != "tcp") {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.network")
        }
    }
    val flow = source.optionalString("flow", path)?.takeIf(String::isNotBlank)
    val encryption = readVlessEncryption(source, path, flow)
    if (flow != null && flow != "xtls-rprx-vision") {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.flow")
    }
    val packetEncoding = translateVlessPacketEncoding(source, path, flow)
    if (flow != null && packetEncoding != "xudp") {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.packet_encoding")
    }
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = false,
            allowReality = true,
        )
    val transport = source["transport"]?.asFoxCoreObject("$path.transport")
    val resolvedTransport = if (tls.reality != null && transport?.optionalString("type", "$path.transport")?.lowercase() in setOf("xhttp", "splithttp") && transport?.optionalString("mode", "$path.transport").orEmpty() in setOf("", "auto")) {
        JsonObject(checkNotNull(transport) + ("mode" to JsonPrimitive("stream-one")))
    } else { transport }
    if (tls.reality != null && source["transport"] != null && !isRealityStreamTransport(source, flow)) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.transport")
    }
    return buildJsonObject {
        put("type", "vless")
        putCommonServer(source, path)
        put("uuid", source.requiredString("uuid", path))
        encryption?.let { put("encryption", it) }
        flow?.let { put("flow", it) }
        put(
            "transport",
            translateFoxCoreTransport(
                resolvedTransport,
                "$path.transport",
            ),
        )
        put("packet_encoding", packetEncoding)
        put("tls", tls.tls)
        tls.reality?.let { put("reality", it) }
    }
}

private fun readVlessEncryption(source: JsonObject, path: String, flow: String?): String? {
    val encryption =
        source.optionalString("encryption", path)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("none", true) }
    if (encryption != null && !isClientVlessEncryptionSpec(encryption)) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.encryption")
    }
    if (encryption != null && flow != null) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.flow")
    }
    return encryption
}

private fun isRealityStreamTransport(source: JsonObject, flow: String?): Boolean {
    if (flow != null) {
        return false
    }
    val transport = source["transport"] as? JsonObject ?: return false
    val type = transport["type"] as? JsonPrimitive ?: return false
    return type.isString && type.content.lowercase() in setOf("grpc", "xhttp", "splithttp")
}

private fun translateVlessPacketEncoding(
    source: JsonObject,
    path: String,
    flow: String?,
): String {
    val requested = source.optionalString("packet_encoding", path)?.lowercase()
    return when {
        requested != null && requested in setOf("none", "xudp", "packetaddr") -> requested
        requested != null ->
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.packet_encoding")
        flow == "xtls-rprx-vision" -> "xudp"
        else -> "none"
    }
}

private fun translateVmess(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(VMESS_KEYS, path)
    validateManagedTcpReliability(source, path)
    source.optionalString("network", path)?.let { network ->
        if (network.lowercase() != "tcp") {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.network")
        }
    }
    val alterId = source.optionalInt("alter_id", path) ?: 0
    if (alterId != 0) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.alter_id")
    }
    val cipher =
        when (source.optionalString("security", path)?.lowercase() ?: "auto") {
            "auto" -> "auto"
            "aes-128-gcm" -> "aes-128-gcm"
            "chacha20-poly1305" -> "chacha20-poly1305"
            "none" -> "none"
            else -> rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.security")
        }
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = false,
            allowReality = false,
        )
    return buildJsonObject {
        put("type", "vmess")
        putCommonServer(source, path)
        put("uuid", source.requiredString("uuid", path))
        put("alter_id", 0)
        put("cipher", cipher)
        put(
            "transport",
            translateFoxCoreTransport(
                source["transport"]?.asFoxCoreObject("$path.transport"),
                "$path.transport",
            ),
        )
        put("tls", tls.tls)
    }
}

private fun translateTrojan(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(TROJAN_KEYS, path)
    validateManagedTcpReliability(source, path)
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = true,
            allowReality = false,
        )
    return buildJsonObject {
        put("type", "trojan")
        putCommonServer(source, path)
        put("password", source.requiredString("password", path))
        put("tls", tls.tls)
        put(
            "transport",
            translateFoxCoreTransport(
                source["transport"]?.asFoxCoreObject("$path.transport"),
                "$path.transport",
            ),
        )
    }
}

private fun translateShadowsocks(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(SHADOWSOCKS_KEYS, path)
    validateManagedTcpReliability(source, path)
    val network = source.optionalString("network", path)?.lowercase()
    val udp =
        when (network) {
            null, "", "tcp,udp", "udp,tcp" -> true
            "tcp" -> false
            else -> rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.network")
        }
    if (source.optionalBoolean("udp_over_tcp", path) == true) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.udp_over_tcp")
    }
    val transport =
        translateFoxCoreTransport(
            source["transport"]?.asFoxCoreObject("$path.transport"),
            "$path.transport",
        )
    val tls =
        translateFoxCoreTls(
            source["tls"]?.asFoxCoreObject("$path.tls"),
            "$path.tls",
            mandatory = false,
            allowReality = false,
        )
    val streamCarrier = transport["type"] != JsonPrimitive("raw") || tls.tls["enabled"] == JsonPrimitive(true)
    if (udp && streamCarrier) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.network")
    }
    return buildJsonObject {
        put("type", "shadowsocks")
        putCommonServer(source, path)
        put("method", source.requiredString("method", path))
        put("password", source.requiredString("password", path))
        put("udp", udp)
        put("transport", transport)
        put("tls", tls.tls)
    }
}

@Suppress("LongMethod")
private fun translateHysteria2(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(HYSTERIA2_KEYS, path)
    val sourceRanges = source.stringList("server_ports", path)
    val ranges =
        sourceRanges.mapIndexed { index, range ->
            parseHysteriaPortRange(range, "$path.server_ports[$index]")
        }
    val port =
        if (ranges.isEmpty()) {
            source.requiredPort("server_port", path)
        } else {
            if (source["server_port"] != null) {
                source.requiredPort("server_port", path)
            } else {
                ranges.first().first
            }
        }
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = true,
            allowReality = false,
        )
    val obfs =
        source["obfs"]?.asFoxCoreObject("$path.obfs")?.let { obfsSource ->
            obfsSource.requireOnlyKeys(setOf("type", "password"), "$path.obfs")
            if (obfsSource.requiredString("type", "$path.obfs").lowercase() != "salamander") {
                rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.obfs")
            }
            buildJsonObject {
                put("type", "salamander")
                put("password", obfsSource.requiredString("password", "$path.obfs"))
            }
        }
    return buildJsonObject {
        put("type", "hysteria2")
        put("server", source.requiredString("server", path))
        source.optionalString("server_ip", path)?.let { put("server_ip", it) }
        put("port", port)
        put("password", source.requiredString("password", path))
        source.optionalInt("up_mbps", path)?.let { put("up_mbps", it) }
        source.optionalInt("down_mbps", path)?.let { put("down_mbps", it) }
        obfs?.let { put("obfs", it) }
        if (ranges.isNotEmpty()) {
            put(
                "server_ports",
                buildJsonArray {
                    ranges.forEach { range ->
                        add(
                            buildJsonObject {
                                put("start", range.first)
                                put("end", range.last)
                            },
                        )
                    }
                },
            )
            val hopInterval =
                source.optionalString("hop_interval", path)
                    ?.let { parseFoxCoreDurationMillis(it, "$path.hop_interval") }
                    ?: 30_000L
            put("hop_interval_ms", hopInterval)
        } else if (source["hop_interval"] != null) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                "$path.hop_interval",
            )
        }
        put("tls", tls.tls)
    }
}

@Suppress("CyclomaticComplexMethod")
private fun translateTuic(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(TUIC_KEYS, path)
    val congestionControl =
        when (source.optionalString("congestion_control", path)?.lowercase() ?: "cubic") {
            "cubic" -> "cubic"
            "new_reno", "newreno" -> "new_reno"
            else -> rejectFoxCoreConfig(
                FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT,
                "$path.congestion_control",
            )
        }
    val udpRelayMode =
        source.optionalString("udp_relay_mode", path)
            ?.lowercase()
            ?.takeIf { it in setOf("native", "quic") }
            ?: source.optionalString("udp_relay_mode", path)?.let {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT,
                    "$path.udp_relay_mode",
                )
            }
            ?: "native"
    val network = source.optionalString("network", path)?.lowercase()
    val tcp = network == null || network == "tcp" || network in setOf("tcp,udp", "udp,tcp")
    val udp = network == null || network == "udp" || network in setOf("tcp,udp", "udp,tcp")
    if (!tcp && !udp) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.network")
    }
    if (source.optionalBoolean("zero_rtt_handshake", path) == true) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.zero_rtt_handshake")
    }
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = true,
            allowReality = false,
        )
    return buildJsonObject {
        put("type", "tuic")
        putCommonServer(source, path)
        put("uuid", source.requiredString("uuid", path))
        put("password", source.requiredString("password", path))
        put("congestion_control", congestionControl)
        put("udp_relay_mode", udpRelayMode)
        put("tcp", tcp)
        put("udp", udp)
        put("zero_rtt_handshake", false)
        source.optionalString("heartbeat", path)?.let {
            put("heartbeat_ms", parseFoxCoreDurationMillis(it, "$path.heartbeat"))
        }
        source.optionalString("idle_timeout", path)?.let {
            put("idle_timeout_ms", parseFoxCoreDurationMillis(it, "$path.idle_timeout"))
        }
        put("tls", tls.tls)
    }
}

private fun translateNaive(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(NAIVE_KEYS, path)
    if (source.optionalBoolean("quic", path) == true ||
        source.optionalBoolean("udp_over_tcp", path) == true ||
        source["quic_congestion_control"] != null
    ) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
    val username = source.optionalString("username", path)
    val password = source.optionalString("password", path)
    if ((username == null) != (password == null)) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = true,
            allowReality = false,
        )
    return buildJsonObject {
        put("type", "naive")
        putCommonServer(source, path)
        username?.let { put("username", it) }
        password?.let { put("password", it) }
        put("tls", tls.tls)
        put("padding", true)
    }
}

private fun translateAnyTls(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(ANYTLS_KEYS, path)
    val tls =
        translateFoxCoreTls(
            source = source["tls"]?.asFoxCoreObject("$path.tls"),
            path = "$path.tls",
            mandatory = true,
            allowReality = false,
        )
    return buildJsonObject {
        put("type", "anytls")
        putCommonServer(source, path)
        put("password", source.requiredString("password", path))
        put("tls", tls.tls)
        source.optionalString("idle_session_check_interval", path)?.let {
            put(
                "idle_session_check_interval_ms",
                parseFoxCoreDurationMillis(it, "$path.idle_session_check_interval"),
            )
        }
        source.optionalString("idle_session_timeout", path)?.let {
            put(
                "idle_session_timeout_ms",
                parseFoxCoreDurationMillis(it, "$path.idle_session_timeout"),
            )
        }
        source.optionalInt("min_idle_session", path)?.let { put("min_idle_session", it) }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putCommonServer(
    source: JsonObject,
    path: String,
) {
    put("server", source.requiredString("server", path))
    source.optionalString("server_ip", path)?.let { serverIp ->
        if (!serverIp.isFoxCoreIpLiteral()) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.server_ip")
        }
        put("server_ip", serverIp)
    }
    put("port", source.requiredPort("server_port", path))
}

private fun validateManagedTcpReliability(
    source: JsonObject,
    path: String,
) {
    source.optionalString("tcp_keep_alive", path)?.let {
        if (it != MOBILE_TCP_KEEP_ALIVE) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED, "$path.tcp_keep_alive")
        }
    }
    source.optionalString("tcp_keep_alive_interval", path)?.let {
        if (it != MOBILE_TCP_KEEP_ALIVE_INTERVAL) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                "$path.tcp_keep_alive_interval",
            )
        }
    }
    if (source.optionalBoolean("disable_tcp_keep_alive", path) == true) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
            "$path.disable_tcp_keep_alive",
        )
    }
}

private fun parseHysteriaPortRange(
    source: String,
    path: String,
): IntRange {
    val parts = source.replace('-', ':').split(':', limit = 2)
    val start = parts.firstOrNull()?.toIntOrNull()
    val end = parts.getOrNull(1)?.toIntOrNull() ?: start
    if (start == null || end == null) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    if (start !in 1..65_535 || end !in start..65_535) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return start..end
}

private val COMMON_PROXY_KEYS =
    setOf(
        "type",
        "tag",
        "server",
        "server_ip",
        "server_port",
    )

private val TCP_RELIABILITY_KEYS =
    setOf(
        "tcp_keep_alive",
        "tcp_keep_alive_interval",
        "disable_tcp_keep_alive",
    )

private val VLESS_KEYS =
    COMMON_PROXY_KEYS +
        TCP_RELIABILITY_KEYS +
        setOf("uuid", "flow", "network", "packet_encoding", "tls", "transport", "encryption")

private val VMESS_KEYS =
    COMMON_PROXY_KEYS +
        TCP_RELIABILITY_KEYS +
        setOf("uuid", "alter_id", "security", "network", "tls", "transport")

private val TROJAN_KEYS =
    COMMON_PROXY_KEYS +
        TCP_RELIABILITY_KEYS +
        setOf("password", "tls", "transport")

private val SHADOWSOCKS_KEYS =
    COMMON_PROXY_KEYS +
        TCP_RELIABILITY_KEYS +
        setOf("method", "password", "network", "udp_over_tcp", "tls", "transport")

private val HYSTERIA2_KEYS =
    COMMON_PROXY_KEYS +
        setOf(
            "password",
            "up_mbps",
            "down_mbps",
            "obfs",
            "server_ports",
            "hop_interval",
            "tls",
        )

private val TUIC_KEYS =
    COMMON_PROXY_KEYS +
        setOf(
            "uuid",
            "password",
            "congestion_control",
            "udp_relay_mode",
            "network",
            "zero_rtt_handshake",
            "heartbeat",
            "idle_timeout",
            "tls",
        )

private val NAIVE_KEYS =
    COMMON_PROXY_KEYS +
        setOf(
            "username",
            "password",
            "quic",
            "udp_over_tcp",
            "quic_congestion_control",
            "tls",
        )

private val ANYTLS_KEYS =
    COMMON_PROXY_KEYS +
        setOf(
            "password",
            "tls",
            "idle_session_check_interval",
            "idle_session_timeout",
            "min_idle_session",
        )

private fun isClientVlessEncryptionSpec(value: String): Boolean {
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
