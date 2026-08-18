package com.foxhole.guard.ui.cli.profiles

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal enum class CliProtoGroup { COMMON, AUTH, TRANSPORT, TLS }

internal enum class CliProtoKind { TEXT, NUMBER, BOOL, CSV, CHOICE }

internal data class CliProtoField(
    val label: String,
    val path: List<String>,
    val kind: CliProtoKind,
    val group: CliProtoGroup,
    val choices: List<String> = emptyList(),
)

private val TRANSPORT_TYPE_PATH = listOf("transport", "type")
private val UTLS_FINGERPRINT_PATH = listOf("tls", "utls", "fingerprint")
private val REALITY_PUBLIC_KEY_PATH = listOf("tls", "reality", "public_key")

internal val UTLS_FINGERPRINTS =
    listOf("", "chrome", "firefox", "edge", "safari", "ios", "qq", "random", "randomized")
private val VMESS_SECURITIES =
    listOf("", "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305")
private val TRANSPORT_TYPES = listOf("", "ws", "grpc", "httpupgrade", "http")

internal const val CLI_WIREGUARD_TYPE = "wireguard"
internal const val CLI_AMNEZIA_WIREGUARD_TYPE = "amneziawg"

internal val CLI_STRUCTURED_OUTBOUND_TYPES =
    setOf(
        "vless",
        "vmess",
        "trojan",
        "shadowsocks",
        "hysteria2",
        "tuic",
        "anytls",
        "naive",
        CLI_WIREGUARD_TYPE,
        CLI_AMNEZIA_WIREGUARD_TYPE,
    )

internal val CLI_NEW_OUTBOUND_TYPES =
    CLI_STRUCTURED_OUTBOUND_TYPES.filterNot { type -> type == CLI_AMNEZIA_WIREGUARD_TYPE }.sorted()

private const val FIRST_PEER = "peers[0]"

private val TRANSPORT_CAPABLE_TYPES = setOf("vless", "vmess", "trojan")
private val TLS_CAPABLE_TYPES =
    setOf("vless", "vmess", "trojan", "hysteria2", "tuic", "anytls", "naive")

internal fun CliProtoField.isTransportType(): Boolean = path == TRANSPORT_TYPE_PATH

internal fun cliProtoFields(
    type: String,
    transportType: String,
): List<CliProtoField> =
    buildList {
        addAll(commonFields(type))
        addAll(authFields(type))
        if (type in TRANSPORT_CAPABLE_TYPES) {
            add(transportTypeField())
            addAll(transportDetailFields(transportType))
        }
        if (type in TLS_CAPABLE_TYPES) {
            addAll(tlsFields())
        }
    }

private fun commonFields(type: String): List<CliProtoField> =
    if (type in CLI_PACKET_TUNNEL_TYPES) {
        listOf(
            CliProtoField("server", listOf(FIRST_PEER, "address"), CliProtoKind.TEXT, CliProtoGroup.COMMON),
            CliProtoField("server_port", listOf(FIRST_PEER, "port"), CliProtoKind.NUMBER, CliProtoGroup.COMMON),
            CliProtoField("tag", listOf("tag"), CliProtoKind.TEXT, CliProtoGroup.COMMON),
            CliProtoField("address", listOf("address"), CliProtoKind.CSV, CliProtoGroup.COMMON),
            CliProtoField("mtu", listOf("mtu"), CliProtoKind.NUMBER, CliProtoGroup.COMMON),
        )
    } else {
        listOf(
            CliProtoField("server", listOf("server"), CliProtoKind.TEXT, CliProtoGroup.COMMON),
            CliProtoField("server_port", listOf("server_port"), CliProtoKind.NUMBER, CliProtoGroup.COMMON),
            CliProtoField("tag", listOf("tag"), CliProtoKind.TEXT, CliProtoGroup.COMMON),
        )
    }

@Suppress("CyclomaticComplexMethod")
private fun authFields(type: String): List<CliProtoField> =
    when (type) {
        "vless" ->
            listOf(
                text("uuid", listOf("uuid"), CliProtoGroup.AUTH),
                choice("flow", listOf("flow"), listOf("", "xtls-rprx-vision")),
                choice("packet_encoding", listOf("packet_encoding"), listOf("", "xudp", "packetaddr")),
            )
        "vmess" ->
            listOf(
                text("uuid", listOf("uuid"), CliProtoGroup.AUTH),
                CliProtoField("alter_id", listOf("alter_id"), CliProtoKind.NUMBER, CliProtoGroup.AUTH),
                choice("security", listOf("security"), VMESS_SECURITIES),
            )
        "trojan", "anytls" -> listOf(text("password", listOf("password"), CliProtoGroup.AUTH))
        "shadowsocks" ->
            listOf(
                text("method", listOf("method"), CliProtoGroup.AUTH),
                text("password", listOf("password"), CliProtoGroup.AUTH),
            )
        "naive" ->
            listOf(
                text("username", listOf("username"), CliProtoGroup.AUTH),
                text("password", listOf("password"), CliProtoGroup.AUTH),
            )
        "hysteria2" ->
            listOf(
                text("password", listOf("password"), CliProtoGroup.AUTH),
                choice("obfs.type", listOf("obfs", "type"), listOf("", "salamander")),
                text("obfs.password", listOf("obfs", "password"), CliProtoGroup.AUTH),
            )
        "tuic" ->
            listOf(
                text("uuid", listOf("uuid"), CliProtoGroup.AUTH),
                text("password", listOf("password"), CliProtoGroup.AUTH),
                choice("congestion_control", listOf("congestion_control"), listOf("", "cubic", "new_reno", "bbr")),
                choice("udp_relay_mode", listOf("udp_relay_mode"), listOf("", "native", "quic")),
            )
        in CLI_PACKET_TUNNEL_TYPES ->
            listOf(
                text("private_key", listOf("private_key"), CliProtoGroup.AUTH),
                text("peer_public_key", listOf(FIRST_PEER, "public_key"), CliProtoGroup.AUTH),
                text("pre_shared_key", listOf(FIRST_PEER, "pre_shared_key"), CliProtoGroup.AUTH),
                CliProtoField(
                    label = "allowed_ips",
                    path = listOf(FIRST_PEER, "allowed_ips"),
                    kind = CliProtoKind.CSV,
                    group = CliProtoGroup.AUTH,
                ),
                CliProtoField(
                    label = "persistent_keepalive_interval",
                    path = listOf(FIRST_PEER, "persistent_keepalive_interval"),
                    kind = CliProtoKind.NUMBER,
                    group = CliProtoGroup.AUTH,
                ),
            ) + if (type == CLI_AMNEZIA_WIREGUARD_TYPE) amneziaFields() else emptyList()
        else -> emptyList()
    }

private fun amneziaFields(): List<CliProtoField> =
    listOf(
        number("amnezia.Jc", "junk_packet_count"),
        number("amnezia.Jmin", "junk_min_size"),
        number("amnezia.Jmax", "junk_max_size"),
        number("amnezia.S1", "init_junk_size"),
        number("amnezia.S2", "response_junk_size"),
        number("amnezia.S3", "cookie_junk_size"),
        number("amnezia.S4", "transport_junk_size"),
        text("amnezia.H1", listOf("amnezia", "header_initiation"), CliProtoGroup.TRANSPORT),
        text("amnezia.H2", listOf("amnezia", "header_response"), CliProtoGroup.TRANSPORT),
        text("amnezia.H3", listOf("amnezia", "header_cookie"), CliProtoGroup.TRANSPORT),
        text("amnezia.H4", listOf("amnezia", "header_transport"), CliProtoGroup.TRANSPORT),
    )

private fun number(label: String, key: String): CliProtoField =
    CliProtoField(label, listOf("amnezia", key), CliProtoKind.NUMBER, CliProtoGroup.TRANSPORT)

private fun transportTypeField(): CliProtoField =
    CliProtoField(
        label = "transport.type",
        path = TRANSPORT_TYPE_PATH,
        kind = CliProtoKind.CHOICE,
        group = CliProtoGroup.TRANSPORT,
        choices = TRANSPORT_TYPES,
    )

private fun transportDetailFields(transportType: String): List<CliProtoField> =
    when (transportType) {
        "ws" ->
            listOf(
                text("transport.path", listOf("transport", "path"), CliProtoGroup.TRANSPORT),
                text("transport.headers.Host", listOf("transport", "headers", "Host"), CliProtoGroup.TRANSPORT),
            )
        "grpc" ->
            listOf(text("transport.service_name", listOf("transport", "service_name"), CliProtoGroup.TRANSPORT))
        "httpupgrade" ->
            listOf(
                text("transport.host", listOf("transport", "host"), CliProtoGroup.TRANSPORT),
                text("transport.path", listOf("transport", "path"), CliProtoGroup.TRANSPORT),
            )
        "http" ->
            listOf(
                CliProtoField(
                    label = "transport.host",
                    path = listOf("transport", "host"),
                    kind = CliProtoKind.CSV,
                    group = CliProtoGroup.TRANSPORT,
                ),
                text("transport.path", listOf("transport", "path"), CliProtoGroup.TRANSPORT),
            )
        else -> emptyList()
    }

private fun tlsFields(): List<CliProtoField> =
    listOf(
        CliProtoField("tls.enabled", listOf("tls", "enabled"), CliProtoKind.BOOL, CliProtoGroup.TLS),
        text("tls.server_name", listOf("tls", "server_name"), CliProtoGroup.TLS),
        CliProtoField("tls.alpn", listOf("tls", "alpn"), CliProtoKind.CSV, CliProtoGroup.TLS),
        CliProtoField(
            label = "tls.utls.fingerprint",
            path = UTLS_FINGERPRINT_PATH,
            kind = CliProtoKind.CHOICE,
            group = CliProtoGroup.TLS,
            choices = UTLS_FINGERPRINTS,
        ),
        text("tls.reality.public_key", REALITY_PUBLIC_KEY_PATH, CliProtoGroup.TLS),
        text("tls.reality.short_id", listOf("tls", "reality", "short_id"), CliProtoGroup.TLS),
        CliProtoField("tls.insecure", listOf("tls", "insecure"), CliProtoKind.BOOL, CliProtoGroup.TLS),
    )

private fun text(
    label: String,
    path: List<String>,
    group: CliProtoGroup,
): CliProtoField = CliProtoField(label, path, CliProtoKind.TEXT, group)

private fun choice(
    label: String,
    path: List<String>,
    choices: List<String>,
): CliProtoField = CliProtoField(label, path, CliProtoKind.CHOICE, CliProtoGroup.AUTH, choices)

private val CLI_PACKET_TUNNEL_TYPES = setOf(CLI_WIREGUARD_TYPE, CLI_AMNEZIA_WIREGUARD_TYPE)

internal fun JsonObject.readCliProtoField(field: CliProtoField): String {
    val element = elementAtPath(field.path) ?: return ""
    return when (field.kind) {
        CliProtoKind.BOOL -> (element as? JsonPrimitive)?.booleanOrNull?.toString().orEmpty()
        CliProtoKind.CSV ->
            when (element) {
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",")
                is JsonPrimitive -> element.contentOrNull.orEmpty()
                else -> ""
            }
        else -> (element as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

internal fun JsonObject.writeCliProtoField(
    field: CliProtoField,
    value: String,
): JsonObject {
    val written = putAtPath(field.path, field.jsonValue(value))
    return when (field.path) {
        UTLS_FINGERPRINT_PATH -> written.withEnabledFlag(listOf("tls", "utls"), value.isNotBlank())
        REALITY_PUBLIC_KEY_PATH -> written.withEnabledFlag(listOf("tls", "reality"), value.isNotBlank())
        else -> written
    }
}

internal fun JsonObject.writeCliTransportType(value: String): JsonObject {
    val kept = transportDetailFields(value).map(CliProtoField::path).toSet()
    val cleared =
        TRANSPORT_TYPES
            .flatMap(::transportDetailFields)
            .distinctBy(CliProtoField::path)
            .filterNot { field -> field.path in kept }
            .fold(this) { outbound, field -> outbound.putAtPath(field.path, null) }
    return cleared.writeCliProtoField(transportTypeField(), value)
}

private fun CliProtoField.jsonValue(value: String): JsonElement? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    return when (kind) {
        CliProtoKind.BOOL -> trimmed.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        CliProtoKind.NUMBER -> trimmed.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(trimmed)
        CliProtoKind.CSV ->
            trimmed
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeIf(List<String>::isNotEmpty)
                ?.let { items -> JsonArray(items.map(::JsonPrimitive)) }
        else -> JsonPrimitive(trimmed)
    }
}

private fun JsonObject.withEnabledFlag(
    path: List<String>,
    enabled: Boolean,
): JsonObject =
    if (enabled) {
        putAtPath(path + "enabled", JsonPrimitive(true))
    } else {
        putAtPath(path, null)
    }

private val INDEXED_PATH_SEGMENT = Regex("""^(.+)\[(\d+)]$""")

private fun JsonElement?.childAtSegment(segment: String): JsonElement? {
    val parent = this as? JsonObject ?: return null
    val indexed = INDEXED_PATH_SEGMENT.matchEntire(segment) ?: return parent[segment]
    val array = parent[indexed.groupValues[1]] as? JsonArray ?: return null
    return array.getOrNull(indexed.groupValues[2].toInt())
}

private fun JsonObject.elementAtPath(path: List<String>): JsonElement? =
    path.fold<String, JsonElement?>(this) { element, key -> element.childAtSegment(key) }

private fun JsonObject.putAtPath(
    path: List<String>,
    value: JsonElement?,
): JsonObject {
    val key = path.first()
    INDEXED_PATH_SEGMENT.matchEntire(key)?.let { indexed ->
        return putAtIndexedPath(indexed.groupValues[1], indexed.groupValues[2].toInt(), path.drop(1), value)
    }
    if (path.size == 1) {
        return JsonObject(if (value == null) this - key else this + (key to value))
    }
    val child = this[key] as? JsonObject ?: JsonObject(emptyMap())
    val updated = child.putAtPath(path.drop(1), value)
    return JsonObject(if (updated.isEmpty()) this - key else this + (key to updated))
}

private fun JsonObject.putAtIndexedPath(
    key: String,
    index: Int,
    rest: List<String>,
    value: JsonElement?,
): JsonObject {
    val array = this[key] as? JsonArray ?: return this
    val item = array.getOrNull(index) as? JsonObject ?: return this
    if (rest.isEmpty()) {
        return this
    }
    val updated = array.toMutableList().apply { this[index] = item.putAtPath(rest, value) }
    return JsonObject(this + (key to JsonArray(updated)))
}
