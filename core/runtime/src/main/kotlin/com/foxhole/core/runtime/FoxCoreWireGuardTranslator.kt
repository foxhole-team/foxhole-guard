package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val reserved =
        peer["reserved"]?.asFoxCoreArray("$path.peers[0].reserved")?.let { values ->
            if (values.size != 3) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.INVALID_SHAPE,
                    "$path.peers[0].reserved",
                )
            }
            values.mapIndexed { index, value ->
                value
                    .asFoxCoreInt("$path.peers[0].reserved[$index]")
                    .takeIf { it in 0..255 }
                    ?: rejectFoxCoreConfig(
                        FoxCoreConfigRejection.INVALID_SHAPE,
                        "$path.peers[0].reserved[$index]",
                    )
            }
        }
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
        put("mtu", source.optionalInt("mtu", path) ?: DEFAULT_WIREGUARD_MTU)
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
    }
}

private const val DEFAULT_WIREGUARD_MTU = 1420

private val WIREGUARD_KEYS =
    setOf(
        "type",
        "tag",
        "private_key",
        "address",
        "mtu",
        "peers",
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
