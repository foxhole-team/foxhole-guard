package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryReport
import com.foxhole.core.model.SubscriptionEntryStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CancellationException

internal fun requireClashOnlyDocument(root: JsonObject) {
    require(!root.containsKey("bootstrap_dns")) { "bootstrap DNS requires canonical profile JSON" }
    require(
        root.keys.none { it in CLASH_CONFLICTING_FIELDS } &&
            !root.keys.containsAll(setOf("server", "server_port", "method", "password"))
    ) { "mixed profile formats are not supported" }
}

private val CLASH_CONFLICTING_FIELDS = setOf(
    "log", "inbounds", "outbounds", "endpoints", "route", "experimental",
    "schema", "config", "configs", "profiles", "servers",
)

internal fun ProfileImportNodeSupport.parseClashNodeLines(
    raw: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ParsedNodeLines? {
    val yamlCandidate = Regex("""^\s*proxies\s*:""", RegexOption.MULTILINE).containsMatchIn(raw)
    if (!yamlCandidate && !raw.trimStart().startsWith('{')) return null
    return try {
        val root = parseBoundedProfileYaml(raw) as? JsonObject
        if (!yamlCandidate && root?.containsKey("proxies") != true) return null
        parseClashDocument(
            requireNotNull(root) { "invalid Clash document" },
            allowPrivateOutboundHosts,
            allowInsecureTls
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        throw clashInputFailure(error)
    }
}

private fun ProfileImportNodeSupport.parseClashDocument(
    root: JsonObject,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ParsedNodeLines {
    requireClashOnlyDocument(root)
    require(root["proxy-providers"] == null) { "external Clash providers are not supported" }
    val definitions = root["proxies"] as? JsonArray ?: error("Clash proxies are missing")
    require(definitions.size in 1..MAX_SUBSCRIPTION_ENTRY_LINES) { "invalid Clash proxy count" }
    val nodes = mutableListOf<ProfileImportCoreSupport.ProxyNode>()
    val reports = mutableListOf<SubscriptionEntryReport>()
    definitions.forEachIndexed { index, element ->
        val node = element as? JsonObject ?: error("invalid Clash proxy")
        val type = node.clashString("type").lowercase()
        if (type !in setOf("ss", "vless", "vmess", "trojan", "hysteria2", "hy2", "anytls")) {
            reports += SubscriptionEntryReport("UNKNOWN", ProtocolHint.UNKNOWN, SubscriptionEntryStatus.IGNORED_UNSUPPORTED, index + 1, "unsupported protocol")
        } else {
            val host = node.clashString("server")
            validateOutboundHost(host, allowPrivateOutboundHosts)
            val converted = parseSingleNode(clashShareLink(node, type), allowPrivateOutboundHosts, allowInsecureTls)
            nodes += converted
            reports += SubscriptionEntryReport(type.uppercase(), converted.protocolHint, SubscriptionEntryStatus.ACCEPTED, index + 1)
        }
    }
    require(nodes.isNotEmpty()) { "Clash contains no supported proxies" }
    if (root.keys.any { it != "proxies" }) {
        reports += SubscriptionEntryReport("CLASH_SETTINGS", ProtocolHint.UNKNOWN, SubscriptionEntryStatus.IGNORED_UNSUPPORTED, 1, "client settings are not imported")
    }
    return ParsedNodeLines(nodes, reports)
}

private fun JsonObject.clashString(key: String, default: String? = null): String =
    if (containsKey(key)) {
        (this[key] as? JsonPrimitive)?.content ?: error("invalid Clash field")
    } else {
        default ?: error("missing Clash field")
    }

private fun JsonObject.clashBool(key: String, default: Boolean = false): Boolean =
    this[key]?.let { element ->
        when ((element as? JsonPrimitive)?.content?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> error("invalid Clash boolean")
        }
    } ?: default

private fun escapeClash(value: String): String = URLEncoder.encode(
    value,
    StandardCharsets.UTF_8.name()
).replace("+", "%20")

private val CLASH_COMMON_FIELDS = setOf("name", "type", "server", "port", "udp")
private val CLASH_TLS_FIELDS = setOf("tls", "servername", "sni", "skip-cert-verify")
private val CLASH_TRANSPORT_FIELDS = setOf("network", "ws-opts", "grpc-opts")

private fun clashInputFailure(error: RuntimeException): IllegalArgumentException {
    // Parser exceptions can contain profile credentials; expose only the failure kind.
    val message = if (error.message.orEmpty().contains("INSECURE TLS")) {
        "INSECURE TLS is not allowed"
    } else {
        "invalid or unsupported clash profile"
    }
    return IllegalArgumentException(message)
}

private fun clashShareLink(node: JsonObject, type: String): String {
    val allowedFields = CLASH_COMMON_FIELDS + clashProtocolFields(type)
    require(node.keys.all { it in allowedFields }) { "unsupported Clash proxy field" }
    val host = node.clashString("server").let { if (it.contains(':')) "[$it]" else it }
    val port = node.clashString("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid Clash port")
    val name = escapeClash(node.clashString("name", "VPN").filterNot(Char::isISOControl).take(160))
    if (type == "ss") return clashShadowsocksLink(node, host, port, name)
    validateClashProtocolOptions(node, type)
    val params = clashTlsParameters(node)
    val network = appendClashTransport(node, params)
    appendClashSecurity(node, type, params)
    if (type in setOf("hysteria2", "hy2")) appendClashHysteriaParameters(node, params)
    if (type == "vmess") return clashVmessLink(node, port, network, params)
    val auth = escapeClash(node.clashString(if (type == "vless") "uuid" else "password"))
    return "$type://$auth@$host:$port?" + params.entries.joinToString("&") {
        "${escapeClash(it.key)}=${escapeClash(it.value)}"
    } + "#$name"
}

private fun clashProtocolFields(type: String): Set<String> = when (type) {
    "ss" -> setOf("cipher", "password")
    "vmess" -> setOf("uuid", "alterId", "cipher", "alpn", "client-fingerprint") + CLASH_TLS_FIELDS + CLASH_TRANSPORT_FIELDS
    "vless" -> setOf("uuid", "alpn", "client-fingerprint", "flow", "reality-opts", "packet-encoding") + CLASH_TLS_FIELDS + CLASH_TRANSPORT_FIELDS
    "trojan" -> setOf("password", "alpn") + CLASH_TLS_FIELDS + CLASH_TRANSPORT_FIELDS
    "anytls" -> setOf("password") + CLASH_TLS_FIELDS
    else -> setOf("password", "obfs", "obfs-password", "up", "down", "alpn") + CLASH_TLS_FIELDS
}

private fun clashShadowsocksLink(node: JsonObject, host: String, port: Int, name: String): String {
    val auth = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "${node.clashString("cipher")}:${node.clashString("password")}".toByteArray(Charsets.UTF_8)
    )
    return "ss://$auth@$host:$port?udp=${node.clashBool("udp", true)}#$name"
}

private fun validateClashProtocolOptions(node: JsonObject, type: String) {
    require(node.clashBool("udp", true)) { "Clash UDP disabling is not supported for this protocol" }
    if (type in setOf("trojan", "hysteria2", "hy2", "anytls")) {
        require(node.clashBool("tls", true)) { "this Clash protocol requires TLS" }
    }
    if (type == "vmess") {
        require(node["reality-opts"] == null && node["flow"] == null && node["packet-encoding"] == null) {
            "unsupported VMess options"
        }
    }
}

private fun clashTlsParameters(node: JsonObject): LinkedHashMap<String, String> {
    val params = linkedMapOf<String, String>()
    require(node["servername"] == null || node["sni"] == null || node["servername"] == node["sni"]) {
        "conflicting Clash SNI fields"
    }
    (node["servername"] ?: node["sni"])?.let {
        params["sni"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash SNI")
    }
    if (node.clashBool("skip-cert-verify")) params["insecure"] = "1"
    node["alpn"]?.let { params["alpn"] = clashAlpn(it).joinToString(",") }
    if (params["alpn"].isNullOrEmpty()) params.remove("alpn")
    node["client-fingerprint"]?.let { params["fp"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash fingerprint") }
    return params
}

private fun clashAlpn(element: kotlinx.serialization.json.JsonElement): List<String> {
    val values = (element as? JsonArray)?.map {
        (it as? JsonPrimitive)?.content ?: error("invalid Clash ALPN")
    } ?: error("invalid Clash ALPN")
    require(
        values.all {
            it.isNotEmpty() && it.length <= 255 && ',' !in it && it.none(Char::isISOControl)
        }
    ) { "invalid Clash ALPN" }
    return values
}

private fun appendClashTransport(node: JsonObject, params: MutableMap<String, String>): String {
    val network = node.clashString("network", "tcp").lowercase()
    require(network in setOf("tcp", "ws", "grpc", "gun")) { "unsupported Clash transport" }
    when (network) {
        "ws" -> {
            require(node["grpc-opts"] == null) { "Clash grpc settings require grpc transport" }
            appendClashWebsocket(node, params)
        }
        "grpc", "gun" -> {
            require(node["ws-opts"] == null) { "Clash websocket settings require websocket transport" }
            val options = node["grpc-opts"]?.let {
                it as? JsonObject ?: error("invalid Clash grpc settings")
            } ?: JsonObject(emptyMap())
            require(
                options.keys.all { it in setOf("grpc-service-name", "grpc-user-agent", "ping-interval") }
            ) { "unsupported Clash grpc pooling or option" }
            params["type"] = "grpc"
            val service = options.clashString("grpc-service-name", "GunService").ifEmpty { "GunService" }
            params["serviceName"] = service
            // Mihomo gun uses URL.Opaque: preserve its literal path and default.
            params["rpc_path"] = if (service.startsWith('/')) service else "/$service/Tun"
            params["user_agent"] = options["grpc-user-agent"]?.let {
                (
                    it as? JsonPrimitive
                    )?.takeIf { value -> value.isString }?.content ?: error("invalid Clash grpc user agent")
            }.orEmpty().ifEmpty { "grpc-go/1.36.0" }
            options["ping-interval"]?.let {
                val seconds = (
                    it as? JsonPrimitive
                    )?.content?.toLongOrNull() ?: error("invalid Clash grpc ping interval")
                require(seconds in 0..86400) { "invalid Clash grpc ping interval" }
                params["idle_timeout_ms"] = (seconds * 1000).toString()
            }
        }
        else -> require(
            node["ws-opts"] == null && node["grpc-opts"] == null
        ) { "Clash transport settings require their transport" }
    }
    return if (network == "gun") "grpc" else network
}

private fun appendClashWebsocket(node: JsonObject, params: MutableMap<String, String>) {
    params["type"] = "ws"
    val options = node["ws-opts"]?.let {
        it as? JsonObject ?: error("invalid Clash websocket settings")
    } ?: JsonObject(emptyMap())
    require(options.keys.all { it in setOf("path", "headers") }) { "unsupported Clash websocket option" }
    params["path"] = options.clashString("path", "/")
    options["headers"]?.let { element ->
        val headers = element as? JsonObject ?: error("invalid Clash headers")
        require(
            headers.size <= 1 && headers.keys.all { it.equals("Host", ignoreCase = true) }
        ) { "unsupported Clash headers" }
        headers.values.firstOrNull()?.let {
            params["host"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash host header")
        }
    }
}

private fun appendClashSecurity(node: JsonObject, type: String, params: MutableMap<String, String>) {
    node["flow"]?.let { params["flow"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash flow") }
    node["packet-encoding"]?.let {
        params["packetEncoding"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash packet encoding")
    }
    val reality = node["reality-opts"]?.let { it as? JsonObject ?: error("invalid Clash REALITY settings") }
    validateClashTlsOptions(node, type, reality)
    if (type in setOf("vless", "vmess")) {
        params["security"] = when {
            reality != null -> "reality"
            node.clashBool("tls") -> "tls"
            else -> "none"
        }
    }
    if (reality != null) appendClashReality(type, reality, params)
}

private fun validateClashTlsOptions(node: JsonObject, type: String, reality: JsonObject?) {
    if (type in setOf("vless", "vmess") && reality == null && !node.clashBool("tls")) {
        require(
            node.keys.none {
                it in CLASH_TLS_FIELDS - "tls" + setOf("alpn", "client-fingerprint")
            }
        ) { "Clash TLS settings require TLS" }
    }
    if (reality != null) require(node.clashBool("tls", true)) { "Clash REALITY requires TLS" }
}

private fun appendClashReality(type: String, reality: JsonObject, params: MutableMap<String, String>) {
    require(
        type == "vless" && reality.keys.all {
            it in setOf("public-key", "short-id")
        }
    ) { "unsupported Clash REALITY settings" }
    params["pbk"] = reality.clashString("public-key")
    params["sid"] = reality.clashString("short-id", "")
}

private fun appendClashHysteriaParameters(node: JsonObject, params: MutableMap<String, String>) {
    node["obfs"]?.let { params["obfs"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash obfuscation") }
    node["obfs-password"]?.let {
        params["obfs-password"] = (it as? JsonPrimitive)?.content ?: error("invalid Clash obfuscation")
    }
    for (direction in listOf("up", "down")) node[direction]?.let {
        val speed = (it as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { value -> value >= 0 } ?: error("unsupported Clash bandwidth")
        params[direction + "mbps"] = speed.toString()
    }
}

private fun clashVmessLink(node: JsonObject, port: Int, network: String, params: Map<String, String>): String {
    require(node.clashString("alterId", "0") == "0") { "legacy VMess alterId is not supported" }
    val grpcFields =
        params.filterKeys { it in setOf("user_agent", "idle_timeout_ms", "rpc_path") }
            .mapValues { (_, value) -> JsonPrimitive(value) }
    val vmess =
        JsonObject(
            mapOf(
                "v" to JsonPrimitive("2"),
                "ps" to JsonPrimitive(
                    node.clashString(
                        "name",
                        "VPN"
                    )
                ),
                "add" to JsonPrimitive(node.clashString("server")),
                "port" to JsonPrimitive(port.toString()),
                "id" to JsonPrimitive(node.clashString("uuid")),
                "aid" to JsonPrimitive("0"),
                "scy" to JsonPrimitive(
                    node.clashString(
                        "cipher",
                        "auto"
                    )
                ),
                "net" to JsonPrimitive(network),
                "path" to JsonPrimitive(
                    if (network == "grpc") {
                        params["serviceName"].orEmpty()
                    } else {
                        params["path"] ?: "/"
                    }
                ),
                "host" to JsonPrimitive(params["host"] ?: ""),
                "tls" to JsonPrimitive(if (node.clashBool("tls")) "tls" else ""),
                "fp" to JsonPrimitive(params["fp"] ?: ""),
                "alpn" to JsonPrimitive(params["alpn"] ?: ""),
                "sni" to JsonPrimitive(params["sni"] ?: ""),
                "allowInsecure" to JsonPrimitive(node.clashBool("skip-cert-verify"))
            ) + grpcFields
        )
    return "vmess://" + Base64.getEncoder().encodeToString(vmess.toString().toByteArray(Charsets.UTF_8))
}
