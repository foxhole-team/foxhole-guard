package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.Locale

internal fun normalizeProfileBootstrapDns(root: JsonObject): JsonObject? {
    if (!root.containsKey("bootstrap_dns")) return null
    val source = requireNotNull(root["bootstrap_dns"] as? JsonObject) { "invalid bootstrap DNS policy" }
    require(source.keys.all { it in setOf("domains", "upstreams", "timeout_ms", "max_candidates") })
    val rawDomains = requireNotNull(source["domains"] as? JsonArray)
    require(rawDomains.size in 1..32)
    val domains = rawDomains.map { bootstrapDnsName(bootstrapString(it)) }
    require(domains.distinct().size == domains.size)
    val hosts = bootstrapNodeHosts(root)
    require(domains.all { domain -> hosts.any { it.first == domain } }) { "bootstrap domain is not a VPN endpoint" }
    require(hosts.none { it.first in domains && it.second }) { "pinned VPN endpoint bypasses bootstrap DNS" }
    val upstreams = requireNotNull(source["upstreams"] as? JsonArray)
    require(upstreams.size in 1..4)
    val normalized = upstreams.map { normalizeBootstrapUpstream(requireNotNull(it as? JsonObject)) }
    val deadline = bootstrapInt(source["timeout_ms"], 3000)
    val candidates = bootstrapInt(source["max_candidates"], 8)
    require(deadline in 500..10000 && candidates in 1..8)
    return JsonObject(
        mapOf(
            "domains" to JsonArray(domains.map(::JsonPrimitive)),
            "upstreams" to JsonArray(normalized),
            "timeout_ms" to JsonPrimitive(deadline),
            "max_candidates" to JsonPrimitive(candidates),
        ),
    )
}

internal fun projectProfileBootstrapDns(policy: JsonObject?, outbounds: List<JsonObject>): JsonObject? {
    if (policy == null) return null
    val hosts = mutableSetOf<String>()
    fun collect(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                (value["server"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name ->
                    runCatching { bootstrapDnsName(name) }.getOrNull()?.let(hosts::add)
                }
                value.values.forEach(::collect)
            }
            is JsonArray -> value.forEach(::collect)
            else -> Unit
        }
    }
    outbounds.forEach(::collect)
    val domains = requireNotNull(policy["domains"] as? JsonArray).filter { bootstrapString(it) in hosts }
    return if (domains.isEmpty()) null else JsonObject(policy + ("domains" to JsonArray(domains)))
}

internal fun bootstrapNodeHosts(root: JsonObject): List<Pair<String, Boolean>> =
    listOf("outbounds", "endpoints").flatMap { key ->
        (root[key] as? JsonArray).orEmpty().flatMap { element ->
            val node = requireNotNull(element as? JsonObject)
            val result = mutableListOf<Pair<String, Boolean>>()
            fun add(value: JsonElement?, pinned: Boolean) {
                val host = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
                runCatching { bootstrapDnsName(host) }.getOrNull()?.let { result += it to pinned }
            }
            add(node["server"], node.containsKey("server_ip"))
            (node["peers"] as? JsonArray).orEmpty().forEach { peerElement ->
                val peer = requireNotNull(peerElement as? JsonObject)
                val pinned = node.containsKey("server_ip") || peer.containsKey("server_ip")
                add(peer["server"] ?: peer["address"], pinned)
            }
            result
        }
    }

private fun normalizeBootstrapUpstream(source: JsonObject): JsonObject {
    val type = bootstrapString(source["type"])
    return when (type) {
        "udp", "tcp" -> normalizeBootstrapSocket(source, type)
        "dot", "doq", "doh", "doh3" -> normalizeEncryptedBootstrapUpstream(source, type)
        else -> error("unsupported bootstrap DNS transport")
    }
}

private fun normalizeBootstrapSocket(source: JsonObject, type: String): JsonObject {
    require(source.keys == setOf("type", "address"))
    val address = bootstrapString(source["address"])
    val host: String
    val port: String
    if (address.startsWith('[')) {
        val close = address.indexOf(']')
        require(close > 1 && address.getOrNull(close + 1) == ':')
        host = address.substring(1, close)
        port = address.substring(close + 2)
        require(host.contains(':'))
    } else {
        require(address.count { it == ':' } == 1)
        host = address.substringBefore(':')
        port = address.substringAfter(':')
    }
    bootstrapIp(host)
    require(port.matches(Regex("[0-9]{1,5}")) && port.toInt() in 1..65535)
    val socket = if (host.contains(':')) "[$host]:${port.toInt()}" else "$host:${port.toInt()}"
    return JsonObject(mapOf("type" to JsonPrimitive(type), "address" to JsonPrimitive(socket)))
}

private fun normalizeEncryptedBootstrapUpstream(source: JsonObject, type: String): JsonObject {
    val keys =
        if (type in setOf("dot", "doq")) {
            setOf("type", "host", "port", "server_ip", "insecure", "pinned_spki_sha256")
        } else {
            setOf("type", "url", "server_ip", "insecure", "pinned_spki_sha256")
        }
    require(source.keys.all { it in keys })
    val ip = bootstrapString(source["server_ip"])
    bootstrapIp(ip)
    val insecure = source["insecure"]?.let {
        val value = requireNotNull(it as? JsonPrimitive)
        require(!value.isString)
        requireNotNull(value.booleanOrNull)
    } ?: false
    require(!insecure) { "bootstrap DNS cannot disable certificate verification" }
    if (type in setOf("dot", "doq")) {
        bootstrapTlsName(bootstrapString(source["host"]))
        val port = bootstrapInt(source["port"])
        require(port in 1..65535 && (type != "doq" || port != 53))
    } else {
        validateBootstrapDohUrl(bootstrapString(source["url"]))
    }
    source["pinned_spki_sha256"]?.let(::validateBootstrapPin)
    return JsonObject(source + ("insecure" to JsonPrimitive(false)))
}

private fun validateBootstrapDohUrl(url: String) {
    require(url.length <= 2048 && url.all { it.code in 33..126 })
    val uri = requireNotNull(runCatching { URI(url) }.getOrNull())
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    val host = requireNotNull(uri.host).removePrefix("[").removeSuffix("]")
    bootstrapTlsName(host)
    require(uri.port == -1 || uri.port in 1..65535)
    require(!uri.rawAuthority.endsWith(':'))
}

private fun validateBootstrapPin(pin: JsonElement) {
    val text = bootstrapString(pin)
    val bytes =
        when {
            text.length == 44 && text.endsWith('=') -> Base64.getDecoder().decode(text).also {
                require(Base64.getEncoder().encodeToString(it) == text)
            }
            text.length == 43 && text.matches(Regex("[A-Za-z0-9_-]{43}")) -> Base64.getUrlDecoder().decode(text).also {
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == text)
            }
            else -> error("invalid bootstrap DNS SPKI pin")
        }
    require(bytes.size == 32)
}

private fun bootstrapTlsName(value: String) {
    if (value.contains(':') || value.matches(Regex("[0-9]+(?:\\.[0-9]+){3}"))) {
        bootstrapIp(value)
    } else {
        bootstrapDnsName(value)
    }
}

private fun bootstrapDnsName(value: String): String {
    val normalized = value.removeSuffix(".").lowercase(Locale.ROOT)
    require(normalized.length in 1..253 && normalized.all { it.code in 33..126 })
    require(
        normalized.split('.').all { label ->
            label.length in 1..63 && label.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
        },
    )
    require(!normalized.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")))
    return normalized
}

private fun bootstrapIp(value: String) {
    require(value.isNotEmpty() && '%' !in value && '[' !in value && ']' !in value)
    if (value.contains(':')) {
        require(value.matches(Regex("[0-9a-fA-F:.]+")))
    } else {
        val parts = value.split('.')
        require(
            parts.size == 4 && parts.all {
                it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 && it.toInt().toString() == it
            },
        )
    }
    val address = requireNotNull(runCatching { InetAddress.getByName(value) }.getOrNull())
    require(
        !address.isAnyLocalAddress && !address.isMulticastAddress && address.address.any {
            (it.toInt() and 255) != 255
        },
    )
}

private fun bootstrapString(value: JsonElement?): String {
    val text = requireNotNull(value as? JsonPrimitive)
    require(text.isString)
    return text.content
}

private fun bootstrapInt(value: JsonElement?, fallback: Int? = null): Int {
    if (value == null) return requireNotNull(fallback)
    val number = requireNotNull(value as? JsonPrimitive)
    require(!number.isString)
    return requireNotNull(number.intOrNull)
}
