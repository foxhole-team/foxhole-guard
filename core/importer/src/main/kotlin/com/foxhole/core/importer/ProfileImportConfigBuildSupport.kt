package com.foxhole.core.importer

import com.foxhole.core.model.DEFAULT_FOXHOLE_RUNTIME_LOG_LEVEL
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress

// The config-building leg of the import hierarchy: WireGuard parsing and the normalized
// migration-document assembly (TLS/transport included). Split from ProfileImportNodeSupport.kt.
internal open class ProfileImportConfigBuildSupport(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportCoreSupport(json, remoteHostResolver) {
    internal fun parseWireGuardUri(
        value: String,
        allowPrivateOutboundHosts: Boolean,
    ): ProxyNode {
        val uri = parseLenientUri(value)
        val query = parseQueryParameters(uri.rawQuery)
        val host = uri.host ?: error("wireguard host is missing")
        val port = uri.port.takeIf { it > 0 } ?: 51820
        val privateKey =
            uri.rawUserInfo
                ?.let(::uriDecode)
                ?.requireSafeWireGuardUriValue("private key")
                ?: error("wireguard private key is missing")
        val publicKey =
            query
                .firstValue("publickey", "public_key", "public-key")
                ?.requireSafeWireGuardUriValue("public key")
                ?: error("wireguard public key is missing")
        val addresses =
            query
                .firstValue("address", "addresses")
                ?.requireSafeWireGuardUriValue("address")
                ?: error("wireguard address is missing")
        val allowedIps =
            query
                .firstValue("allowed_ips", "allowedips", "allowed-ips")
                ?.requireSafeWireGuardUriValue("allowed IPs")
                ?: "0.0.0.0/0, ::/0"
        val displayName = displayNameFromUri(uri, host)
        val lines =
            buildList {
                add("[Interface]")
                add("PrivateKey = $privateKey")
                add("Address = $addresses")
                query["dns"]?.requireSafeWireGuardUriValue("DNS")?.let { add("DNS = $it") }
                query["mtu"]?.requireWireGuardUriInt("MTU", 576..65_535)?.let { add("MTU = $it") }
                add("[Peer]")
                add("PublicKey = $publicKey")
                query
                    .firstValue("presharedkey", "preshared_key", "pre-shared-key")
                    ?.requireSafeWireGuardUriValue("pre-shared key")
                    ?.let { add("PresharedKey = $it") }
                add("AllowedIPs = $allowedIps")
                add("Endpoint = ${formatWireGuardUriEndpoint(host, port)}")
                query
                    .firstValue("keepalive", "persistentkeepalive", "persistent_keepalive")
                    ?.requireWireGuardUriInt("keepalive", 0..65_535)
                    ?.let { add("PersistentKeepalive = $it") }
                query["reserved"]?.requireSafeWireGuardUriValue("reserved")?.let { add("Reserved = $it") }
            }
        return parseWireGuardConfig(
            raw = (lines + amneziaUriInterfaceLines(query)).joinToString(separator = "\n"),
            displayName = displayName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        )
    }

    private fun amneziaUriInterfaceLines(query: Map<String, String>): List<String> {
        // Re-spell link parameters as wg-quick lines so link and file imports share one parser.
        val declared =
            AMNEZIA_URI_QUERY_KEYS.mapNotNull { key ->
                query[key]?.trim()?.takeIf(String::isNotBlank)?.let { key to it }
            }
        if (declared.isEmpty()) {
            return emptyList()
        }
        val peerKeys = setOf("advancedsecurity")
        return buildList {
            add("[Interface]")
            declared.filterNot { (key, _) -> key in peerKeys }.forEach { (key, value) ->
                add("$key = ${value.requireSafeWireGuardUriValue(key)}")
            }
            declared.filter { (key, _) -> key in peerKeys }.forEach { (key, value) ->
                add("[Peer]")
                add("$key = ${value.requireSafeWireGuardUriValue(key)}")
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
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
        val mtu = interfaceSection["MTU"]?.firstOrNull()?.toIntOrNull()
        val amnezia =
            amneziaBlockFromInterface(
                interfaceSection = interfaceSection,
                peerSection = peerSection,
                mtu = mtu ?: DEFAULT_WIREGUARD_IMPORT_MTU,
            )
        val wireGuardEndpoint =
            buildJsonObject {
                put("type", "wireguard")
                put("tag", tagFor(displayName, "wireguard", host, port, peerSection["PublicKey"]?.firstOrNull()))
                put(
                    "private_key",
                    interfaceSection["PrivateKey"]?.firstOrNull() ?: error("wireguard private key is missing")
                )
                put(
                    "address",
                    buildStringArray(localAddress),
                )
                mtu?.let { put("mtu", it) }
                amnezia?.let { put("amnezia", it) }
                putJsonArray("peers") {
                    add(
                        buildJsonObject {
                            put("address", host)
                            put("port", port)
                            put(
                                "public_key",
                                peerSection["PublicKey"]?.firstOrNull() ?: error("wireguard public key is missing")
                            )
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
        val dedupedNodes = deduplicateNodeTags(nodes)
        val normalizedConfig = buildBaseConfig(
            outbounds = JsonArray(dedupedNodes.mapNotNull { it.outbound }),
            endpoints = JsonArray(dedupedNodes.mapNotNull { it.endpoint }),
            proxyTags = dedupedNodes.map { it.tag },
            tunAddresses = tunAddressesForProxyNodes(dedupedNodes),
            wireGuardDnsServers = allowedWireGuardDnsServers(dedupedNodes),
        )
        requireAllowedRemoteHosts(normalizedConfig, allowPrivateOutboundHosts)
        return json.encodeToString(JsonObject.serializer(), normalizedConfig)
    }

    /**
     * The resolvers a WireGuard profile declares, kept as the profile wrote them.
     *
     * These are not outbound endpoints: a packet-tunnel profile does not intercept DNS, so the
     * address here is advertised on the TUN and reached as a packet through the tunnel. That is why
     * the public-host rule is not applied — a WireGuard peer's resolver normally lives inside the
     * tunnel (10.x, 172.16.x, fd00::/8), and screening it out used to be invisible only because DNS
     * quietly fell back to the managed remote resolver. It no longer does, so screening it out would
     * leave the profile with no resolver at all and refuse it.
     *
     * What stays out: loopback, "any" and link-local addresses, which would point the device at
     * itself or at its own LAN segment rather than at anything the profile meant; and hostnames,
     * because Android's TUN takes numeric addresses only and resolving that name would have to
     * happen outside the very tunnel the profile exists to keep queries inside.
     */
    private fun allowedWireGuardDnsServers(nodes: List<ProxyNode>): List<String> =
        nodes
            .asSequence()
            .filter { it.protocolHint == ProtocolHint.WIREGUARD }
            .flatMap { it.dnsServers.asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .filter { server ->
                runCatching { parseRemoteEndpoint(server, defaultPort = 53).host }
                    .getOrNull()
                    ?.let(::isAdvertisableTunnelResolver) == true
            }.toList()

    private fun isAdvertisableTunnelResolver(host: String): Boolean {
        val literal = host.trim().removeSurrounding("[", "]")
        if (!literal.isImporterIpv4Literal() && !literal.isImporterIpv6Literal()) {
            return false
        }
        // Safe for a literal: InetAddress performs no lookup when the text is already an address.
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        return !address.isLoopbackAddress &&
            !address.isAnyLocalAddress &&
            !address.isLinkLocalAddress &&
            !address.isMulticastAddress
    }

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

    @Suppress("LongMethod")
    internal fun buildBaseConfig(
        outbounds: JsonArray,
        endpoints: JsonArray = JsonArray(emptyList()),
        proxyTags: List<String> =
            outbounds.map {
                it.jsonObject["tag"]?.jsonPrimitive?.content ?: error("missing tag")
            },
        tunAddresses: List<String> = defaultTunAddresses,
        routeOverride: JsonObject? = null,
        dnsOverride: JsonObject? = null,
        wireGuardDnsServers: List<String> = emptyList(),
    ): JsonObject {
        require(proxyTags.isNotEmpty()) { "empty proxy tag list" }
        return buildJsonObject {
            putJsonObject("log") {
                put("level", DEFAULT_FOXHOLE_RUNTIME_LOG_LEVEL)
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
            put("tag", WIREGUARD_DNS_SERVER_TAG)
            put("type", "udp")
            put("server", endpoint.host)
            put("server_port", endpoint.port ?: 53)
            put("detour", "proxy")
        }
    }

    internal fun tunAddressesForProxyNodes(nodes: List<ProxyNode>): List<String> {
        val endpoints = nodes.mapNotNull { it.endpoint }
        if (endpoints.isEmpty() || nodes.any { it.outbound != null }) {
            return defaultTunAddresses
        }
        val allWireGuardEndpoints =
            endpoints.all { endpoint ->
                endpoint["type"]?.jsonPrimitive?.contentOrNull.equals("wireguard", ignoreCase = true)
            }
        if (!allWireGuardEndpoints) {
            return defaultTunAddresses
        }
        val hasIpv6WireGuardAddress =
            endpoints.any { endpoint ->
                endpoint.stringValues("address").any { address -> address.substringBefore('/').contains(':') }
            }
        return if (hasIpv6WireGuardAddress) defaultTunAddresses else ipv4OnlyTunAddresses
    }

    private fun JsonObject.stringValues(key: String): List<String> =
        when (val value = this[key]) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
            null -> emptyList()
            else -> listOfNotNull(value.jsonPrimitive.contentOrNull)
        }

    private val defaultTunAddresses = listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")
    private val ipv4OnlyTunAddresses = listOf("172.19.0.1/30")

    @Suppress("CyclomaticComplexMethod")
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
            normalizeUtlsFingerprint(
                query.firstValue("fp", "fingerprint", "utlsFingerprint", "utls_fingerprint"),
                reality = security == "reality",
            )
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
                    put("fingerprint", utlsFingerprint ?: SUBSTITUTE_UTLS_FINGERPRINT)
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

    internal fun Map<String, String>.firstValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.trim()?.takeIf(String::isNotBlank)
                ?: this[key.lowercase()]?.trim()?.takeIf(String::isNotBlank)
        }

    internal fun Map<String, String>.booleanValue(vararg keys: String): Boolean? =
        firstValue(*keys)?.toFlexibleBoolean()

    internal fun normalizeUtlsFingerprint(
        value: String?,
        reality: Boolean,
    ): String? {
        val fingerprint = value?.trim()?.lowercase()
        return when {
            fingerprint.isNullOrEmpty() || fingerprint in UTLS_FINGERPRINT_OFF_VALUES -> null
            fingerprint in SUPPORTED_UTLS_FINGERPRINTS -> fingerprint
            // These TLS 1.2 parrots have no key_share for REALITY authentication.
            reality && fingerprint in REALITY_IMPOSSIBLE_UTLS_FINGERPRINTS ->
                error(
                    "REALITY cannot use fp=$fingerprint: that parrot sends no key_share extension, and " +
                        "REALITY derives its authentication key from the client's x25519 key share",
                )
            else -> SUBSTITUTE_UTLS_FINGERPRINT
        }
    }
}

// Membership promises that FoxCore can emit the named ClientHello.
val SUPPORTED_UTLS_FINGERPRINTS: Set<String> =
    setOf(
        "chrome",
        "chrome_151",
        "chrome_133",
        "chrome_131",
        "edge",
        "edge_85",
        "safari",
        "safari_26_3",
        "ios",
        "ios_14",
        "qq",
        "qq_11_1",
        "firefox",
        "firefox_153",
        "firefox_148",
        "random",
        "randomized",
    )

const val SUBSTITUTE_UTLS_FINGERPRINT: String = "chrome"

private val REALITY_IMPOSSIBLE_UTLS_FINGERPRINTS = setOf("360", "android")

private val UTLS_FINGERPRINT_OFF_VALUES = setOf("auto", "off", "false", "0", "disabled")

fun unsupportedUtlsFingerprint(value: String?): String? {
    val trimmed = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val normalized = trimmed.lowercase()
    return trimmed.takeUnless {
        normalized in UTLS_FINGERPRINT_OFF_VALUES || normalized in SUPPORTED_UTLS_FINGERPRINTS
    }
}

internal const val DEFAULT_WIREGUARD_IMPORT_MTU = 1420

private fun String.requireSafeWireGuardUriValue(label: String): String =
    trim().also { normalized ->
        require(normalized.isNotEmpty()) { "wireguard $label is missing" }
        require(normalized.none(Char::isISOControl)) { "wireguard $label contains control characters" }
    }

private fun String.requireWireGuardUriInt(
    label: String,
    allowedRange: IntRange,
): Int {
    val parsed = requireSafeWireGuardUriValue(label).toIntOrNull()
    require(parsed != null && parsed in allowedRange) { "invalid wireguard $label" }
    return parsed
}

private fun formatWireGuardUriEndpoint(
    host: String,
    port: Int,
): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

// Text-only address checks, so InetAddress is never handed a name it would have to resolve.
private fun String.isImporterIpv4Literal(): Boolean {
    val parts = split('.')
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
}

private fun String.isImporterIpv6Literal(): Boolean =
    count { it == ':' } >= 2 &&
        length <= MAX_IPV6_LITERAL_LENGTH &&
        all { character ->
            character.isDigit() ||
                character.lowercaseChar() in 'a'..'f' ||
                character == ':' ||
                character == '.'
        }

private const val MAX_IPV6_LITERAL_LENGTH = 45
