package com.foxhole.core.importer

import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.SubscriptionEntryReport
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.requirePublicRemoteHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@Suppress("TooManyFunctions")
internal open class ProfileImportCoreSupport(
    protected val json: Json,
    protected val remoteHostResolver: RemoteHostResolver? = null,
) {
    internal fun decodeSubscriptionCandidate(candidate: String): String? {
        return runCatching { decodeBase64Url(candidate) }.getOrNull()?.takeIf { it.contains("://") }
    }

    internal fun normalizeInput(input: String): String = input.trimStart {
        it.isImportPadding()
    }.trimEnd { it.isImportPadding() }

    internal fun extractSubscriptionUrlCandidate(input: String): String? {
        if (input.startsWith("https://", ignoreCase = true) || input.startsWith("http://", ignoreCase = true)) {
            return input
        }
        val matches =
            SUBSCRIPTION_URL_REGEX
                .findAll(input)
                .map { match -> match.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '>') }
                .distinct()
                .toList()
        return matches.singleOrNull()
    }

    internal fun Char.isImportPadding(): Boolean {
        if (isWhitespace() || this == '\uFEFF' || this == '\u00A0') {
            return true
        }
        val type = Character.getType(this)
        return type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()
    }

    internal fun looksLikeJson(value: String): Boolean = value.startsWith("{") && value.endsWith("}")

    internal fun looksLikeXrayJson(value: JsonObject): Boolean =
        value["outbounds"]?.jsonArray?.any { element -> element.jsonObject.containsKey("protocol") } == true

    internal fun looksLikeWireGuard(value: String): Boolean =
        value.contains("[Interface]", ignoreCase = true) && value.contains("[Peer]", ignoreCase = true)

    companion object {
        // 4 bytes -> 8 hex chars of stable identity hash, keeping the historical tag-suffix length.
        private const val NODE_TAG_HASH_BYTES = 4
        internal val SMART_CONFIG_HEADING_REGEX = Regex("""#\s*===\s*(.+?)\s*/\s*(.+?)\s*===""")
        internal val SMART_CONFIG_EXPIRE_REGEX = Regex("""(?:^|[;\s])expire=(\d{10,13})(?:$|[;\s])""")
        internal val SMART_CONFIG_PROFILE_ID_REGEX = Regex("""(?:^|[;\s])profile_id=([^;\s]+)(?:$|[;\s])""")
        internal val SUBSCRIPTION_URL_REGEX = Regex(
            """(?<![A-Za-z0-9+.-])https?://[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        )
        internal val SHARE_URI_REGEX =
            Regex(
                """(?<![A-Za-z0-9+.-])(?:vless|trojan|naive\+https|naive|ss|outline|vmess|hy2|hysteria2|tuic|anytls)://[^\s<>"']+""",
                RegexOption.IGNORE_CASE,
            )
    }

    internal fun extractShareUriCandidates(input: String): List<String> =
        SHARE_URI_REGEX
            .findAll(input)
            .map { match -> match.value.trimEnd('.', ',', ';', ')', ']', '}', '>') }
            .distinct()
            .toList()

    internal fun extractSingleShareUriCandidate(input: String): String? =
        extractShareUriCandidates(input).singleOrNull()

    internal fun decodeBase64IfNeeded(value: String): String {
        return runCatching { decodeBase64Url(value) }.getOrElse { value }
    }

    internal fun decodeBase64Url(value: String): String {
        val normalized =
            value
                .replace('-', '+')
                .replace('_', '/')
                .let { if (it.length % 4 == 0) it else it.padEnd(it.length + (4 - it.length % 4), '=') }
        return String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
    }

    // Node tags must be DETERMINISTIC so that re-parsing an unchanged node yields a byte-identical
    // outbound (stable subscription fingerprint, no config churn on refresh). The sanitized display
    // name stays the human-readable prefix; the 8-char suffix is a stable hash of the node identity
    // (protocol + server + port + credential + display name) rather than a random UUID. Distinct
    // nodes therefore keep distinct tags, and within-config collisions are resolved deterministically
    // by deduplicateNodeTags.
    internal fun tagFor(
        displayName: String,
        type: String,
        server: String,
        port: Int?,
        credential: String?,
    ): String {
        val prefix =
            displayName
                .lowercase()
                .replace(nodeTagSanitizeRegex, "-")
                .trim('-')
                .takeIf { it.isNotBlank() }
                ?: "node"
        val seed =
            listOf(type, server, port?.toString().orEmpty(), credential.orEmpty(), displayName)
                .joinToString(separator = "\u0000")
        return "$prefix-${stableTagHash(seed)}"
    }

    // Deterministic de-dup pass shared by every node-list assembly point. Two nodes producing the
    // same tag (genuine duplicate identity or an astronomically rare hash collision) are made unique
    // by appending a first-seen ordinal (-2, -3, ...). The rewrite preserves node ordering and the
    // tag's position within the outbound/endpoint JSON, so the result stays byte-stable for a fixed
    // input ordering while keeping normalized selector/outbound references collision-free.
    internal fun deduplicateNodeTags(nodes: List<ProxyNode>): List<ProxyNode> {
        val seenCounts = mutableMapOf<String, Int>()
        return nodes.map { node ->
            val baseTag = node.tag
            val occurrence = (seenCounts[baseTag] ?: 0) + 1
            seenCounts[baseTag] = occurrence
            if (occurrence == 1) node else node.withDisambiguatedTag("$baseTag-$occurrence")
        }
    }

    // Two share URIs describing the SAME normalized outbound are one server, not two profiles. The most
    // common source is a Shadowsocks node advertised both as a plain ss:// and as an Outline
    // ss://...?outline=1 (Outline IS Shadowsocks — the OUTLINE hint is cosmetic, the emitted outbound
    // is byte-identical). Identity is the outbound/endpoint body WITHOUT its tag: the tag folds in the
    // #display-name, so differently-named identical servers would otherwise slip past deduplicateNodeTags.
    // When both representations exist the plain (non-OUTLINE) node wins so the surviving label reads
    // Shadowsocks, keeping first-seen position.
    internal fun deduplicateNodeIdentities(nodes: List<ProxyNode>): List<ProxyNode> {
        val chosen = LinkedHashMap<JsonObject, ProxyNode>()
        nodes.forEach { node ->
            val key = nodeIdentityBody(node)
            val existing = chosen[key]
            when {
                existing == null -> chosen[key] = node
                existing.protocolHint == ProtocolHint.OUTLINE && node.protocolHint != ProtocolHint.OUTLINE ->
                    chosen[key] = node
            }
        }
        return chosen.values.toList()
    }

    // Outbound/endpoint body with the tag stripped — the structural identity two share URIs must share
    // to count as the same server (JsonObject equality is order-independent).
    internal fun nodeIdentityBody(node: ProxyNode): JsonObject {
        val body = node.outbound ?: node.endpoint ?: return JsonObject(emptyMap())
        return JsonObject(body.filterKeys { key -> key != "tag" })
    }

    private fun ProxyNode.withDisambiguatedTag(newTag: String): ProxyNode =
        copy(
            outbound = outbound?.let { replaceTagValue(it, newTag) },
            endpoint = endpoint?.let { replaceTagValue(it, newTag) },
        )

    private fun replaceTagValue(
        source: JsonObject,
        newTag: String,
    ): JsonObject =
        buildJsonObject {
            source.forEach { (key, value) ->
                if (key == "tag") put("tag", newTag) else put(key, value)
            }
        }

    private fun stableTagHash(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            for (index in 0 until NODE_TAG_HASH_BYTES) {
                append("%02x".format(digest[index].toInt() and 0xff))
            }
        }
    }

    internal fun buildStringArray(values: Iterable<String>): JsonArray =
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        }

    internal fun String.toFlexibleBoolean(): Boolean? =
        when (lowercase()) {
            "1", "true", "on", "enabled" -> true
            "0", "false", "off", "disabled" -> false
            else -> null
        }

    internal fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        val parameters = linkedMapOf<String, String>()
        rawQuery
            ?.split('&')
            ?.forEach { item ->
                if (item.isBlank()) {
                    return@forEach
                }
                val key = uriDecode(item.substringBefore('=')).trim()
                val value = uriDecode(item.substringAfter('=', ""))
                if (key.isNotBlank()) {
                    parameters[key] = value
                    parameters[key.lowercase()] = value
                }
            }
        return parameters
    }

    internal fun uriDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    @Suppress("ThrowsCount")
    internal fun parseLenientUri(value: String): URI {
        return runCatching { URI(value) }.getOrElse { originalError ->
            val schemeSeparatorIndex = value.indexOf("://")
            require(schemeSeparatorIndex > 0) { throw originalError }
            val fragmentIndex = value.indexOf('#', startIndex = schemeSeparatorIndex + 3)
            require(fragmentIndex >= 0) { throw originalError }
            val scheme = value.substring(0, schemeSeparatorIndex)
            val schemeSpecificPart = "//" + value.substring(schemeSeparatorIndex + 3, fragmentIndex)
            val fragment = value.substring(fragmentIndex + 1)
            runCatching { URI(scheme, schemeSpecificPart, fragment) }.getOrElse { throw originalError }
        }
    }

    internal fun parseRemoteEndpoint(
        raw: String,
        defaultPort: Int? = null,
    ): RemoteEndpoint {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "remote endpoint is missing" }
        if (trimmed.startsWith("[")) {
            val closingBracket = trimmed.indexOf(']')
            require(closingBracket > 1) { "invalid remote endpoint" }
            val host = trimmed.substring(1, closingBracket)
            val port =
                trimmed
                    .substring(closingBracket + 1)
                    .takeIf(String::isNotBlank)
                    ?.also { suffix -> require(suffix.startsWith(':')) { "invalid remote endpoint" } }
                    ?.removePrefix(":")
                    ?.toIntOrNull()
                    ?: defaultPort
            return RemoteEndpoint(host = host, port = port)
        }
        val colonCount = trimmed.count { it == ':' }
        if (colonCount == 0) {
            return RemoteEndpoint(host = trimmed, port = defaultPort)
        }
        if (colonCount == 1) {
            val host = trimmed.substringBefore(':')
            val port = trimmed.substringAfter(':', "").toIntOrNull() ?: defaultPort
            return RemoteEndpoint(host = host, port = port)
        }
        return RemoteEndpoint(host = trimmed, port = defaultPort)
    }

    internal fun displayNameFromUri(
        uri: URI,
        fallbackHost: String,
    ): String {
        val fragment =
            uri.fragment
                ?.takeIf { it.isNotBlank() }
                ?.let(::uriDecode)
                ?.substringBefore('?')
                ?.trim()
        return fragment?.takeIf { it.isNotBlank() } ?: fallbackHost
    }

    internal fun preferredXrayNodeName(
        outbound: JsonObject,
        fallbackHost: String,
    ): String =
        outbound["tag"]?.jsonPrimitive?.contentOrNull?.takeIf {
            it.isNotBlank() && it.lowercase() !in setOf("proxy", "direct", "block")
        } ?: fallbackHost

    internal fun extractStringValues(element: JsonElement?): List<String> =
        when (element) {
            is JsonArray ->
                element.mapNotNull { item ->
                    item.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                }
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
            else -> emptyList()
        }

    internal fun splitCommaSeparated(element: JsonElement?): List<String> =
        extractStringValues(element)
            .flatMap { value -> value.split(',') }
            .map(String::trim)
            .filter(String::isNotBlank)

    internal fun resolveXrayRouteOutbound(
        outboundTag: String,
        tagMapping: Map<String, String>,
    ): String =
        tagMapping[outboundTag]
            ?: when (outboundTag.lowercase()) {
                "proxy" -> "proxy"
                "direct", "freedom" -> "direct"
                "block", "blackhole" -> "block"
                else -> error("unsupported xray routing outbound: $outboundTag")
            }

    internal fun parseXrayDnsServer(element: JsonElement): XrayDnsServer? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf(
                String::isNotBlank
            )?.let(::parseXrayDnsServerAddress)
            is JsonObject ->
                element["address"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(
                    String::isNotBlank
                )?.let(::parseXrayDnsServerAddress)
            else -> null
        }
    }

    internal fun parseXrayDnsServerAddress(address: String): XrayDnsServer? {
        return when {
            address.startsWith("https://", ignoreCase = true) -> {
                val uri = URI(address)
                val host = uri.host ?: return null
                XrayDnsServer(
                    type = "https",
                    server = host,
                    port = uri.port.takeIf { it > 0 } ?: 443,
                    path = uri.rawPath?.takeIf { it.isNotBlank() },
                )
            }
            else -> {
                val endpoint = runCatching { parseRemoteEndpoint(address, defaultPort = 53) }.getOrNull() ?: return null
                XrayDnsServer(
                    type = "udp",
                    server = endpoint.host,
                    port = endpoint.port ?: 53,
                    path = null,
                )
            }
        }
    }

    internal fun normalizeTlsSettings(
        element: JsonElement,
        allowInsecureTls: Boolean,
    ): JsonObject = normalizeTlsSettingsElement(element, allowInsecureTls).jsonObject

    internal fun normalizeTlsSettingsElement(
        element: JsonElement,
        allowInsecureTls: Boolean,
    ): JsonElement =
        when (element) {
            is JsonObject -> {
                var insecureTls: Boolean? = null
                buildJsonObject {
                    element.forEach { (key, value) ->
                        when {
                            key.isInsecureTlsSettingKey() -> {
                                val normalizedValue = flexibleBooleanOrNull(value)
                                require(allowInsecureTls || normalizedValue != true) { "INSECURE TLS is not allowed" }
                                insecureTls = normalizedValue ?: insecureTls
                            }
                            else -> put(key, normalizeTlsSettingsElement(value, allowInsecureTls))
                        }
                    }
                    insecureTls?.let { put("insecure", it) }
                }
            }
            is JsonArray -> JsonArray(element.map { item -> normalizeTlsSettingsElement(item, allowInsecureTls) })
            else -> element
        }

    // Must stay a superset of the detector's isInsecureTlsKey (app: ProfileInsecureTlsSupport) —
    // any spelling the detector flags has to be consumed here, or a strict parse passes a marker
    // through that later demands consent the strict gate never enforced.
    internal fun String.isInsecureTlsSettingKey(): Boolean =
        equals("insecure", ignoreCase = true) ||
            equals("allowInsecure", ignoreCase = true) ||
            equals("allow_insecure", ignoreCase = true)

    internal fun flexibleBooleanOrNull(element: JsonElement): Boolean? = element.jsonPrimitive.contentOrNull?.toFlexibleBoolean()

    internal fun subscriptionExpirationFromQuery(rawQuery: String?): Long? =
        SubscriptionMetadataParser.expirationFromRawQuery(rawQuery)

    internal fun subscriptionExpirationFromJson(objectValue: JsonObject): Long? =
        listOf(
            "expire",
            "expires",
            "expiry",
            "expiration",
            "subscription-expire",
            "subscription_expires_at",
            "subscription-expiration",
        ).firstNotNullOfOrNull { key ->
            SubscriptionMetadataParser.parseExpirationValue(objectValue[key]?.jsonPrimitive?.contentOrNull)
        }

    internal fun requireAllowedRemoteHosts(
        config: JsonObject,
        allowPrivateOutboundHosts: Boolean,
    ) {
        if (allowPrivateOutboundHosts) {
            return
        }
        extractNormalizedRemoteHosts(config)
            .distinct()
            .forEach { host ->
                host.requirePublicRemoteHost(resolveHost = true, resolver = remoteHostResolver)
            }
    }

    internal fun requireAllowedOutboundHosts(
        outbounds: JsonArray,
        allowPrivateOutboundHosts: Boolean,
    ) {
        outbounds.forEach { outbound ->
            validateOutboundHost(
                host = outbound.jsonObject["server"]?.jsonPrimitive?.contentOrNull,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            )
        }
    }

    internal fun validateOutboundHost(
        host: String?,
        allowPrivateOutboundHosts: Boolean,
    ) {
        if (allowPrivateOutboundHosts) {
            return
        }
        host
            ?.takeIf(String::isNotBlank)
            ?.requirePublicRemoteHost(resolveHost = true, resolver = remoteHostResolver)
    }

    private fun extractNormalizedRemoteHosts(config: JsonObject): List<String> =
        buildList {
            config["outbounds"]?.jsonArray?.forEach { outboundElement ->
                val outbound = outboundElement.jsonObject
                outbound["server"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let(::normalizeRemoteHostValue)
                    ?.let(::add)
                outbound["peers"]?.jsonArray?.forEach { peerElement ->
                    peerElement.jsonObject["server"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let(::normalizeRemoteHostValue)
                        ?.let(::add)
                }
                addAll(outbound["tls"]?.jsonObject?.get("server_name")?.collectHostValues().orEmpty())
                outbound["transport"]?.jsonObject?.let { transport ->
                    addAll(transport["host"]?.collectHostValues().orEmpty())
                    addAll(transport["headers"]?.jsonObject?.get("Host")?.collectHostValues().orEmpty())
                }
            }
            config["endpoints"]?.jsonArray?.forEach { endpointElement ->
                val endpoint = endpointElement.jsonObject
                endpoint["peers"]?.jsonArray?.forEach { peerElement ->
                    peerElement.jsonObject["address"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let(::normalizeRemoteHostValue)
                        ?.let(::add)
                }
            }
            config["dns"]?.jsonObject?.get("servers")?.jsonArray?.forEach { serverElement ->
                extractDnsServerHost(serverElement)?.let(::add)
            }
        }

    private fun JsonElement.collectHostValues(): List<String> =
        when (this) {
            is JsonArray -> flatMap { item -> item.collectHostValues() }
            is JsonPrimitive -> contentOrNull?.let(::normalizeRemoteHostValue)?.let(::listOf).orEmpty()
            else -> emptyList()
        }

    private fun extractDnsServerHost(element: JsonElement): String? =
        when (element) {
            is JsonObject ->
                if (element["tag"]?.jsonPrimitive?.contentOrNull == WIREGUARD_DNS_SERVER_TAG) {
                    // The resolver a WireGuard peer pushes normally lives inside the tunnel
                    // (10.x, 172.16.x, fd00::/8). It is never dialled from the device — it is
                    // advertised on the TUN and reached as a packet through the tunnel — so the
                    // public-host rule that guards outbound endpoints does not apply to it, and
                    // applying it would fail the import of an ordinary WireGuard profile.
                    null
                } else {
                    element["server"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let(::normalizeDnsServerValue)
                }
            is JsonPrimitive -> element.contentOrNull?.let(::normalizeDnsServerValue)
            else -> null
        }

    private fun normalizeDnsServerValue(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return null
        }
        if (trimmed.equals("local", ignoreCase = true) || trimmed.equals("system", ignoreCase = true)) {
            return null
        }
        if (trimmed.contains("://")) {
            return runCatching { URI(trimmed) }.getOrNull()?.host?.takeIf(String::isNotBlank)
        }
        return normalizeRemoteHostValue(trimmed)
    }

    private fun normalizeRemoteHostValue(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return null
        }
        if (trimmed.contains("://")) {
            return runCatching { URI(trimmed) }.getOrNull()?.host?.takeIf(String::isNotBlank)
        }
        return parseRemoteEndpoint(trimmed).host.trim().takeIf(String::isNotBlank)
    }

    internal data class ProxyNode(
        val displayName: String,
        val protocolHint: ProtocolHint,
        val outbound: JsonObject?,
        val endpoint: JsonObject? = null,
        val subscriptionExpiresAt: Long? = null,
        val dnsServers: List<String> = emptyList(),
    ) {
        val tag: String
            get() =
                (outbound ?: endpoint)
                    ?.get("tag")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: error("missing node tag")
    }

    internal data class RemoteEndpoint(
        val host: String,
        val port: Int?,
    )

    internal data class SmartConfigHeading(
        val protocolLabel: String,
        val routeLabel: String,
    )

    internal data class SmartConfigEntry(
        val heading: SmartConfigHeading,
        val node: ProxyNode,
        val profileGroupKey: String? = null,
    )

    internal data class SmartConfigImport(
        val displayName: String,
        val protocolHint: ProtocolHint,
        val normalizedConfigJson: String,
        val protocolOptions: List<StoredProfileProtocolOption>,
        val selectedProtocolOptionId: String,
        val nodesCount: Int,
        val subscriptionExpiresAt: Long? = null,
        val entryReports: List<SubscriptionEntryReport> = emptyList(),
    ) {
        fun toParsedSubscriptionImport(): ParsedSubscriptionImport =
            ParsedSubscriptionImport(
                displayName = displayName,
                profiles =
                listOf(
                    ParsedSubscriptionProfile(
                        displayName = displayName,
                        protocolHint = protocolHint,
                        normalizedConfigJson = normalizedConfigJson,
                        subscriptionExpiresAt = subscriptionExpiresAt,
                        protocolOptions = protocolOptions,
                        selectedProtocolOptionId = selectedProtocolOptionId,
                    ),
                ),
                subscriptionExpiresAt = subscriptionExpiresAt,
                entryReports = entryReports,
            )
    }

    internal data class RawJsonImport(
        val displayName: String,
        val protocolHint: ProtocolHint,
        val normalizedConfigJson: String,
        val subscriptionExpiresAt: Long? = null,
    )

    internal data class XrayOutboundsConversion(
        val nodes: List<ProxyNode>,
        val tagMapping: Map<String, String>,
    )

    internal data class XrayDnsServer(
        val type: String,
        val server: String,
        val port: Int,
        val path: String?,
    ) {
        fun asNormalizedServer(
            tag: String,
            detour: String?,
        ): JsonObject =
            buildJsonObject {
                put("tag", tag)
                put("type", type)
                put("server", server)
                put("server_port", port)
                path?.let { put("path", it) }
                detour?.let { put("detour", it) }
            }
    }
}

private val nodeTagSanitizeRegex = Regex("[^a-z0-9]+")

/**
 * The managed tag for the resolver a WireGuard `DNS =` line becomes.
 *
 * Read by the runtime translator, which advertises this address on the TUN: a packet-tunnel profile
 * does not intercept DNS, so this entry is the only resolver such a profile has.
 */
internal const val WIREGUARD_DNS_SERVER_TAG = "dns-wireguard"
