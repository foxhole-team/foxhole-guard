package com.foxhole.beta.core.importer

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.network.RemoteHostResolver
import com.foxhole.beta.core.network.requirePublicRemoteHost
import com.foxhole.beta.vpn.FOXHOLE_RUNTIME_LOG_LEVEL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal open class ProfileImportNodeSupport(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportCoreSupport(json, remoteHostResolver) {
internal fun normalizeRawSingBoxConfig(
    objectValue: JsonObject,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): String {
    val normalizedObject = normalizeTlsSettings(objectValue, allowInsecureTls)
    val inbounds = normalizedObject["inbounds"]?.jsonArray
    require(inbounds == null || inbounds.isEmpty()) { "raw sing-box configs must not define inbounds" }

    val experimental = normalizedObject["experimental"]?.jsonObject
    require(experimental?.containsKey("clash_api") != true) { "clash api is not allowed" }
    require(experimental?.containsKey("v2ray_api") != true) { "v2ray api is not allowed" }

    val outbounds = normalizedObject["outbounds"]?.jsonArray ?: error("raw sing-box config must define outbounds")
    requireAllowedOutboundHosts(outbounds, allowPrivateOutboundHosts)
    val normalizedConfig =
        buildBaseConfig(
            outbounds = outbounds,
            routeOverride = normalizedObject["route"]?.jsonObject,
            dnsOverride = normalizedObject["dns"]?.jsonObject,
        )
    requireAllowedRemoteHosts(normalizedConfig, allowPrivateOutboundHosts)
    return json.encodeToString(JsonObject.serializer(), normalizedConfig)
}

internal fun parseNodeLines(
    raw: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): List<ProxyNode> {
    val lines =
        raw.lineSequence()
            .map(::normalizeInput)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
    if (lines.isEmpty()) {
        return emptyList()
    }
    val parsed = mutableListOf<ProxyNode>()
    val rejected = mutableListOf<String>()
    lines.forEachIndexed { index, line ->
        runCatching {
            parseSingleNode(line, allowPrivateOutboundHosts, allowInsecureTls)
        }.onSuccess { node ->
            parsed += node
        }.onFailure { error ->
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            rejected += "line ${index + 1}: $reason"
        }
    }
    require(rejected.isEmpty()) {
        "unsupported subscription entries: ${rejected.size} rejected (${rejected.take(3).joinToString("; ")})"
    }
    return parsed
}

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
    val outbound =
        buildJsonObject {
            put("type", "vless")
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("uuid", uri.userInfo ?: error("missing uuid"))
            query["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            buildVlessNetwork(query)?.let { put("network", it) }
            query.firstValue("packetEncoding", "packet_encoding", "packet-encoding")?.let { put("packet_encoding", it) }
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
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("password", uri.userInfo ?: error("missing password"))
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
            put("tag", tagFor(displayName))
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
        protocolHint = ProtocolHint.SING_BOX,
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
        val credentials = base.substringBefore('@')
        decodedPart = decodeBase64IfNeeded(credentials)
        hostPortPart = base.substringAfter('@').substringBefore('?')
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
    require(!queryPart.contains("plugin=")) { "shadowsocks plugins are not supported in v1" }
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
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
    return ProxyNode(displayName, hint, outbound)
}

private fun Map<String, String>.firstValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        this[key]?.trim()?.takeIf(String::isNotBlank)
            ?: this[key.lowercase()]?.trim()?.takeIf(String::isNotBlank)
    }

private fun Map<String, String>.booleanValue(vararg keys: String): Boolean? =
    firstValue(*keys)?.toFlexibleBoolean()

internal fun normalizeUtlsFingerprint(value: String?): String? =
    when (val fingerprint = value?.trim()?.lowercase()) {
        null, "", "auto", "off", "false", "0", "disabled" -> null
        else -> fingerprint
    }

internal fun buildVlessNetwork(query: Map<String, String>): String? {
    query["network"]?.trim()?.lowercase()?.takeIf { it in setOf("tcp", "udp") }?.let { return it }
    return when (query["type"].orEmpty().trim().lowercase()) {
        "tcp" -> "tcp"
        else -> null
    }
}

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
    val network = objectValue["net"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val security = objectValue["tls"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val query =
        buildMap {
            put("type", network)
            put("path", objectValue["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("host", objectValue["host"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("serviceName", objectValue["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
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
            put("tag", tagFor(displayName))
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
    val displayName = displayNameFromUri(uri, host)
    val outbound =
        buildJsonObject {
            put("type", "hysteria2")
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("password", uri.userInfo ?: error("missing password"))
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
            }
            query["obfs"]?.takeIf { it.isNotBlank() }?.let { put("obfs", it) }
            query["obfs-password"]?.takeIf { it.isNotBlank() }?.let { put("obfs_password", it) }
            query["upmbps"]?.toIntOrNull()?.let { put("up_mbps", it) }
            query["downmbps"]?.toIntOrNull()?.let { put("down_mbps", it) }
        }
    return ProxyNode(
        displayName = displayName,
        protocolHint = ProtocolHint.HYSTERIA2,
        outbound = outbound,
        subscriptionExpiresAt = subscriptionExpirationFromQuery(uri.rawQuery),
    )
}

internal fun parseWireGuardConfig(
    raw: String,
    displayName: String,
    allowPrivateOutboundHosts: Boolean,
): ProxyNode {
    val interfaceSection = mutableMapOf<String, MutableList<String>>()
    val peerSection = mutableMapOf<String, MutableList<String>>()
    var currentSection = ""
    raw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";") -> Unit
            trimmed.startsWith("[interface]", ignoreCase = true) -> currentSection = "interface"
            trimmed.startsWith("[peer]", ignoreCase = true) -> currentSection = "peer"
            trimmed.contains("=") -> {
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                when (currentSection) {
                    "interface" -> interfaceSection.getOrPut(key) { mutableListOf() }.add(value)
                    "peer" -> peerSection.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
        }
    }
    val rawEndpoint = peerSection["Endpoint"]?.firstOrNull() ?: error("wireguard endpoint is missing")
    val remoteEndpoint = parseRemoteEndpoint(rawEndpoint, defaultPort = 51820)
    val host = remoteEndpoint.host
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = remoteEndpoint.port ?: 51820
    val localAddress = interfaceSection["Address"]?.flatMap { it.split(',') }?.map(String::trim).orEmpty()
    val dnsServers =
        interfaceSection["DNS"]
            ?.flatMap { it.split(',') }
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
    val allowedIps =
        filterWireGuardAllowedIpsForLocalAddresses(
            allowedIps = peerSection["AllowedIPs"]?.flatMap { it.split(',') }?.map(String::trim).orEmpty(),
            localAddresses = localAddress,
        )
    val wireGuardEndpoint =
        buildJsonObject {
            put("type", "wireguard")
            put("tag", tagFor(displayName))
            put("private_key", interfaceSection["PrivateKey"]?.firstOrNull() ?: error("wireguard private key is missing"))
            put(
                "address",
                buildStringArray(localAddress),
            )
            interfaceSection["ListenPort"]?.firstOrNull()?.toIntOrNull()?.let { put("listen_port", it) }
            interfaceSection["MTU"]?.firstOrNull()?.toIntOrNull()?.let { put("mtu", it) }
            putJsonArray("peers") {
                add(
                    buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("public_key", peerSection["PublicKey"]?.firstOrNull() ?: error("wireguard public key is missing"))
                        peerSection["PresharedKey"]?.firstOrNull()?.let { put("pre_shared_key", it) }
                        if (allowedIps.isNotEmpty()) {
                            put(
                                "allowed_ips",
                                buildStringArray(allowedIps),
                            )
                        }
                        peerSection["PersistentKeepalive"]?.firstOrNull()?.toIntOrNull()?.let {
                            put("persistent_keepalive_interval", it)
                        }
                        parseWireGuardReserved(peerSection["Reserved"]?.firstOrNull())?.let { reserved ->
                            put("reserved", reserved)
                        }
                    },
                )
            }
        }
    return ProxyNode(
        displayName = displayName,
        protocolHint = ProtocolHint.WIREGUARD,
        outbound = null,
        endpoint = wireGuardEndpoint,
        dnsServers = dnsServers,
    )
}

internal fun filterWireGuardAllowedIpsForLocalAddresses(
    allowedIps: List<String>,
    localAddresses: List<String>,
): List<String> {
    val hasIpv4Local = localAddresses.any { !it.substringBefore('/').contains(':') }
    val hasIpv6Local = localAddresses.any { it.substringBefore('/').contains(':') }
    return allowedIps
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { allowedIp ->
            val isIpv6 = allowedIp.substringBefore('/').contains(':')
            if (isIpv6) {
                hasIpv6Local
            } else {
                hasIpv4Local
            }
        }
}

internal fun buildConfigFromNodes(
    nodes: List<ProxyNode>,
    allowPrivateOutboundHosts: Boolean = false,
): String {
    require(nodes.isNotEmpty()) { "empty node list" }
    val normalizedConfig = buildBaseConfig(
        outbounds = JsonArray(nodes.mapNotNull { it.outbound }),
        endpoints = JsonArray(nodes.mapNotNull { it.endpoint }),
        proxyTags = nodes.map { it.tag },
        tunAddresses = tunAddressesForProxyNodes(nodes),
        wireGuardDnsServers = allowedWireGuardDnsServers(nodes, allowPrivateOutboundHosts),
    )
    requireAllowedRemoteHosts(normalizedConfig, allowPrivateOutboundHosts)
    return json.encodeToString(JsonObject.serializer(), normalizedConfig)
}

private fun allowedWireGuardDnsServers(
    nodes: List<ProxyNode>,
    allowPrivateOutboundHosts: Boolean,
): List<String> =
    nodes
        .asSequence()
        .filter { it.protocolHint == ProtocolHint.WIREGUARD }
        .flatMap { it.dnsServers.asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .filter { server ->
            allowPrivateOutboundHosts ||
                runCatching {
                    parseRemoteEndpoint(server, defaultPort = 53)
                        .host
                        .requirePublicRemoteHost(resolveHost = true, resolver = remoteHostResolver)
                }.isSuccess
        }.toList()

internal fun parseWireGuardReserved(value: String?): JsonArray? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val bytes =
        normalized
            .split(',')
            .map { item -> item.trim().toIntOrNull() ?: return null }
            .takeIf { it.isNotEmpty() }
            ?: return null
    return buildJsonArray {
        bytes.forEach { add(JsonPrimitive(it)) }
    }
}

internal fun buildBaseConfig(
    outbounds: JsonArray,
    endpoints: JsonArray = JsonArray(emptyList()),
    proxyTags: List<String> =
        outbounds.map {
            it.jsonObject["tag"]?.jsonPrimitive?.content ?: error("missing tag")
        },
    tunAddresses: List<String> = DEFAULT_TUN_ADDRESSES,
    routeOverride: JsonObject? = null,
    dnsOverride: JsonObject? = null,
    wireGuardDnsServers: List<String> = emptyList(),
): JsonObject {
    require(proxyTags.isNotEmpty()) { "empty proxy tag list" }
    return buildJsonObject {
        putJsonObject("log") {
            put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
            put("timestamp", true)
        }
        put(
            "dns",
            dnsOverride ?: buildJsonObject {
                put(
                    "servers",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("tag", "dns-local")
                                put("type", "local")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("tag", "dns-direct")
                                put("type", "local")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("tag", "dns-remote")
                                put("type", "https")
                                put("server", "1.1.1.1")
                                put("server_port", 443)
                                put("path", "/dns-query")
                                put("detour", "proxy")
                            },
                        )
                        wireGuardDnsServers.firstOrNull()?.let { server ->
                            add(wireGuardDnsServer(server))
                        }
                    },
                )
                put("strategy", "prefer_ipv4")
                put("final", "dns-remote")
            },
        )
        put(
            "inbounds",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", "foxhole")
                        put("mtu", 1500)
                        put("auto_route", true)
                        put("strict_route", true)
                        put("stack", "system")
                        put(
                            "address",
                            buildStringArray(tunAddresses),
                        )
                    },
                )
            },
        )
        if (endpoints.isNotEmpty()) {
            put("endpoints", endpoints)
        }
        put(
            "outbounds",
            buildJsonArray {
                outbounds.forEach(::add)
                add(
                    buildJsonObject {
                        put("type", "selector")
                        put("tag", "proxy")
                        put("default", proxyTags.first())
                        put(
                            "outbounds",
                            buildStringArray(proxyTags),
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "block")
                        put("tag", "block")
                    },
                )
            },
        )
        put(
            "route",
            routeOverride ?: buildJsonObject {
                put(
                    "rules",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("action", "sniff")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("port", 53)
                                put("action", "hijack-dns")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("protocol", "dns")
                                put("action", "hijack-dns")
                            },
                        )
                    },
                )
                put("final", "proxy")
                put("default_domain_resolver", "dns-direct")
                put("auto_detect_interface", true)
            },
        )
    }
}

private fun wireGuardDnsServer(server: String): JsonObject {
    val endpoint = parseRemoteEndpoint(server, defaultPort = 53)
    return buildJsonObject {
        put("tag", WIREGUARD_DNS_TAG)
        put("type", "udp")
        put("server", endpoint.host)
        put("server_port", endpoint.port ?: 53)
        put("detour", "proxy")
    }
}

internal fun tunAddressesForProxyNodes(nodes: List<ProxyNode>): List<String> {
    val endpoints = nodes.mapNotNull { it.endpoint }
    if (endpoints.isEmpty() || nodes.any { it.outbound != null }) {
        return DEFAULT_TUN_ADDRESSES
    }
    val allWireGuardEndpoints =
        endpoints.all { endpoint ->
            endpoint["type"]?.jsonPrimitive?.contentOrNull.equals("wireguard", ignoreCase = true)
        }
    if (!allWireGuardEndpoints) {
        return DEFAULT_TUN_ADDRESSES
    }
    val hasIpv6WireGuardAddress =
        endpoints.any { endpoint ->
            endpoint.stringValues("address").any { address -> address.substringBefore('/').contains(':') }
        }
    return if (hasIpv6WireGuardAddress) DEFAULT_TUN_ADDRESSES else IPV4_ONLY_TUN_ADDRESSES
}

private fun JsonObject.stringValues(key: String): List<String> =
    when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
        null -> emptyList()
        else -> listOfNotNull(value.jsonPrimitive.contentOrNull)
    }

private val DEFAULT_TUN_ADDRESSES = listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")
private val IPV4_ONLY_TUN_ADDRESSES = listOf("172.19.0.1/30")
private val WIREGUARD_DNS_TAG = "dns-wireguard"

internal fun buildTls(
    query: Map<String, String>,
    host: String,
    tlsDefault: Boolean = false,
    allowInsecureTls: Boolean,
): JsonObject? {
    val security = query["security"].orEmpty().trim().lowercase()
    val hasTls = tlsDefault || security == "tls" || security == "reality" || query["sni"].orEmpty().isNotBlank()
    if (!hasTls) {
        return null
    }
    val utlsFingerprint =
        normalizeUtlsFingerprint(query.firstValue("fp", "fingerprint", "utlsFingerprint", "utls_fingerprint"))
    return buildJsonObject {
        put("enabled", true)
        put("server_name", query["sni"]?.takeIf { it.isNotBlank() } ?: host)
        val insecureTls =
            listOfNotNull(query.firstValue("allowInsecure", "allow_insecure"), query["insecure"]).firstNotNullOfOrNull { value ->
                value.toFlexibleBoolean()
            } ?: false
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
        query.firstValue("min_version", "minVersion", "tlsMinVersion")?.let { put("min_version", it) }
        query.firstValue("max_version", "maxVersion", "tlsMaxVersion")?.let { put("max_version", it) }
        query.firstValue("curve_preferences", "curves", "tlsCurves")?.let { curves ->
            put(
                "curve_preferences",
                buildStringArray(curves.split(',').map(String::trim).filter(String::isNotBlank)),
            )
        }
        query.booleanValue("ech")?.let { echEnabled ->
            putJsonObject("ech") {
                put("enabled", echEnabled)
            }
        }
        if (utlsFingerprint != null || security == "reality") {
            putJsonObject("utls") {
                put("enabled", true)
                put("fingerprint", utlsFingerprint ?: "chrome")
            }
        }
        if (security == "reality") {
            putJsonObject("reality") {
                put("enabled", true)
                put("public_key", query["pbk"] ?: error("missing reality public key"))
                query["sid"]?.takeIf { it.isNotBlank() }?.let { put("short_id", it) }
            }
        }
    }
}

internal fun buildTransport(query: Map<String, String>): JsonObject? {
    val transportType = query["type"].orEmpty().trim().lowercase()
    return when (transportType) {
        "", "tcp" -> null
        "ws" -> buildJsonObject {
            put("type", "ws")
            query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
            query["host"]?.takeIf { it.isNotBlank() }?.let { host ->
                putJsonObject("headers") {
                    put("Host", host)
                }
            }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            put("service_name", query.firstValue("serviceName", "service_name") ?: query["path"].orEmpty())
        }
        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            query["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
        }
        "http" -> buildJsonObject {
            put("type", "http")
            query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
            query["host"]?.takeIf { it.isNotBlank() }?.let { host ->
                put(
                    "host",
                    buildStringArray(listOf(host)),
                )
            }
        }
        else -> error("unsupported transport type: $transportType")
    }
}
}
