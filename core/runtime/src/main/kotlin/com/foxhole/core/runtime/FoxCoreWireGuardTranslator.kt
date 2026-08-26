package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun wireGuardReservedBytes(
    peer: JsonObject,
    path: String,
): List<Int>? =
    peer["reserved"]?.asFoxCoreArray("$path.reserved")?.let { values ->
        if (values.size != 3) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.reserved")
        }
        values.mapIndexed { index, value ->
            value
                .asFoxCoreInt("$path.reserved[$index]")
                .takeIf { it in 0..255 }
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.reserved[$index]")
        }
    }

internal fun translateFoxCoreWireGuard(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(WIREGUARD_KEYS, path)
    val peers =
        source["peers"]
            ?.asFoxCoreArray("$path.peers")
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.peers")
    if (peers.size != 1) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
            "$path.peers",
        )
    }
    val peer = peers.single().asFoxCoreObject("$path.peers[0]")
    peer.requireOnlyKeys(WIREGUARD_PEER_KEYS, "$path.peers[0]")
    val addresses = source.stringList("address", path)
    val allowedIps = peer.stringList("allowed_ips", "$path.peers[0]")
    if (addresses.isEmpty() || allowedIps.isEmpty()) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    val reserved = wireGuardReservedBytes(peer, "$path.peers[0]")
    val mtu = source.optionalInt("mtu", path) ?: DEFAULT_WIREGUARD_MTU
    val amnezia =
        source["amnezia"]
            ?.asFoxCoreObject("$path.amnezia")
            ?.let { translateFoxCoreAmnezia(it, "$path.amnezia", mtu) }
    return buildJsonObject {
        put("type", "wireguard")
        put("server", peer.requiredString("address", "$path.peers[0]"))
        put("port", peer.requiredPort("port", "$path.peers[0]"))
        put("private_key", source.requiredString("private_key", path))
        put("peer_public_key", peer.requiredString("public_key", "$path.peers[0]"))
        peer.optionalString("pre_shared_key", "$path.peers[0]")?.let {
            put("preshared_key", it)
        }
        put("address", JsonArray(addresses.map(::JsonPrimitive)))
        put("allowed_ips", JsonArray(allowedIps.map(::JsonPrimitive)))
        put("mtu", mtu)
        peer.optionalInt("persistent_keepalive_interval", "$path.peers[0]")?.let {
            if (it !in 0..65_535) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.INVALID_SHAPE,
                    "$path.peers[0].persistent_keepalive_interval",
                )
            }
            put("persistent_keepalive_s", it)
        }
        reserved?.let { put("reserved", JsonArray(it.map(::JsonPrimitive))) }
        amnezia?.let { put("amnezia", it) }
    }
}

private fun amneziaHeaderRanges(
    source: JsonObject,
    path: String,
): List<Pair<Long, Long>> {
    val headers =
        AMNEZIA_HEADER_KEYS.map { key ->
            source[key]?.let { amneziaHeaderRange(it, "$path.$key") } ?: AMNEZIA_HEADER_DEFAULTS.getValue(key)
        }

    headers.indices.forEach { index ->
        headers.drop(index + 1).forEach { other ->
            if (headers[index].first <= other.second && other.first <= headers[index].second) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.header_initiation")
            }
        }
    }
    return headers
}

private fun translateFoxCoreAmnezia(
    source: JsonObject,
    path: String,
    mtu: Int,
): JsonObject {
    source.requireOnlyKeys(AMNEZIA_KEYS, path)
    val junkCount = source.amneziaSize("junk_packet_count", path, MAX_AMNEZIA_JUNK_PACKET_COUNT)
    val junkMin = source.amneziaSize("junk_min_size", path, MAX_AMNEZIA_JUNK_SIZE)
    val junkMax = source.amneziaSize("junk_max_size", path, MAX_AMNEZIA_JUNK_SIZE)
    if (junkCount > 0) {
        val fragments = junkMax >= mtu
        if (junkMin > junkMax || junkMax == 0 || fragments) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.junk_max_size")
        }
    }
    val headers = amneziaHeaderRanges(source, path)
    val initPackets =
        source["init_packets"]?.asFoxCoreArray("$path.init_packets")?.also { packets ->
            if (packets.size > MAX_AMNEZIA_INIT_PACKETS) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.init_packets")
            }
            packets.forEachIndexed { index, packet ->
                requireAmneziaInitPacketRendersBytes(
                    packet.asFoxCoreObject("$path.init_packets[$index]"),
                    "$path.init_packets[$index]",
                )
            }
        }
    return buildJsonObject {
        put("junk_packet_count", junkCount)
        put("junk_min_size", junkMin)
        put("junk_max_size", junkMax)
        put("init_junk_size", source.amneziaSize("init_junk_size", path, MAX_AMNEZIA_JUNK_SIZE))
        put("response_junk_size", source.amneziaSize("response_junk_size", path, MAX_AMNEZIA_JUNK_SIZE))
        put("cookie_junk_size", source.amneziaSize("cookie_junk_size", path, MAX_AMNEZIA_JUNK_SIZE))
        put("transport_junk_size", source.amneziaSize("transport_junk_size", path, MAX_AMNEZIA_JUNK_SIZE))
        AMNEZIA_HEADER_KEYS.forEachIndexed { index, key ->
            val (start, end) = headers[index]
            put(key, if (start == end) JsonPrimitive(start) else JsonPrimitive("$start-$end"))
        }
        initPackets?.let { put("init_packets", it) }
        source["timers"]?.let { put("timers", it.asFoxCoreObject("$path.timers")) }
    }
}

private fun requireAmneziaInitPacketRendersBytes(
    packet: JsonObject,
    path: String,
) {
    packet.requireOnlyKeys(setOf("tags"), path)
    val tags = packet["tags"]?.asFoxCoreArray("$path.tags") ?: rejectFoxCoreConfig(
        FoxCoreConfigRejection.INVALID_SHAPE,
        "$path.tags",
    )
    var rendered = 0
    tags.forEachIndexed { index, element ->
        val tag = element.asFoxCoreObject("$path.tags[$index]")
        val name = tag.requiredString("tag", "$path.tags[$index]")
        rendered +=
            when (name) {
                "bytes" -> {
                    val hex = tag.requiredString("hex", "$path.tags[$index]")
                    if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" }) {
                        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.tags[$index].hex")
                    }
                    hex.length / 2
                }

                "timestamp" -> AMNEZIA_TIMESTAMP_TAG_BYTES
                "random", "random_letters", "random_digits", "payload_size" ->
                    tag.amneziaSize("len", "$path.tags[$index]", MAX_AMNEZIA_JUNK_SIZE)

                "payload", "payload_base64" -> 0
                else -> rejectFoxCoreConfig(FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED, "$path.tags[$index]")
            }
    }
    if (rendered == 0 || rendered > MAX_AMNEZIA_JUNK_SIZE) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
}

private fun amneziaHeaderRange(
    value: JsonElement,
    path: String,
): Pair<Long, Long> {
    val primitive = value as? JsonPrimitive ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    if (primitive.isString) {
        val text = primitive.content
        val separator = text.indexOf('-')
        if (separator < 0) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
        }
        val start = text.substring(0, separator).toLongOrNull()
        val end = text.substring(separator + 1).toLongOrNull()
        val bothParsed = start != null && end != null
        val bothInRange = bothParsed && start in 0..U32_MAX && end in 0..U32_MAX
        if (!bothInRange || end < start) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
        }
        return start to end
    }
    val single = primitive.content.toLongOrNull()
    if (single == null || single !in 0..U32_MAX) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return single to single
}

private fun JsonObject.amneziaSize(
    key: String,
    path: String,
    limit: Int,
): Int {
    val value = optionalInt(key, path) ?: return 0
    if (value !in 0..limit) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.$key")
    }
    return value
}

private const val DEFAULT_WIREGUARD_MTU = 1420

private const val MAX_AMNEZIA_JUNK_SIZE = 1280
private const val MAX_AMNEZIA_JUNK_PACKET_COUNT = 128
private const val MAX_AMNEZIA_INIT_PACKETS = 5
private const val AMNEZIA_TIMESTAMP_TAG_BYTES = 4
private const val U32_MAX = 4_294_967_295L

private val AMNEZIA_HEADER_KEYS =
    listOf("header_initiation", "header_response", "header_cookie", "header_transport")

private val AMNEZIA_HEADER_DEFAULTS =
    mapOf(
        "header_initiation" to (1L to 1L),
        "header_response" to (2L to 2L),
        "header_cookie" to (3L to 3L),
        "header_transport" to (4L to 4L),
    )

private val AMNEZIA_KEYS =
    setOf(
        "junk_packet_count",
        "junk_min_size",
        "junk_max_size",
        "init_junk_size",
        "response_junk_size",
        "cookie_junk_size",
        "transport_junk_size",
        "init_packets",
        "timers",
    ) + AMNEZIA_HEADER_KEYS

private val WIREGUARD_KEYS =
    setOf(
        "type",
        "tag",
        "private_key",
        "address",
        "mtu",
        "peers",
        "amnezia",
        "listen_port",
    )

private val WIREGUARD_PEER_KEYS =
    setOf(
        "address",
        "port",
        "public_key",
        "pre_shared_key",
        "allowed_ips",
        "persistent_keepalive_interval",
        "reserved",
    )
