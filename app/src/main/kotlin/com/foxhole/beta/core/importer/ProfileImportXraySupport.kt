package com.foxhole.beta.core.importer

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal open class ProfileImportXraySupport(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportNodeSupport(json, remoteHostResolver) {
internal fun normalizeRawXrayConfig(
    objectValue: JsonObject,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): RawJsonImport {
    val normalizedObject = normalizeTlsSettings(objectValue, allowInsecureTls)
    require(normalizedObject["api"] == null) { "v2ray api is not allowed" }

    val xrayOutbounds = normalizedObject["outbounds"]?.jsonArray ?: error("xray config must define outbounds")
    val convertedOutbounds = convertXrayOutbounds(xrayOutbounds, allowPrivateOutboundHosts, allowInsecureTls)
    require(convertedOutbounds.nodes.isNotEmpty()) { "xray config must define at least one supported remote outbound" }

    val displayName =
        normalizedObject["remarks"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: convertedOutbounds.nodes.first().displayName

    val normalizedConfig =
        buildBaseConfig(
            outbounds = JsonArray(convertedOutbounds.nodes.map { it.outbound }),
            routeOverride = convertXrayRoute(normalizedObject["routing"]?.jsonObject, convertedOutbounds.tagMapping),
            dnsOverride = convertXrayDns(normalizedObject["dns"]?.jsonObject),
        )
    requireAllowedRemoteHosts(normalizedConfig, allowPrivateOutboundHosts)
    return RawJsonImport(
        displayName = displayName,
        protocolHint = convertedOutbounds.nodes.first().protocolHint,
        normalizedConfigJson = json.encodeToString(JsonObject.serializer(), normalizedConfig),
        subscriptionExpiresAt =
            convertedOutbounds.nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull()
                ?: subscriptionExpirationFromJson(normalizedObject),
    )
}

internal fun convertXrayOutbounds(
    outbounds: JsonArray,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): XrayOutboundsConversion {
    val nodes = mutableListOf<ProxyNode>()
    val tagMapping = mutableMapOf<String, String>()

    outbounds.forEach { element ->
        val outbound = element.jsonObject
        val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val originalTag = outbound["tag"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        when (protocol) {
            "freedom" -> {
                if (originalTag.isNotBlank()) {
                    tagMapping[originalTag] = "direct"
                }
            }
            "blackhole" -> {
                if (originalTag.isNotBlank()) {
                    tagMapping[originalTag] = "block"
                }
            }
            "vless" -> {
                val node = convertXrayVlessOutbound(outbound, allowPrivateOutboundHosts, allowInsecureTls)
                nodes += node
                if (originalTag.isNotBlank()) {
                    tagMapping[originalTag] =
                        if (originalTag.equals("proxy", ignoreCase = true)) {
                            "proxy"
                        } else {
                            node.outbound["tag"]!!.jsonPrimitive.content
                        }
                }
            }
            "trojan" -> {
                val node = convertXrayTrojanOutbound(outbound, allowPrivateOutboundHosts, allowInsecureTls)
                nodes += node
                if (originalTag.isNotBlank()) {
                    tagMapping[originalTag] =
                        if (originalTag.equals("proxy", ignoreCase = true)) {
                            "proxy"
                        } else {
                            node.outbound["tag"]!!.jsonPrimitive.content
                        }
                }
            }
            "vmess" -> {
                val node = convertXrayVmessOutbound(outbound, allowPrivateOutboundHosts, allowInsecureTls)
                nodes += node
                if (originalTag.isNotBlank()) {
                    tagMapping[originalTag] =
                        if (originalTag.equals("proxy", ignoreCase = true)) {
                            "proxy"
                        } else {
                            node.outbound["tag"]!!.jsonPrimitive.content
                        }
                }
            }
            "shadowsocks" -> {
                val node = convertXrayShadowsocksOutbound(outbound, allowPrivateOutboundHosts)
                nodes += node
                if (originalTag.isNotBlank()) {
                    tagMapping[originalTag] =
                        if (originalTag.equals("proxy", ignoreCase = true)) {
                            "proxy"
                        } else {
                            node.outbound["tag"]!!.jsonPrimitive.content
                        }
                }
            }
            "dns", "" -> Unit
            else -> error("unsupported xray outbound protocol: $protocol")
        }
    }

    return XrayOutboundsConversion(nodes = nodes, tagMapping = tagMapping)
}

internal fun convertXrayVlessOutbound(
    outbound: JsonObject,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ProxyNode {
    val vnext =
        outbound["settings"]
            ?.jsonObject
            ?.get("vnext")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: error("xray vless outbound is missing vnext")
    val host = vnext["address"]?.jsonPrimitive?.contentOrNull ?: error("missing host")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = vnext["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 443
    val user = vnext["users"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("xray vless outbound is missing user")
    val displayName = preferredXrayNodeName(outbound, host)
    val streamSettings = outbound["streamSettings"]?.jsonObject
    val singboxOutbound =
        buildJsonObject {
            put("type", "vless")
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("uuid", user["id"]?.jsonPrimitive?.contentOrNull ?: error("missing id"))
            user["flow"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            buildXrayTls(streamSettings, host, allowInsecureTls = allowInsecureTls)?.let { put("tls", it) }
            buildXrayTransport(streamSettings)?.let { put("transport", it) }
        }
    return ProxyNode(displayName, ProtocolHint.VLESS, singboxOutbound)
}

internal fun convertXrayTrojanOutbound(
    outbound: JsonObject,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ProxyNode {
    val server =
        outbound["settings"]
            ?.jsonObject
            ?.get("servers")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: error("xray trojan outbound is missing servers")
    val host = server["address"]?.jsonPrimitive?.contentOrNull ?: error("missing host")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = server["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 443
    val displayName = preferredXrayNodeName(outbound, host)
    val streamSettings = outbound["streamSettings"]?.jsonObject
    val singboxOutbound =
        buildJsonObject {
            put("type", "trojan")
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("password", server["password"]?.jsonPrimitive?.contentOrNull ?: error("missing password"))
            buildXrayTls(
                streamSettings,
                host,
                tlsDefault = true,
                allowInsecureTls = allowInsecureTls,
            )?.let { put("tls", it) }
            buildXrayTransport(streamSettings)?.let { put("transport", it) }
        }
    return ProxyNode(displayName, ProtocolHint.TROJAN, singboxOutbound)
}

internal fun convertXrayVmessOutbound(
    outbound: JsonObject,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ProxyNode {
    val vnext =
        outbound["settings"]
            ?.jsonObject
            ?.get("vnext")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: error("xray vmess outbound is missing vnext")
    val host = vnext["address"]?.jsonPrimitive?.contentOrNull ?: error("missing host")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = vnext["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 443
    val user = vnext["users"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("xray vmess outbound is missing user")
    val displayName = preferredXrayNodeName(outbound, host)
    val streamSettings = outbound["streamSettings"]?.jsonObject
    val singboxOutbound =
        buildJsonObject {
            put("type", "vmess")
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("uuid", user["id"]?.jsonPrimitive?.contentOrNull ?: error("missing id"))
            user["alterId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { put("alter_id", it) }
            user["security"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("security", it) }
            buildXrayTls(streamSettings, host, allowInsecureTls = allowInsecureTls)?.let { put("tls", it) }
            buildXrayTransport(streamSettings)?.let { put("transport", it) }
        }
    return ProxyNode(displayName, ProtocolHint.VMESS, singboxOutbound)
}

internal fun convertXrayShadowsocksOutbound(
    outbound: JsonObject,
    allowPrivateOutboundHosts: Boolean,
): ProxyNode {
    val server =
        outbound["settings"]
            ?.jsonObject
            ?.get("servers")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: error("xray shadowsocks outbound is missing servers")
    val host = server["address"]?.jsonPrimitive?.contentOrNull ?: error("missing host")
    validateOutboundHost(host, allowPrivateOutboundHosts)
    val port = server["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("missing port")
    val displayName = preferredXrayNodeName(outbound, host)
    val singboxOutbound =
        buildJsonObject {
            put("type", "shadowsocks")
            put("tag", tagFor(displayName))
            put("server", host)
            put("server_port", port)
            put("method", server["method"]?.jsonPrimitive?.contentOrNull ?: error("missing method"))
            put("password", server["password"]?.jsonPrimitive?.contentOrNull ?: error("missing password"))
        }
    return ProxyNode(displayName, ProtocolHint.SHADOWSOCKS, singboxOutbound)
}

internal fun buildXrayTls(
    streamSettings: JsonObject?,
    host: String,
    tlsDefault: Boolean = false,
    allowInsecureTls: Boolean,
): JsonObject? {
    if (streamSettings == null) {
        return if (tlsDefault) {
            buildJsonObject {
                put("enabled", true)
                put("server_name", host)
            }
        } else {
            null
        }
    }
    val security = streamSettings["security"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
    val tlsSettings = streamSettings["tlsSettings"]?.jsonObject
    val realitySettings = streamSettings["realitySettings"]?.jsonObject
    val hasTls = tlsDefault || security == "tls" || security == "reality" || tlsSettings != null || realitySettings != null
    if (!hasTls) {
        return null
    }
    return buildJsonObject {
        put("enabled", true)
        put(
            "server_name",
            realitySettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                ?: tlsSettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                ?: host,
        )
        val insecureTls =
            listOfNotNull(
                tlsSettings?.get("allowInsecure"),
                tlsSettings?.get("insecure"),
                realitySettings?.get("allowInsecure"),
                realitySettings?.get("insecure"),
            ).firstNotNullOfOrNull(::flexibleBooleanOrNull) ?: false
        require(allowInsecureTls || !insecureTls) { "insecure tls is not allowed" }
        if (insecureTls) {
            put("insecure", true)
        }
        extractStringValues(tlsSettings?.get("alpn")).takeIf { it.isNotEmpty() }?.let { put("alpn", buildStringArray(it)) }
        if (security == "reality" || realitySettings != null) {
            putJsonObject("utls") {
                put("enabled", true)
                put(
                    "fingerprint",
                    realitySettings?.get("fingerprint")?.jsonPrimitive?.contentOrNull
                        ?: tlsSettings?.get("fingerprint")?.jsonPrimitive?.contentOrNull
                        ?: "chrome",
                )
            }
            putJsonObject("reality") {
                put("enabled", true)
                put(
                    "public_key",
                    realitySettings?.get("publicKey")?.jsonPrimitive?.contentOrNull
                        ?: error("missing reality public key"),
                )
                realitySettings["shortId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("short_id", it) }
            }
        }
    }
}

internal fun buildXrayTransport(streamSettings: JsonObject?): JsonObject? {
    val network = streamSettings?.get("network")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
    return when (network) {
        "", "tcp" -> null
        "ws" -> {
            val wsSettings = streamSettings?.get("wsSettings")?.jsonObject
            buildJsonObject {
                put("type", "ws")
                wsSettings?.get("path")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                wsSettings
                    ?.get("headers")
                    ?.jsonObject
                    ?.get("Host")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { host ->
                        putJsonObject("headers") {
                            put("Host", host)
                        }
                    }
            }
        }
        "grpc" -> {
            val grpcSettings = streamSettings?.get("grpcSettings")?.jsonObject
            buildJsonObject {
                put("type", "grpc")
                put(
                    "service_name",
                    grpcSettings?.get("serviceName")?.jsonPrimitive?.contentOrNull
                        ?: error("missing grpc service name"),
                )
            }
        }
        "http" -> {
            val httpSettings = streamSettings?.get("httpSettings")?.jsonObject
            buildJsonObject {
                put("type", "http")
                httpSettings?.get("path")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                extractStringValues(httpSettings?.get("host")).takeIf { it.isNotEmpty() }?.let { put("host", buildStringArray(it)) }
            }
        }
        "httpupgrade" -> {
            val httpUpgradeSettings =
                streamSettings?.get("httpupgradeSettings")?.jsonObject ?: streamSettings?.get("httpUpgradeSettings")?.jsonObject
            buildJsonObject {
                put("type", "httpupgrade")
                httpUpgradeSettings?.get("path")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                httpUpgradeSettings?.get("host")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
        }
        else -> null
    }
}

internal fun convertXrayDns(dns: JsonObject?): JsonObject? {
    if (dns == null) {
        return null
    }
    val primaryServer = dns["servers"]?.jsonArray?.firstNotNullOfOrNull(::parseXrayDnsServer) ?: return null
    return buildJsonObject {
        putJsonArray("servers") {
            add(
                buildJsonObject {
                    put("tag", "dns-local")
                    put("type", "local")
                },
            )
            add(primaryServer.asSingboxServer(tag = "dns-direct", detour = null))
            add(primaryServer.asSingboxServer(tag = "dns-remote", detour = "proxy"))
        }
        put("strategy", "prefer_ipv4")
        put("final", "dns-direct")
    }
}

internal fun convertXrayRoute(
    routing: JsonObject?,
    tagMapping: Map<String, String>,
): JsonObject? {
    if (routing == null) {
        return null
    }
    val rules =
        routing["rules"]
            ?.jsonArray
            ?.mapNotNull { element -> convertXrayRouteRule(element.jsonObject, tagMapping) }
            .orEmpty()
    if (rules.isEmpty()) {
        return null
    }
    return buildJsonObject {
        put("rules", JsonArray(rules))
        put("final", "proxy")
        put("default_domain_resolver", "dns-direct")
        put("auto_detect_interface", true)
    }
}

internal fun convertXrayRouteRule(
    rule: JsonObject,
    tagMapping: Map<String, String>,
): JsonObject? {
    val ruleType = rule["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
    if (ruleType != null && ruleType != "field") {
        return null
    }
    val outboundTag = rule["outboundTag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return buildJsonObject {
        val domains = extractStringValues(rule["domain"])
        val exactDomains = mutableListOf<String>()
        val suffixDomains = mutableListOf<String>()
        val keywordDomains = mutableListOf<String>()
        val regexDomains = mutableListOf<String>()
        domains.forEach { value ->
            when {
                value.startsWith("full:") -> exactDomains += value.removePrefix("full:")
                value.startsWith("domain:") -> suffixDomains += value.removePrefix("domain:")
                value.startsWith("keyword:") -> keywordDomains += value.removePrefix("keyword:")
                value.startsWith("regexp:") -> regexDomains += value.removePrefix("regexp:")
                value.startsWith("geosite:") -> error("unsupported xray routing domain rule: $value")
                else -> exactDomains += value
            }
        }
        exactDomains.takeIf { it.isNotEmpty() }?.let { put("domain", buildStringArray(it)) }
        suffixDomains.takeIf { it.isNotEmpty() }?.let { put("domain_suffix", buildStringArray(it)) }
        keywordDomains.takeIf { it.isNotEmpty() }?.let { put("domain_keyword", buildStringArray(it)) }
        regexDomains.takeIf { it.isNotEmpty() }?.let { put("domain_regex", buildStringArray(it)) }

        extractStringValues(rule["ip"]).takeIf { it.isNotEmpty() }?.let { put("ip_cidr", buildStringArray(it)) }
        splitCommaSeparated(rule["port"]).takeIf { it.isNotEmpty() }?.let { values ->
            val ports = values.mapNotNull(String::toIntOrNull)
            val portRanges = values.filter { it.contains('-') }
            ports.takeIf { it.isNotEmpty() }?.let { numericPorts ->
                if (numericPorts.size == 1) {
                    put("port", numericPorts.first())
                } else {
                    putJsonArray("port") {
                        numericPorts.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
            portRanges.takeIf { it.isNotEmpty() }?.let { ranges ->
                if (ranges.size == 1) {
                    put("port_range", ranges.first())
                } else {
                    put("port_range", buildStringArray(ranges))
                }
            }
        }
        splitCommaSeparated(rule["network"]).takeIf { it.isNotEmpty() }?.let { values ->
            if (values.size == 1) {
                put("network", values.first())
            } else {
                put("network", buildStringArray(values))
            }
        }
        splitCommaSeparated(rule["protocol"]).takeIf { it.isNotEmpty() }?.let { values ->
            if (values.size == 1) {
                put("protocol", values.first())
            } else {
                put("protocol", buildStringArray(values))
            }
        }

        put("action", "route")
        put(
            "outbound",
            resolveXrayRouteOutbound(outboundTag, tagMapping),
        )
    }
}

}
