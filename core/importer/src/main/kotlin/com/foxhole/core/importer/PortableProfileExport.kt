package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CancellationException

fun exportPortableConfigs(profileName: String, configs: List<String>): String = try {
    require(configs.isNotEmpty() && configs.size <= MAX_PORTABLE_CONFIGS)
    require(configs.sumOf { it.length.toLong() } <= MAX_PORTABLE_BYTES)
    require(configs.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_PORTABLE_BYTES)
    val writer = PortableProfileWriter()
    configs.map { writer.write(profileName, it) }.distinct().joinToString("\n").also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_PORTABLE_BYTES)
    }
} catch (error: CancellationException) {
    throw error
} catch (_: RuntimeException) {
    throw PortableProfileExportFailure()
}

private class PortableProfileWriter {
    private val json = Json
    private val parser = ProfileImportParser(json)

    fun write(name: String, raw: String): String {
        require(raw.length <= MAX_PORTABLE_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_PORTABLE_BYTES)
        requireJsonDepth(raw)
        val root = json.parseToJsonElement(raw).jsonObject
        val selected = selectedNode(root)
        validateCopyEncryption(selected)
        normalizeProfileBootstrapDns(root)?.let { bootstrap ->
            return projectedJsonCopy(JsonObject(root + ("bootstrap_dns" to bootstrap)), selected)
        }
        val transport = selected["transport"] as? JsonObject
        if (
            transport?.string("type") == "grpc" && transport.keys.any {
                it in setOf(
                    "rpc_path",
                    "user_agent",
                    "idle_timeout",
                    "idle_timeout_ms",
                    "health_check_timeout",
                    "ping_timeout",
                    "health_check_timeout_ms",
                    "permit_without_stream",
                    "initial_windows_size"
                )
            }
        ) {
            // Standard share links cannot express every carrier tuning/path override.
            // Preserve the selected normalized JSON rather than emit a lossy URI.
            ProfileImportGrpcSupport.canonical(transport, singBoxPath = false)
            return projectedJsonCopy(root, selected)
        }
        val label = name.filterNot(Char::isISOControl).take(160).ifBlank { "VPN" }
        return when {
            selected.string("type") == "vless" && selected["encryption"] != null ->
                encryptedVlessJsonCopy(label, root, selected)
            selected.string("type") == "shadowsocks" && hasShadowsocksCarrier(selected) -> {
                requireSip003CopySubset(selected)
                projectedJsonCopy(root, selected)
            }
            else -> {
                val link = link(label, selected, root)
                val imported =
                    requireNotNull(
                        parser.parseUserInput(
                            link,
                            allowPrivateOutboundHosts = true,
                            allowInsecureTls = true
                        ).normalizedConfigJson
                    )
                // Only topology labels and the explicitly unselected alternatives may differ from a portable link.
                require(comparable(project(root, selected)) == comparable(json.parseToJsonElement(imported).jsonObject))
                link
            }
        }
    }

    private fun projectedJsonCopy(root: JsonObject, selected: JsonObject): String {
        val projected = project(root, selected)
        val copied =
            requireNotNull(
                parser.parseUserInput(
                    projected.toString(),
                    allowPrivateOutboundHosts = true,
                    allowInsecureTls = true,
                ).normalizedConfigJson,
            )
        require(comparable(projected) == comparable(json.parseToJsonElement(copied).jsonObject))
        return projected.toString()
    }

    private fun validateCopyEncryption(selected: JsonObject) {
        if (selected.string("type") != "vless") return
        val raw = selected["encryption"] ?: return
        val value = requireNotNull(raw as? JsonPrimitive)
        require(value.isString && isClientVlessEncryptionSpec(value.content))
        require(selected.string("flow").isNullOrBlank())
    }

    private fun encryptedVlessJsonCopy(name: String, root: JsonObject, selected: JsonObject): String {
        // Every field other than the newly supported encryption must retain the old lossless URI proof.
        val plain =
            requireNotNull(
                parser.parseUserInput(
                    link(name, selected, root),
                    allowPrivateOutboundHosts = true,
                    allowInsecureTls = true,
                ).normalizedConfigJson,
            )
        val expected = withoutVlessEncryption(project(root, selected))
        require(comparable(expected) == comparable(json.parseToJsonElement(plain).jsonObject))
        return projectedJsonCopy(root, selected)
    }

    private fun withoutVlessEncryption(root: JsonObject): JsonObject =
        JsonObject(
            root.mapValues { (key, value) ->
                if (key in setOf("outbounds", "endpoints")) {
                    JsonArray(
                        value.jsonArray.map { element ->
                            val node = element.jsonObject
                            if (node.string("type") == "vless") JsonObject(node - "encryption") else node
                        },
                    )
                } else {
                    value
                }
            },
        )

    private fun hasShadowsocksCarrier(node: JsonObject): Boolean = node["transport"] != null || node["tls"] != null

    private fun requireSip003CopySubset(node: JsonObject) {
        require(node.keys.all { it in SIP003_COPY_NODE_KEYS })
        require(node.string("network") == "tcp")
        val transport = requireNotNull(node["transport"] as? JsonObject)
        require(transport.keys == setOf("type", "path", "headers"))
        require(transport["type"] == JsonPrimitive("ws"))
        val path = requireNotNull(transport["path"] as? JsonPrimitive)
        require(path.isString && validSip003Path(path.content))
        val headers = requireNotNull(transport["headers"] as? JsonObject)
        require(headers.keys == setOf("Host"))
        val host = requireNotNull(headers["Host"] as? JsonPrimitive)
        require(host.isString && validSip003Host(host.content))
        val tls = requireNotNull(node["tls"] as? JsonObject)
        requireSip003TlsCopy(tls, host)
    }

    private fun requireSip003TlsCopy(tls: JsonObject, host: JsonPrimitive) {
        when (tls["enabled"]) {
            JsonPrimitive(true) -> {
                require(tls.keys == setOf("enabled", "server_name"))
                require(tls["server_name"] == host)
            }
            JsonPrimitive(false) -> require(tls.keys == setOf("enabled"))
            else -> error("unsupported portable TLS shape")
        }
    }

    private fun selectedNode(root: JsonObject): JsonObject {
        val final = (root["route"] as? JsonObject)?.string("final") ?: error("route is missing")
        val all = definitions(root)
        require(all.map { it.string("tag") }.distinct().size == all.size)
        val target = all.single { it.string("tag") == final }
        return if (target.string("type") == "selector") {
            val tags = requireNotNull(target["outbounds"] as? JsonArray).map { it.jsonPrimitive.content }
            val chosen = target.string("default") ?: tags.first()
            require(chosen in tags)
            all.single { it.string("tag") == chosen }
        } else {
            target
        }
    }

    private fun project(root: JsonObject, node: JsonObject): JsonObject {
        val finalTag = root["route"]!!.jsonObject.string("final")
        val selectedTag = node.string("tag")!!
        val projected = JsonObject(
            root.mapValues { (key, value) ->
                if (key == "outbounds" || key == "endpoints") {
                    JsonArray(
                        value.jsonArray.mapNotNull { element ->
                            val item = element.jsonObject
                            when {
                                item.string("type") in PROTOCOLS && item != node -> null
                                item.string("type") == "selector" && item.string("tag") == finalTag ->
                                    JsonObject(
                                        item + mapOf("default" to JsonPrimitive(selectedTag), "outbounds" to JsonArray(listOf(JsonPrimitive(selectedTag))))
                                    )
                                else -> item
                            }
                        }
                    )
                } else {
                    value
                }
            }
        )
        val bootstrap = root["bootstrap_dns"] as? JsonObject ?: return projected
        val retainedHosts = bootstrapNodeHosts(projected).map { it.first }.toSet()
        val domains = requireNotNull(bootstrap["domains"] as? JsonArray).filter {
            it.jsonPrimitive.content in retainedHosts
        }
        return if (domains.isEmpty()) {
            JsonObject(projected - "bootstrap_dns")
        } else {
            JsonObject(projected + ("bootstrap_dns" to JsonObject(bootstrap + ("domains" to JsonArray(domains)))))
        }
    }

    private fun comparable(root: JsonObject): JsonObject {
        val nodeTag = selectedNode(root).string("tag")!!
        val final = root["route"]!!.jsonObject.string("final")!!
        fun label(tag: String): String = when (tag) {
            nodeTag -> "portable-node"
            final -> "portable-selector"
            else -> tag
        }
        val result = root.toMutableMap()
        listOf("outbounds", "endpoints").forEach { key ->
            (root[key] as? JsonArray)?.let { values ->
                result[key] = JsonArray(values.map { comparableDefinition(it.jsonObject, ::label) })
            }
        }
        result["route"] = JsonObject(root["route"]!!.jsonObject + ("final" to JsonPrimitive(label(final))))
        (root["dns"] as? JsonObject)?.let { result["dns"] = comparableDns(it, ::label) }
        return JsonObject(result)
    }

    private fun comparableDefinition(obj: JsonObject, label: (String) -> String): JsonObject {
        val mapped = obj.toMutableMap()
        obj.string("tag")?.let { mapped["tag"] = JsonPrimitive(label(it)) }
        if (obj.string("type") == "selector") {
            obj.string("default")?.let { mapped["default"] = JsonPrimitive(label(it)) }
            (obj["outbounds"] as? JsonArray)?.let { targets ->
                mapped["outbounds"] = JsonArray(targets.map { JsonPrimitive(label(it.jsonPrimitive.content)) })
            }
        }
        (obj["transport"] as? JsonObject)?.takeIf { it.string("type") == "grpc" }?.let {
            mapped["transport"] = ProfileImportGrpcSupport.canonical(it, singBoxPath = false)
        }
        return JsonObject(mapped)
    }

    private fun comparableDns(dns: JsonObject, label: (String) -> String): JsonObject {
        val servers = dns["servers"] as? JsonArray ?: return dns
        return JsonObject(
            dns + (
                "servers" to JsonArray(
                    servers.map { element ->
                        val obj = element as? JsonObject
                        val detour = obj?.string("detour")
                        if (obj == null || detour == null) element else JsonObject(obj + ("detour" to JsonPrimitive(label(detour))))
                    }
                )
                )
        )
    }

    private fun link(name: String, node: JsonObject, root: JsonObject): String {
        val type = node.string("type") ?: error("protocol is missing")
        val host = node.string("server") ?: if (type == "wireguard") return wireGuard(name, node, root) else error("host is missing")
        val port = node.string("server_port") ?: "443"
        val authority = if (host.contains(':')) "[$host]" else host
        val query = linkedMapOf<String, String>()
        tls(node["tls"] as? JsonObject, query)
        transport(node["transport"] as? JsonObject, query)
        protocolQuery(type, node, query)
        if (type == "vmess") return vmess(name, node, query)
        val credentials = when (type) {
            "vless" -> encode(node.string("uuid")!!)
            "tuic" -> encode(node.string("uuid")!!) + node.string("password")?.let { ":${encode(it)}" }.orEmpty()
            "naive" -> encode(node.string("username").orEmpty()) + ":" + encode(node.string("password").orEmpty())
            "shadowsocks" -> Base64.getUrlEncoder().withoutPadding().encodeToString(
                "${node.string("method")!!}:${node.string("password")!!}".toByteArray(Charsets.UTF_8)
            )
            else -> encode(node.string("password")!!)
        }
        val scheme = if (type == "shadowsocks") "ss" else type
        return "$scheme://$credentials@$authority:$port${query(query)}#${encode(name)}"
    }

    private fun protocolQuery(type: String, node: JsonObject, query: MutableMap<String, String>) {
        when (type) {
            "vless" -> {
                query["encryption"] = "none"
                copy(node, query, "flow", "network", "packet_encoding")
            }
            "trojan" -> if (node["tls"] == null) query["security"] = "none"
            "tuic" -> copy(node, query, "congestion_control", "udp_relay_mode")
            "naive" -> {
                copy(
                    node,
                    query,
                    "quic",
                    "udp_over_tcp",
                    "quic_congestion_control"
                )
                query.remove("security")
            }
            "hysteria2" -> hysteriaQuery(node, query)
            "shadowsocks" -> if (node.string("network") == "tcp") query["udp"] = "false"
            "vmess", "anytls" -> Unit
            else -> error("unsupported portable protocol")
        }
    }

    private fun hysteriaQuery(node: JsonObject, query: MutableMap<String, String>) {
        query.remove("security")
        copy(node, query, "hop_interval", "up_mbps", "down_mbps")
        (node["server_ports"] as? JsonArray)?.let { ports ->
            query["server_ports"] = ports.joinToString(",") {
                it.jsonPrimitive.content
            }
        }
        (node["obfs"] as? JsonObject)?.let { obfs ->
            query["obfs"] = obfs.string("type")!!
            query["obfs-password"] = obfs.string("password")!!
        }
    }

    private fun tls(tls: JsonObject?, query: MutableMap<String, String>) {
        if (tls == null) return
        require(tls["enabled"] == JsonPrimitive(true))
        query["security"] = "tls"
        tls.string("server_name")?.let { query["sni"] = it }
        tls.string("insecure")?.let { query["insecure"] = it }
        copy(tls, query, "min_version", "max_version")
        listOf(
            "alpn",
            "curve_preferences"
        ).forEach { key ->
            (tls[key] as? JsonArray)?.let {
                query[key] = it.joinToString(",") { value -> value.jsonPrimitive.content }
            }
        }
        (tls["ech"] as? JsonObject)?.string("enabled")?.let { query["ech"] = it }
        (tls["utls"] as? JsonObject)?.let { utls ->
            require(
                utls["enabled"] == JsonPrimitive(true)
            )
            query["fp"] = utls.string("fingerprint")!!
        }
        (tls["reality"] as? JsonObject)?.let { reality ->
            require(reality["enabled"] == JsonPrimitive(true))
            query["security"] = "reality"
            query["pbk"] = reality.string("public_key")!!
            reality.string("short_id")?.let { query["sid"] = it }
        }
        (tls["certificate_public_key_sha256"] as? JsonArray)?.let {
            query["pinned_spki_sha256"] = it.joinToString(",") { value -> value.jsonPrimitive.content }
        }
        (tls["certificate_sha256"] as? JsonArray)?.let {
            require(it.size == 1) { "portable certificate pin must be singular" }
            val bytes = Base64.getDecoder().decode(it.single().jsonPrimitive.content)
            require(bytes.size == 32) { "invalid certificate fingerprint" }
            query["pinSHA256"] = bytes.joinToString("") { value -> (value.toInt() and 255).toString(16).padStart(2, '0') }
        }
    }

    private fun transport(transport: JsonObject?, query: MutableMap<String, String>) {
        if (transport == null) return
        query["type"] = transport.string("type")!!
        transport.string("path")?.let { query["path"] = it }
        transport.string("service_name")?.let { query["serviceName"] = it }
        if (transport.string("type") == "grpc") {
            transport.string("authority")?.let { query["authority"] = it }
            transport["multi_mode"]?.let { mode ->
                require(mode == JsonPrimitive(true) || mode == JsonPrimitive(false))
                query["mode"] = if (mode == JsonPrimitive(true)) "multi" else "gun"
            }
        }
        when (val host = transport["host"]) {
            is JsonArray -> {
                require(host.size == 1)
                query["host"] = host.single().jsonPrimitive.content
            }
            is JsonPrimitive -> query["host"] = host.content
            else -> Unit
        }
        (transport["headers"] as? JsonObject)?.string("Host")?.let { query["host"] = it }
    }

    private fun vmess(name: String, node: JsonObject, query: Map<String, String>): String {
        val value = buildJsonObject {
            put(
                "v",
                "2"
            )
            put(
                "ps",
                name
            )
            put(
                "add",
                node.string("server")!!
            )
            put("port", node.string("server_port")!!)
            put("id", node.string("uuid")!!)
            node.string("alter_id")?.let { put("aid", it) }
            node.string("security")?.let { put("scy", it) }
            query["type"]?.let { put("net", it) }
            (query["serviceName"] ?: query["path"])?.let { put("path", it) }
            (if (query["type"] in setOf("grpc", "gun")) query["authority"] else query["host"])?.let { put("host", it) }
            if (query["type"] in setOf("grpc", "gun")) put("type", query["mode"] ?: "gun")
            query["security"]?.let { put("tls", it) }
            listOf("sni", "fp", "alpn", "insecure").forEach { key -> query[key]?.let { put(key, it) } }
        }
        return "vmess://" + Base64.getEncoder().encodeToString(value.toString().toByteArray(Charsets.UTF_8))
    }

    private fun wireGuard(name: String, node: JsonObject, root: JsonObject): String {
        val peer = node["peers"]!!.jsonArray.single().jsonObject
        val host = peer.string("address")!!
        val query =
            linkedMapOf(
                "publickey" to peer.string("public_key")!!,
                "address" to node["address"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content }
            )
        peer.string("pre_shared_key")?.let { query["presharedkey"] = it }
        peer.string("persistent_keepalive_interval")?.let { query["keepalive"] = it }
        (peer["allowed_ips"] as? JsonArray)?.let {
            query["allowed_ips"] = it.joinToString(",") { value -> value.jsonPrimitive.content }
        }
        (peer["reserved"] as? JsonArray)?.let {
            query["reserved"] = it.joinToString(",") { value -> value.jsonPrimitive.content }
        }
        node.string("mtu")?.let { query["mtu"] = it }
        wireGuardDns(root)?.let { query["dns"] = it }
        return "wireguard://${encode(
            node.string("private_key")!!
        )}@${if (host.contains(':')) "[$host]" else host}:${peer.string("port")!!}${query(query)}#${encode(name)}"
    }

    private fun wireGuardDns(root: JsonObject): String? {
        val servers = (root["dns"] as? JsonObject)?.get("servers") as? JsonArray ?: return null
        val matching = servers.filter { it is JsonObject && it.string("tag") == WIREGUARD_DNS_SERVER_TAG }
        if (matching.isEmpty()) return null
        val record = matching.single().jsonObject
        require(record.string("type") == "udp")
        val host = requireNotNull(record.string("server"))
        val port = record.string("server_port") ?: "53"
        return "${if (host.contains(':')) "[$host]" else host}:$port"
    }

    private fun copy(source: JsonObject, query: MutableMap<String, String>, vararg keys: String) = keys.forEach { key ->
        source.string(
            key
        )?.let { query[key] = it }
    }
    private fun query(
        values: Map<String, String>
    ) = if (values.isEmpty()) {
        ""
    } else {
        values.entries.joinToString(
            "&",
            prefix = "?"
        ) { "${encode(it.key)}=${encode(it.value)}" }
    }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

private val SIP003_COPY_NODE_KEYS =
    setOf("type", "tag", "server", "server_port", "method", "password", "network", "transport", "tls")
