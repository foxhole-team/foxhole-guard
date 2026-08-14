package com.foxhole.guard.ui.cli.profiles

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The field catalog of the structured protocol editor — the reverse of the importer's per-type
 * outbound builders (`ProfileImportNodeSupport` / `ProfileImportQuicNodeSupport` / `buildTls` /
 * `buildTransport`). Each [CliProtoField] is a path into one normalized outbound object, so reading
 * and writing never rebuild the outbound: a write replaces exactly one leaf and leaves every other
 * key — advanced, per-type or plain unknown — untouched. A blank value removes the leaf (and any
 * container the removal emptied), which is how "unset" is expressed in normalized configs.
 */
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

private val UTLS_FINGERPRINTS =
    listOf("", "chrome", "firefox", "edge", "safari", "ios", "android", "random")
private val VMESS_SECURITIES =
    listOf("", "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305")
private val TRANSPORT_TYPES = listOf("", "ws", "grpc", "httpupgrade", "http")

/** Outbound types that get a full field form; everything else falls back to manual text editing. */
internal val CLI_STRUCTURED_OUTBOUND_TYPES =
    setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls", "naive")

/** The types a blank protocol can be created as — the structured ones, in a stable order. */
internal val CLI_NEW_OUTBOUND_TYPES = CLI_STRUCTURED_OUTBOUND_TYPES.sorted()

private val TRANSPORT_CAPABLE_TYPES = setOf("vless", "vmess", "trojan")
private val TLS_CAPABLE_TYPES =
    setOf("vless", "vmess", "trojan", "hysteria2", "tuic", "anytls", "naive")

/** True for the one field whose edit must go through [writeCliTransportType]. */
internal fun CliProtoField.isTransportType(): Boolean = path == TRANSPORT_TYPE_PATH

/** The visible, editable field list of one outbound, given its type and current transport type. */
internal fun cliProtoFields(
    type: String,
    transportType: String,
): List<CliProtoField> =
    buildList {
        addAll(commonFields())
        addAll(authFields(type))
        if (type in TRANSPORT_CAPABLE_TYPES) {
            add(transportTypeField())
            addAll(transportDetailFields(transportType))
        }
        if (type in TLS_CAPABLE_TYPES) {
            addAll(tlsFields())
        }
    }

private fun commonFields(): List<CliProtoField> =
    listOf(
        CliProtoField("server", listOf("server"), CliProtoKind.TEXT, CliProtoGroup.COMMON),
        CliProtoField("server_port", listOf("server_port"), CliProtoKind.NUMBER, CliProtoGroup.COMMON),
        CliProtoField("tag", listOf("tag"), CliProtoKind.TEXT, CliProtoGroup.COMMON),
    )

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
        else -> emptyList()
    }

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

/**
 * Writes one field back into the outbound. Beyond the leaf itself this keeps the two normalized
 * sub-objects consistent that the importer also fills implicitly: `tls.utls` and `tls.reality`
 * carry an `enabled` flag next to the value the form edits, and clearing the value drops the whole
 * sub-object instead of leaving `{"enabled": true}` behind.
 */
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

/**
 * Transport switch: drops the leaves of the variant being left (ws `path`/`headers.Host`, grpc
 * `service_name`, …) so a stale key cannot contradict the new type, while any transport key this
 * editor does not model (`max_early_data`, extra headers) stays exactly where it was.
 */
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

private fun JsonObject.elementAtPath(path: List<String>): JsonElement? =
    path.fold<String, JsonElement?>(this) { element, key -> (element as? JsonObject)?.get(key) }

/** Replaces (or removes, on a null [value]) one leaf, pruning containers the removal emptied. */
private fun JsonObject.putAtPath(
    path: List<String>,
    value: JsonElement?,
): JsonObject {
    val key = path.first()
    if (path.size == 1) {
        return JsonObject(if (value == null) this - key else this + (key to value))
    }
    val child = this[key] as? JsonObject ?: JsonObject(emptyMap())
    val updated = child.putAtPath(path.drop(1), value)
    return JsonObject(if (updated.isEmpty()) this - key else this + (key to updated))
}
