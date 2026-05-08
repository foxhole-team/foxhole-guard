package com.foxhole.beta.core.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class EditableTlsConfig(
    val enabled: Boolean = false,
    val serverName: String = "",
    val alpn: String = "",
    val minVersion: String = "",
    val maxVersion: String = "",
    val curvePreferences: String = "",
    val echMode: String = "",
    val fingerprint: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
)

data class EditableTransportConfig(
    val type: String = "tcp",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
)

data class EditableProfileConfig(
    val type: String,
    val nodeCount: Int = 1,
    val server: String = "",
    val port: String = "",
    val username: String = "",
    val uuid: String = "",
    val password: String = "",
    val method: String = "",
    val flow: String = "",
    val packetEncoding: String = "",
    val alterId: String = "",
    val security: String = "",
    val tls: EditableTlsConfig = EditableTlsConfig(),
    val transport: EditableTransportConfig = EditableTransportConfig(),
    val obfs: String = "",
    val obfsPassword: String = "",
    val upMbps: String = "",
    val downMbps: String = "",
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val preSharedKey: String = "",
    val localAddress: String = "",
    val allowedIps: String = "",
    val persistentKeepalive: String = "",
    val naiveQuic: String = "",
    val naiveUdpOverTcp: String = "",
    val naiveQuicCongestionControl: String = "",
)

class ProfileConfigFormCodec(
    private val json: Json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        },
) {
    fun decode(resolvedConfigJson: String): EditableProfileConfig {
        val root = json.parseToJsonElement(resolvedConfigJson).jsonObject
        val outboundRef = locateEditableOutbound(root)
        val outbound = outboundRef.objectValue
        val wireGuardPeer = outbound.wireGuardPeerOrNull()
        val tls = outbound["tls"]?.jsonObject
        val transport = outbound["transport"]?.jsonObject

        return EditableProfileConfig(
            type = outboundRef.type,
            nodeCount = outboundRef.nodeCount,
            server =
                outbound["server"]?.jsonPrimitive?.contentOrNull
                    ?: wireGuardPeer?.get("server")?.jsonPrimitive?.contentOrNull
                    ?: wireGuardPeer?.get("address")?.jsonPrimitive?.contentOrNull.orEmpty(),
            port =
                outbound["server_port"]?.jsonPrimitive?.contentOrNull
                    ?: wireGuardPeer?.get("server_port")?.jsonPrimitive?.contentOrNull
                    ?: wireGuardPeer?.get("port")?.jsonPrimitive?.contentOrNull.orEmpty(),
            username = outbound["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            uuid = outbound["uuid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            password = outbound["password"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            method = outbound["method"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            flow = outbound["flow"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            packetEncoding = outbound["packet_encoding"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            alterId = outbound["alter_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            security = outbound["security"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            tls =
                EditableTlsConfig(
                    enabled = tls != null && (tls["enabled"]?.jsonPrimitive?.booleanOrNull != false),
                    serverName = tls?.get("server_name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    alpn = tls?.get("alpn").toCsv(),
                    minVersion = tls?.get("min_version")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    maxVersion = tls?.get("max_version")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    curvePreferences = tls?.get("curve_preferences").toCsv(),
                    echMode = tls?.get("ech")?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull.toOnOffLabel(),
                    fingerprint =
                        tls
                            ?.get("utls")
                            ?.jsonObject
                            ?.get("fingerprint")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty(),
                    realityPublicKey =
                        tls
                            ?.get("reality")
                            ?.jsonObject
                            ?.get("public_key")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty(),
                    realityShortId =
                        tls
                            ?.get("reality")
                            ?.jsonObject
                            ?.get("short_id")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty(),
                ),
            transport =
                EditableTransportConfig(
                    type = transport?.get("type")?.jsonPrimitive?.contentOrNull ?: "tcp",
                    host = extractTransportHost(transport),
                    path = transport?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    serviceName = transport?.get("service_name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                ),
            obfs = outbound["obfs"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obfsPassword = outbound["obfs_password"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            upMbps = outbound["up_mbps"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            downMbps = outbound["down_mbps"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            privateKey = outbound["private_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            peerPublicKey = outbound["peer_public_key"]?.jsonPrimitive?.contentOrNull ?: wireGuardPeer?.get("public_key")?.jsonPrimitive?.contentOrNull.orEmpty(),
            preSharedKey = outbound["pre_shared_key"]?.jsonPrimitive?.contentOrNull ?: wireGuardPeer?.get("pre_shared_key")?.jsonPrimitive?.contentOrNull.orEmpty(),
            localAddress =
                outbound["local_address"].toCsv().ifBlank {
                    outbound["address"].toCsv()
                },
            allowedIps =
                outbound["allowed_ips"].toCsv().ifBlank {
                    wireGuardPeer?.get("allowed_ips").toCsv()
                },
            persistentKeepalive = outbound["persistent_keepalive_interval"]?.jsonPrimitive?.contentOrNull ?: wireGuardPeer?.get("persistent_keepalive_interval")?.jsonPrimitive?.contentOrNull.orEmpty(),
            naiveQuic = outbound["quic"]?.jsonPrimitive?.booleanOrNull.toOnOffLabel(),
            naiveUdpOverTcp = outbound["udp_over_tcp"].booleanObjectFlag().toOnOffLabel(),
            naiveQuicCongestionControl = outbound["quic_congestion_control"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    fun encode(
        baseResolvedConfigJson: String,
        draft: EditableProfileConfig,
    ): String {
        require(draft.server.trim().isNotBlank()) { "server is required" }
        val port = draft.port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: error("port must be 1-65535")
        validateRequiredFields(draft)

        val root = json.parseToJsonElement(baseResolvedConfigJson).jsonObject
        val target = locateEditableOutbound(root)
        val items = root[target.containerKey]?.jsonArray ?: error("resolved config must define ${target.containerKey}")
        val updatedOutbound = updateOutbound(target.objectValue, draft, port)
        val updatedItems =
            buildJsonArray {
                items.forEachIndexed { index, element ->
                    add(if (index == target.index) updatedOutbound else element)
                }
            }
        val updatedRoot =
            buildJsonObject {
                root.forEach { (key, value) ->
                    if (key == target.containerKey) {
                        put(key, updatedItems)
                    } else {
                        put(key, value)
                    }
                }
            }
        return json.encodeToString(JsonObject.serializer(), updatedRoot)
    }

    private fun validateRequiredFields(draft: EditableProfileConfig) {
        when (draft.type) {
            "vless", "vmess" -> require(draft.uuid.trim().isNotBlank()) { "uuid is required" }
            "trojan", "hysteria2", "shadowsocks" -> require(draft.password.trim().isNotBlank()) { "password is required" }
            "naive" -> require(draft.username.trim().isNotBlank() || draft.password.trim().isNotBlank()) { "naive credentials are required" }
            "wireguard" -> {
                require(draft.privateKey.trim().isNotBlank()) { "private key is required" }
                require(draft.peerPublicKey.trim().isNotBlank()) { "peer public key is required" }
                require(draft.localAddress.trim().isNotBlank()) { "local address is required" }
            }
        }
        if (draft.type == "shadowsocks") {
            require(draft.method.trim().isNotBlank()) { "method is required" }
        }
    }

    private fun updateOutbound(
        outbound: JsonObject,
        draft: EditableProfileConfig,
        port: Int,
    ): JsonObject {
        val map = outbound.toMutableMap()
        when (draft.type) {
            "vless" -> {
                map["server"] = JsonPrimitive(draft.server.trim())
                map["server_port"] = JsonPrimitive(port)
                map["uuid"] = JsonPrimitive(draft.uuid.trim())
                map.putStringOrRemove("flow", draft.flow)
                map.putStringOrRemove("packet_encoding", draft.packetEncoding)
            }
            "trojan" -> {
                map["server"] = JsonPrimitive(draft.server.trim())
                map["server_port"] = JsonPrimitive(port)
                map["password"] = JsonPrimitive(draft.password.trim())
            }
            "shadowsocks" -> {
                map["server"] = JsonPrimitive(draft.server.trim())
                map["server_port"] = JsonPrimitive(port)
                map["method"] = JsonPrimitive(draft.method.trim())
                map["password"] = JsonPrimitive(draft.password.trim())
            }
            "vmess" -> {
                map["server"] = JsonPrimitive(draft.server.trim())
                map["server_port"] = JsonPrimitive(port)
                map["uuid"] = JsonPrimitive(draft.uuid.trim())
                map.putStringOrRemove("security", draft.security)
                map.putIntStringOrRemove("alter_id", draft.alterId)
            }
            "hysteria2" -> {
                map["server"] = JsonPrimitive(draft.server.trim())
                map["server_port"] = JsonPrimitive(port)
                map["password"] = JsonPrimitive(draft.password.trim())
                map.putStringOrRemove("obfs", draft.obfs)
                map.putStringOrRemove("obfs_password", draft.obfsPassword)
                map.putIntStringOrRemove("up_mbps", draft.upMbps)
                map.putIntStringOrRemove("down_mbps", draft.downMbps)
            }
            "wireguard" -> {
                updateWireGuardNode(map, outbound, draft, port)
            }
            "naive" -> {
                map["server"] = JsonPrimitive(draft.server.trim())
                map["server_port"] = JsonPrimitive(port)
                map.putStringOrRemove("username", draft.username)
                map.putStringOrRemove("password", draft.password)
                map.putOptionalBoolean("quic", draft.naiveQuic)
                map.putOptionalBoolean("udp_over_tcp", draft.naiveUdpOverTcp)
                map.putStringOrRemove("quic_congestion_control", draft.naiveQuicCongestionControl)
            }
        }

        updateTls(outbound["tls"]?.jsonObject, draft)?.let { map["tls"] = it } ?: map.remove("tls")
        updateTransport(outbound["transport"]?.jsonObject, draft.transport)?.let { map["transport"] = it } ?: map.remove("transport")
        return JsonObject(map)
    }

    private fun updateTls(
        original: JsonObject?,
        draft: EditableProfileConfig,
    ): JsonObject? {
        if (original == null && !draft.tls.enabled) {
            return null
        }
        val map = original?.toMutableMap() ?: mutableMapOf()
        map["enabled"] = JsonPrimitive(true)
        map["server_name"] = JsonPrimitive(draft.tls.serverName.trim().ifBlank { draft.server.trim() })
        draft.tls.alpn.toJsonArray()?.let { map["alpn"] = it } ?: map.remove("alpn")
        map.putStringOrRemove("min_version", draft.tls.minVersion)
        map.putStringOrRemove("max_version", draft.tls.maxVersion)
        draft.tls.curvePreferences.toJsonArray()?.let { map["curve_preferences"] = it } ?: map.remove("curve_preferences")
        when (draft.tls.echMode.trim().lowercase()) {
            "on", "true", "1", "enabled" ->
                map["ech"] =
                    buildJsonObject {
                        put("enabled", true)
                    }
            "off", "false", "0", "disabled" ->
                map["ech"] =
                    buildJsonObject {
                        put("enabled", false)
                    }
            "auto", "" -> map.remove("ech")
        }

        when (val fingerprint = draft.tls.fingerprint.trim().lowercase()) {
            "", "auto", "off", "false", "0", "disabled" -> map.remove("utls")
            else -> {
                val utls = original?.get("utls")?.jsonObject?.toMutableMap() ?: mutableMapOf()
                utls["enabled"] = JsonPrimitive(true)
                utls["fingerprint"] = JsonPrimitive(fingerprint)
                map["utls"] = JsonObject(utls)
            }
        }

        val originalReality = original?.get("reality")?.jsonObject
        if (originalReality != null || draft.tls.realityPublicKey.isNotBlank() || draft.tls.realityShortId.isNotBlank()) {
            val reality = originalReality?.toMutableMap() ?: mutableMapOf()
            reality["enabled"] = JsonPrimitive(true)
            if (draft.tls.realityPublicKey.isNotBlank()) {
                reality["public_key"] = JsonPrimitive(draft.tls.realityPublicKey.trim())
            }
            if (draft.tls.realityShortId.isNotBlank()) {
                reality["short_id"] = JsonPrimitive(draft.tls.realityShortId.trim())
            } else {
                reality.remove("short_id")
            }
            map["reality"] = JsonObject(reality)
        }

        return JsonObject(map)
    }

    private fun updateTransport(
        original: JsonObject?,
        draft: EditableTransportConfig,
    ): JsonObject? {
        if (original == null) {
            return null
        }
        val map = original.toMutableMap()
        when (draft.type) {
            "ws" -> {
                map["type"] = JsonPrimitive("ws")
                map.putStringOrRemove("path", draft.path)
                val host = draft.host.trim()
                if (host.isBlank()) {
                    map.remove("headers")
                } else {
                    map["headers"] = buildJsonObject { put("Host", host) }
                }
                map.remove("service_name")
            }
            "grpc" -> {
                map["type"] = JsonPrimitive("grpc")
                map.putStringOrRemove("service_name", draft.serviceName)
                map.remove("path")
                map.remove("headers")
                map.remove("host")
            }
            "httpupgrade" -> {
                map["type"] = JsonPrimitive("httpupgrade")
                map.putStringOrRemove("host", draft.host)
                map.putStringOrRemove("path", draft.path)
                map.remove("service_name")
                map.remove("headers")
            }
            "http" -> {
                map["type"] = JsonPrimitive("http")
                map.putStringOrRemove("path", draft.path)
                draft.host.toJsonArray()?.let { map["host"] = it } ?: map.remove("host")
                map.remove("service_name")
                map.remove("headers")
            }
            else -> return original
        }
        return JsonObject(map)
    }

    private fun JsonObject.wireGuardPeerOrNull(): JsonObject? =
        get("peers")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject

    private fun updateWireGuardNode(
        map: MutableMap<String, JsonElement>,
        original: JsonObject,
        draft: EditableProfileConfig,
        port: Int,
    ) {
        val endpointShape =
            original["address"] != null ||
                original.wireGuardPeerOrNull()?.get("address") != null
        map.remove("server")
        map.remove("server_port")
        map["private_key"] = JsonPrimitive(draft.privateKey.trim())
        map.remove("peer_public_key")
        map.remove("pre_shared_key")
        map.remove("allowed_ips")
        map.remove("persistent_keepalive_interval")
        map["peers"] =
            buildJsonArray {
                add(
                    buildJsonObject {
                        if (endpointShape) {
                            put("address", draft.server.trim())
                            put("port", port)
                        } else {
                            put("server", draft.server.trim())
                            put("server_port", port)
                        }
                        put("public_key", draft.peerPublicKey.trim())
                        draft.preSharedKey.trim().takeIf(String::isNotBlank)?.let { put("pre_shared_key", it) }
                        draft.allowedIps.toJsonArray()?.let { put("allowed_ips", it) }
                        draft.persistentKeepalive.trim().toIntOrNull()?.let { put("persistent_keepalive_interval", it) }
                    },
                )
            }
        if (endpointShape) {
            map.remove("local_address")
            map["address"] = draft.localAddress.toJsonArray() ?: error("local address is required")
        } else {
            map.remove("address")
            map["local_address"] = draft.localAddress.toJsonArray() ?: error("local address is required")
        }
    }

    private fun locateEditableOutbound(root: JsonObject): EditableOutboundRef {
        val outbounds = root["outbounds"]?.jsonArray ?: error("resolved config must define outbounds")
        val editableOutbounds =
            outbounds.mapIndexedNotNull { index, element ->
                val objectValue = element.jsonObject
                val type = objectValue["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val server =
                    objectValue["server"]?.jsonPrimitive?.contentOrNull
                        ?: objectValue.wireGuardPeerOrNull()?.get("server")?.jsonPrimitive?.contentOrNull
                if (server.isNullOrBlank() || type == "selector" || type == "direct" || type == "block") {
                    null
                } else {
                    EditableOutboundRef(
                        containerKey = "outbounds",
                        index = index,
                        type = type,
                        tag = objectValue["tag"]?.jsonPrimitive?.contentOrNull,
                        objectValue = objectValue,
                        nodeCount = 0,
                    )
                }
            }
        val editableEndpoints =
            root["endpoints"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
                val objectValue = element.jsonObject
                val type = objectValue["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val server = objectValue.wireGuardPeerOrNull()?.get("address")?.jsonPrimitive?.contentOrNull
                if (server.isNullOrBlank() || type != "wireguard") {
                    null
                } else {
                    EditableOutboundRef(
                        containerKey = "endpoints",
                        index = index,
                        type = type,
                        tag = objectValue["tag"]?.jsonPrimitive?.contentOrNull,
                        objectValue = objectValue,
                        nodeCount = 0,
                    )
                }
            }
        val editable = editableOutbounds + editableEndpoints
        require(editable.isNotEmpty()) { "profile editor supports proxy outbounds only" }

        val selectorDefault =
            outbounds.firstNotNullOfOrNull { element ->
                val objectValue = element.jsonObject
                if (
                    objectValue["type"]?.jsonPrimitive?.contentOrNull == "selector" &&
                    objectValue["tag"]?.jsonPrimitive?.contentOrNull == "proxy"
                ) {
                    objectValue["default"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }

        val target = editable.firstOrNull { it.tag == selectorDefault } ?: editable.first()
        return target.copy(nodeCount = editable.size)
    }

    private fun extractTransportHost(transport: JsonObject?): String {
        val transportType = transport?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (transportType) {
            "ws" ->
                transport
                    ?.get("headers")
                    ?.jsonObject
                    ?.get("Host")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            "httpupgrade" -> transport?.get("host")?.jsonPrimitive?.contentOrNull.orEmpty()
            "http" -> transport?.get("host")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> ""
        }
    }

    private fun JsonElement?.toCsv(): String =
        when (this) {
            is JsonArray -> mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(", ")
            is JsonPrimitive -> contentOrNull.orEmpty()
            else -> ""
        }

    private fun JsonElement?.booleanObjectFlag(): Boolean? =
        when (this) {
            is JsonObject -> true
            is JsonPrimitive -> booleanOrNull
            else -> null
        }

    private fun Boolean?.toOnOffLabel(): String =
        when (this) {
            true -> "on"
            false -> "off"
            null -> ""
        }

    private fun String.toJsonArray(): JsonArray? {
        val values = split(',').map(String::trim).filter(String::isNotBlank)
        if (values.isEmpty()) {
            return null
        }
        return buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun MutableMap<String, JsonElement>.putStringOrRemove(
        key: String,
        value: String,
    ) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            remove(key)
        } else {
            put(key, JsonPrimitive(normalized))
        }
    }

    private fun MutableMap<String, JsonElement>.putIntStringOrRemove(
        key: String,
        value: String,
    ) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            remove(key)
        } else {
            put(key, JsonPrimitive(normalized.toIntOrNull() ?: error("$key must be a number")))
        }
    }

    private fun MutableMap<String, JsonElement>.putOptionalBoolean(
        key: String,
        value: String,
    ) {
        when (value.trim().lowercase()) {
            "on", "true", "1", "enabled" -> put(key, JsonPrimitive(true))
            "off", "false", "0", "disabled" -> put(key, JsonPrimitive(false))
            "", "auto" -> remove(key)
        }
    }

    private data class EditableOutboundRef(
        val containerKey: String,
        val index: Int,
        val type: String,
        val tag: String?,
        val objectValue: JsonObject,
        val nodeCount: Int,
    )
}
