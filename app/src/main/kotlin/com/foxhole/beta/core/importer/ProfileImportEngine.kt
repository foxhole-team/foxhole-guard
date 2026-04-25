package com.foxhole.beta.core.importer

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.ParsedImport
import com.foxhole.beta.core.model.ParsedSubscriptionImport
import com.foxhole.beta.core.model.ParsedSubscriptionProfile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.network.RemoteHostResolver
import com.foxhole.beta.core.network.ensurePublicUrl
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
import kotlinx.serialization.json.putJsonArray

internal class ProfileImportEngine(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportXraySupport(json, remoteHostResolver) {
internal data class NormalizedRoutePort(
    val ports: List<Int>,
    val portRanges: List<String>,
)

fun parseUserInput(
    input: String,
    allowPrivateOutboundHosts: Boolean = false,
    allowHttpSubscriptionUrls: Boolean = false,
    allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
): ParsedImport {
    val trimmed = normalizeInput(input)
    require(trimmed.isNotBlank()) { "empty input" }

    val subscriptionUrlCandidate = extractSubscriptionUrlCandidate(trimmed)
    if (subscriptionUrlCandidate != null) {
        val url =
            subscriptionUrlCandidate.ensurePublicUrl(
                allowHttp = allowHttpSubscriptionUrls,
                resolveHost = true,
                resolver = remoteHostResolver,
            )
        require(url.isHttps || allowHttpSubscriptionUrls) { "only https subscriptions are allowed" }
        return ParsedImport(
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            protocolHint = ProtocolHint.UNKNOWN,
            displayName = url.host.ifBlank { "subscription" },
            normalizedConfigJson = null,
            sourceUrl = url.toString(),
        )
    }

    parseSmartConfigImport(
        raw = trimmed,
        fallbackName = null,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
    )?.let { parsed ->
        return ParsedImport(
            sourceType = ProfileSourceType.SHARE_URI,
            protocolHint = parsed.protocolHint,
            displayName = parsed.displayName,
            normalizedConfigJson = parsed.normalizedConfigJson,
            nodesCount = parsed.nodesCount,
            subscriptionExpiresAt = parsed.subscriptionExpiresAt,
            protocolOptions = parsed.protocolOptions,
            selectedProtocolOptionId = parsed.selectedProtocolOptionId,
        )
    }

    if (looksLikeWireGuard(trimmed)) {
        return ParsedImport(
            sourceType = ProfileSourceType.RAW_WIREGUARD_TEXT,
            protocolHint = ProtocolHint.WIREGUARD,
            displayName = "wireguard",
            normalizedConfigJson =
                buildConfigFromNodes(
                    listOf(parseWireGuardConfig(trimmed, "wireguard", allowPrivateOutboundHosts)),
                    allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                ),
        )
    }

    if (looksLikeJson(trimmed)) {
        val objectValue = json.parseToJsonElement(trimmed).jsonObject
        if (looksLikeXrayJson(objectValue)) {
            val converted = normalizeRawXrayConfig(objectValue, allowPrivateOutboundHosts, allowInsecureTls)
            return ParsedImport(
                sourceType = ProfileSourceType.RAW_SINGBOX_JSON,
                protocolHint = converted.protocolHint,
                displayName = converted.displayName,
                normalizedConfigJson = converted.normalizedConfigJson,
                subscriptionExpiresAt = converted.subscriptionExpiresAt,
            )
        }
        return ParsedImport(
            sourceType = ProfileSourceType.RAW_SINGBOX_JSON,
            protocolHint = ProtocolHint.SING_BOX,
            displayName = "sing-box",
            normalizedConfigJson = normalizeRawSingBoxConfig(objectValue, allowPrivateOutboundHosts, allowInsecureTls),
        )
    }

    val directNodes = parseNodeLines(trimmed, allowPrivateOutboundHosts, allowInsecureTls)
    if (directNodes.isNotEmpty()) {
        return ParsedImport(
            sourceType = ProfileSourceType.SHARE_URI,
            protocolHint = directNodes.first().protocolHint,
            displayName = directNodes.first().displayName,
            normalizedConfigJson = buildConfigFromNodes(directNodes, allowPrivateOutboundHosts = allowPrivateOutboundHosts),
            nodesCount = directNodes.size,
            subscriptionExpiresAt = directNodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
        )
    }

    throw IllegalArgumentException("unsupported import format")
}

fun parseSubscriptionContent(
    rawContent: String,
    fallbackName: String,
    allowPrivateOutboundHosts: Boolean = false,
    allowHttpSubscriptionUrls: Boolean = false,
    allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
): ParsedImport {
    val trimmed = rawContent.trim()
    require(trimmed.isNotBlank()) { "subscription is empty" }

    runCatching {
        parseUserInput(
            input = trimmed,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )
    }.getOrNull()?.let { parsed ->
        return parsed.copy(
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            displayName = parsed.displayName.ifBlank { fallbackName },
        )
    }

    parseSubscriptionPayloadImport(
        raw = trimmed,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
        includeNodeLines = false,
    )?.let { return it }

    decodeSubscriptionCandidate(trimmed)?.let { decoded ->
        parseSubscriptionPayloadImport(
            raw = decoded,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )?.let { return it }
    }

    parseSubscriptionPayloadImport(
        raw = trimmed,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
    )?.let { return it }

    throw IllegalArgumentException("unsupported subscription payload")
}

fun parseSubscriptionProfiles(
    rawContent: String,
    fallbackName: String,
    allowPrivateOutboundHosts: Boolean = false,
    allowHttpSubscriptionUrls: Boolean = false,
    allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
): ParsedSubscriptionImport {
    val trimmed = normalizeInput(rawContent)
    require(trimmed.isNotBlank()) { "subscription is empty" }

    parseSubscriptionPayloadProfiles(
        raw = trimmed,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
        includeNodeLines = false,
    )?.let { return it }

    decodeSubscriptionCandidate(trimmed)?.let { decoded ->
        parseSubscriptionPayloadProfiles(
            raw = decoded,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )?.let { return it }
    }

    parseSubscriptionPayloadProfiles(
        raw = trimmed,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
    )?.let { return it }

    runCatching {
        parseUserInput(
            input = trimmed,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )
    }.getOrNull()?.let { parsed ->
        parsed.normalizedConfigJson?.let { resolvedConfig ->
            val displayName = parsed.displayName.ifBlank { fallbackName }
            return ParsedSubscriptionImport(
                displayName = displayName,
                profiles =
                    listOf(
                        ParsedSubscriptionProfile(
                            displayName = displayName,
                            protocolHint = parsed.protocolHint,
                            normalizedConfigJson = resolvedConfig,
                            subscriptionExpiresAt = parsed.subscriptionExpiresAt,
                        ),
                    ),
                subscriptionExpiresAt = parsed.subscriptionExpiresAt,
            )
        }
    }

    throw IllegalArgumentException("unsupported subscription payload")
}

private fun parseSubscriptionPayloadImport(
    raw: String,
    fallbackName: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
    includeNodeLines: Boolean = true,
): ParsedImport? {
    parseSmartConfigImport(
        raw = raw,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
    )?.let { parsed ->
        return ParsedImport(
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            protocolHint = parsed.protocolHint,
            displayName = parsed.displayName,
            normalizedConfigJson = parsed.normalizedConfigJson,
            nodesCount = parsed.nodesCount,
            subscriptionExpiresAt = parsed.subscriptionExpiresAt,
            protocolOptions = parsed.protocolOptions,
            selectedProtocolOptionId = parsed.selectedProtocolOptionId,
        )
    }

    if (!includeNodeLines) {
        return null
    }
    val nodes = parseNodeLines(raw, allowPrivateOutboundHosts, allowInsecureTls)
    if (nodes.isEmpty()) {
        return null
    }
    return ParsedImport(
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        protocolHint = nodes.first().protocolHint,
        displayName = fallbackName,
        normalizedConfigJson = buildConfigFromNodes(nodes, allowPrivateOutboundHosts = allowPrivateOutboundHosts),
        nodesCount = nodes.size,
        subscriptionExpiresAt = nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
    )
}

private fun parseSubscriptionPayloadProfiles(
    raw: String,
    fallbackName: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
    includeNodeLines: Boolean = true,
): ParsedSubscriptionImport? {
    parseSmartConfigSubscriptionImport(
        raw = raw,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        allowInsecureTls = allowInsecureTls,
    )?.let { return it }

    if (!includeNodeLines) {
        return null
    }
    val nodes = parseNodeLines(raw, allowPrivateOutboundHosts, allowInsecureTls)
    if (nodes.isEmpty()) {
        return null
    }
    return buildSubscriptionProfiles(
        nodes = nodes,
        fallbackName = fallbackName,
        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
    )
}

fun sanitizeResolvedConfig(
    raw: String,
    allowPrivateOutboundHosts: Boolean = false,
    allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
): String {
    val objectValue =
        normalizeTlsSettings(
            json.parseToJsonElement(normalizeLegacyResolvedRoutePorts(raw)).jsonObject,
            allowInsecureTls = allowInsecureTls,
        )
    val outbounds = objectValue["outbounds"]?.jsonArray ?: error("resolved config must define outbounds")
    requireAllowedOutboundHosts(outbounds, allowPrivateOutboundHosts)
    val experimental = objectValue["experimental"]?.jsonObject
    require(experimental?.containsKey("clash_api") != true) { "clash api is not allowed" }
    require(experimental?.containsKey("v2ray_api") != true) { "v2ray api is not allowed" }

    val sanitizedInbounds =
        objectValue["inbounds"]?.jsonArray?.also { inbounds ->
            require(
                inbounds.all { inbound ->
                    inbound.jsonObject["type"]?.jsonPrimitive?.content == "tun"
                },
            ) { "resolved config must not define local inbounds" }
        } ?: buildBaseConfig(outbounds)["inbounds"]!!.jsonArray
    val sanitizedRoute = objectValue["route"]?.jsonObject?.let(::normalizeResolvedRoute)

    val filteredExperimental =
        experimental
            ?.filterKeys { key -> key != "clash_api" && key != "v2ray_api" }
            ?.let(::JsonObject)

    val sanitized =
        buildJsonObject {
            objectValue.forEach { (key, value) ->
                when (key) {
                    "inbounds" -> put(key, sanitizedInbounds)
                    "route" -> sanitizedRoute?.let { put(key, it) } ?: put(key, value)
                    "experimental" -> Unit
                    else -> put(key, value)
                }
            }
            if (!objectValue.containsKey("dns") || !objectValue.containsKey("route") || !objectValue.containsKey("inbounds")) {
                val defaults =
                    buildBaseConfig(
                        outbounds = outbounds,
                        routeOverride = sanitizedRoute,
                        dnsOverride = objectValue["dns"]?.jsonObject,
                    )
                if (!objectValue.containsKey("dns")) {
                    put("dns", defaults["dns"]!!)
                }
                if (!objectValue.containsKey("route")) {
                    put("route", defaults["route"]!!)
                }
                if (!objectValue.containsKey("inbounds")) {
                    put("inbounds", defaults["inbounds"]!!)
                }
            }
            filteredExperimental?.takeIf { it.isNotEmpty() }?.let { put("experimental", it) }
        }

    requireAllowedRemoteHosts(sanitized, allowPrivateOutboundHosts)
    return json.encodeToString(JsonObject.serializer(), sanitized)
}

internal fun normalizeLegacyResolvedRoutePorts(raw: String): String =
    raw.replace(Regex("""("port"\s*:\s*)"([1-9]\d{0,4})"""")) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}"
    }

internal fun normalizeResolvedRoute(route: JsonObject): JsonObject {
    val rules = route["rules"]?.jsonArray ?: return route
    return buildJsonObject {
        route.forEach { (key, value) ->
            if (key == "rules") {
                put("rules", JsonArray(rules.map { normalizeResolvedRouteRule(it.jsonObject) }))
            } else {
                put(key, value)
            }
        }
    }
}

internal fun normalizeResolvedRouteRule(rule: JsonObject): JsonObject {
    val rawPort = rule["port"] ?: return rule
    val normalizedPort = normalizeResolvedRoutePort(rawPort)
        ?: error("unsupported resolved route port matcher: $rawPort")
    return buildJsonObject {
        rule.forEach { (key, value) ->
            when (key) {
                "port" ->
                    normalizedPort.ports.takeIf { it.isNotEmpty() }?.let { ports ->
                        if (ports.size == 1) {
                            put("port", ports.first())
                        } else {
                            putJsonArray("port") {
                                ports.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                "port_range" -> {
                    if (rule.containsKey("port_range")) {
                        put("port_range", value)
                    } else {
                        normalizedPort.portRanges.takeIf { it.isNotEmpty() }?.let { ranges ->
                            if (ranges.size == 1) {
                                put("port_range", ranges.first())
                            } else {
                                put("port_range", buildStringArray(ranges))
                            }
                        }
                    }
                }
                else -> put(key, value)
            }
        }
        if (!rule.containsKey("port_range")) {
            normalizedPort.portRanges.takeIf { it.isNotEmpty() }?.let { ranges ->
                if (ranges.size == 1) {
                    put("port_range", ranges.first())
                } else {
                    put("port_range", buildStringArray(ranges))
                }
            }
        }
    }
}

internal fun normalizeResolvedRoutePort(element: JsonElement?): NormalizedRoutePort? {
    if (element == null) {
        return null
    }
    val values = splitCommaSeparated(element)
    if (values.isEmpty()) {
        return null
    }
    val numericPorts = mutableListOf<Int>()
    val portRanges = mutableListOf<String>()
    values.forEach { value ->
        value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { numericPort ->
            numericPorts += numericPort
            return@forEach
        }
        if (value.matches(Regex("""\d{1,5}-\d{1,5}"""))) {
            portRanges += value
            return@forEach
        }
        error("unsupported resolved route port matcher: $value")
    }
    return NormalizedRoutePort(
        ports = numericPorts,
        portRanges = portRanges,
    )
}

internal fun buildSubscriptionProfiles(
    nodes: List<ProxyNode>,
    fallbackName: String,
    allowPrivateOutboundHosts: Boolean,
): ParsedSubscriptionImport {
    require(nodes.isNotEmpty()) { "empty node list" }
    val profiles =
        nodes.mapIndexed { index, node ->
            val displayName =
                node.displayName.ifBlank {
                    if (nodes.size == 1) {
                        fallbackName
                    } else {
                        "$fallbackName ${index + 1}"
                    }
                }
            ParsedSubscriptionProfile(
                displayName = displayName,
                protocolHint = node.protocolHint,
                normalizedConfigJson = buildConfigFromNodes(listOf(node), allowPrivateOutboundHosts = allowPrivateOutboundHosts),
                subscriptionExpiresAt = node.subscriptionExpiresAt,
            )
        }
    return ParsedSubscriptionImport(
        displayName = if (profiles.size == 1) profiles.first().displayName else fallbackName,
        profiles = profiles,
        subscriptionExpiresAt = nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
    )
}

internal fun parseSmartConfigImport(
    raw: String,
    fallbackName: String?,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): SmartConfigImport? {
    val entries =
        parseSmartConfigEntries(
            raw = raw,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )
    if (entries.isEmpty()) {
        return null
    }
    val protocolOptions =
        entries.mapIndexed { index, entry ->
            StoredProfileProtocolOption(
                id = smartConfigOptionId(entry, index),
                displayName = smartConfigOptionDisplayName(entry),
                protocolHint = entry.node.protocolHint,
                normalizedConfigJson = buildConfigFromNodes(listOf(entry.node), allowPrivateOutboundHosts = allowPrivateOutboundHosts),
            )
        }
    val selectedOption = protocolOptions.first()
    return SmartConfigImport(
        displayName = resolveSmartConfigDisplayName(entries, fallbackName),
        protocolHint = selectedOption.protocolHint,
        normalizedConfigJson = selectedOption.normalizedConfigJson,
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = selectedOption.id,
        nodesCount = protocolOptions.size,
        subscriptionExpiresAt = entries.mapNotNull { it.node.subscriptionExpiresAt }.minOrNull(),
    )
}

internal fun parseSmartConfigSubscriptionImport(
    raw: String,
    fallbackName: String?,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): ParsedSubscriptionImport? {
    val entries =
        parseSmartConfigEntries(
            raw = raw,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )
    if (entries.isEmpty()) {
        return null
    }
    val groupedEntries = linkedMapOf<String, MutableList<SmartConfigEntry>>()
    entries.forEach { entry ->
        val routeKey = slugifySmartConfigKey(entry.heading.routeLabel).ifBlank { "default" }
        groupedEntries.getOrPut(routeKey) { mutableListOf() }.add(entry)
    }
    val profiles =
        groupedEntries.values.map { routeEntries ->
            buildSmartConfigRouteProfile(
                entries = routeEntries,
                fallbackName = fallbackName,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            )
        }
    return ParsedSubscriptionImport(
        displayName = resolveSmartConfigDisplayName(entries, fallbackName),
        profiles = profiles,
        subscriptionExpiresAt = profiles.mapNotNull(ParsedSubscriptionProfile::subscriptionExpiresAt).minOrNull(),
    )
}

internal fun buildSmartConfigRouteProfile(
    entries: List<SmartConfigEntry>,
    fallbackName: String?,
    allowPrivateOutboundHosts: Boolean,
): ParsedSubscriptionProfile {
    require(entries.isNotEmpty()) { "empty smart config route profile" }
    val usedIds = mutableMapOf<String, Int>()
    val protocolOptions =
        entries.map { entry ->
            val baseId = entry.node.protocolHint.name.lowercase()
            val count = (usedIds[baseId] ?: 0) + 1
            usedIds[baseId] = count
            StoredProfileProtocolOption(
                id = if (count == 1) baseId else "${baseId}_$count",
                displayName = protocolDisplayLabel(entry.node.protocolHint),
                protocolHint = entry.node.protocolHint,
                normalizedConfigJson = buildConfigFromNodes(listOf(entry.node), allowPrivateOutboundHosts = allowPrivateOutboundHosts),
            )
        }
    val selectedOption = protocolOptions.first()
    return ParsedSubscriptionProfile(
        displayName = resolveSmartConfigProfileDisplayName(entries, fallbackName),
        protocolHint = selectedOption.protocolHint,
        normalizedConfigJson = selectedOption.normalizedConfigJson,
        subscriptionExpiresAt = entries.mapNotNull { it.node.subscriptionExpiresAt }.minOrNull(),
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = selectedOption.id,
    )
}

internal fun parseSmartConfigEntries(
    raw: String,
    allowPrivateOutboundHosts: Boolean,
    allowInsecureTls: Boolean,
): List<SmartConfigEntry> {
    val lines = raw.lineSequence().map(String::trimEnd).toList()
    val normalizedLines = lines.map(::normalizeInput)
    if (normalizedLines.none { parseSmartConfigHeading(it) != null }) {
        return emptyList()
    }
    val entries = mutableListOf<SmartConfigEntry>()
    var index = 0
    while (index < normalizedLines.size) {
        val trimmed = normalizedLines[index]
        if (trimmed.isBlank()) {
            index += 1
            continue
        }
        val heading = parseSmartConfigHeading(trimmed) ?: return emptyList()
        index += 1
        val payloadLines = mutableListOf<String>()
        val metadataLines = mutableListOf<String>()
        while (index < normalizedLines.size) {
            val candidate = normalizedLines[index]
            if (candidate.isBlank()) {
                index += 1
                continue
            }
            if (parseSmartConfigHeading(candidate) != null) {
                break
            }
            if (candidate.startsWith("#")) {
                metadataLines += candidate
            } else {
                payloadLines += candidate
            }
            index += 1
        }
        if (payloadLines.isEmpty()) {
            return emptyList()
        }
        val payload = payloadLines.joinToString(separator = "\n").trim()
        val parsedNode =
            if (looksLikeWireGuard(payload)) {
                parseWireGuardConfig(
                    raw = payload,
                    displayName = smartConfigOptionDisplayName(heading),
                    allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                )
            } else {
                parseSingleNode(payload, allowPrivateOutboundHosts, allowInsecureTls)
            }
        entries +=
            SmartConfigEntry(
                heading = heading,
                node =
                    parsedNode.copy(
                        subscriptionExpiresAt =
                            metadataLines.firstNotNullOfOrNull(::subscriptionExpirationFromMetadataComment)
                                ?: parsedNode.subscriptionExpiresAt,
                    ),
            )
    }
    return entries
}

internal fun parseSmartConfigHeading(value: String): SmartConfigHeading? {
    val match = SMART_CONFIG_HEADING_REGEX.matchEntire(value) ?: return null
    return SmartConfigHeading(
        protocolLabel = match.groupValues[1].trim(),
        routeLabel = match.groupValues[2].trim(),
    )
}

internal fun subscriptionExpirationFromMetadataComment(value: String): Long? {
    val expiresAt = SMART_CONFIG_EXPIRE_REGEX.find(value)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
    return if (expiresAt >= 1_000_000_000_000L) {
        expiresAt
    } else {
        expiresAt * 1000L
    }
}

internal fun smartConfigOptionId(
    entry: SmartConfigEntry,
    index: Int,
): String {
    val base = "${entry.node.protocolHint.name.lowercase()}_${slugifySmartConfigKey(entry.heading.routeLabel)}"
    return if (index == 0 || base.isNotBlank()) {
        base.ifBlank { "option_${index + 1}" }
    } else {
        "option_${index + 1}"
    }
}

internal fun smartConfigOptionDisplayName(entry: SmartConfigEntry): String =
    "${protocolDisplayLabel(entry.node.protocolHint)} · ${normalizeSmartConfigRouteLabel(entry.heading.routeLabel)}"

internal fun smartConfigOptionDisplayName(heading: SmartConfigHeading): String =
    "${heading.protocolLabel.trim().uppercase()} · ${normalizeSmartConfigRouteLabel(heading.routeLabel)}"

internal fun normalizeSmartConfigRouteLabel(value: String): String =
    when (value.trim().lowercase()) {
        "direct" -> "Direct"
        "tor+i2p" -> "Tor + I2P"
        else ->
            value
                .trim()
                .replace("+", " + ")
                .replace(Regex("\\s+"), " ")
        }

internal fun resolveSmartConfigDisplayName(
    entries: List<SmartConfigEntry>,
    fallbackName: String?,
): String {
    val derivedProviderName = detectSmartConfigProviderName(entries)
    return when {
        derivedProviderName != null -> "$derivedProviderName Smart Config"
        !fallbackName.isNullOrBlank() -> "$fallbackName Smart Config"
        else -> "Smart Config"
    }
}

internal fun resolveSmartConfigProfileDisplayName(
    entries: List<SmartConfigEntry>,
    fallbackName: String?,
): String {
    require(entries.isNotEmpty()) { "empty smart config route profile" }
    val preferredNodeName =
        entries.firstNotNullOfOrNull { entry ->
            entry.node.displayName.takeIf(String::isNotBlank)
        }
    if (preferredNodeName != null) {
        return preferredNodeName
    }
    val routeLabel = normalizeSmartConfigRouteLabel(entries.first().heading.routeLabel)
    val derivedProviderName = detectSmartConfigProviderName(entries)
    return when {
        derivedProviderName != null -> "$derivedProviderName $routeLabel"
        !fallbackName.isNullOrBlank() -> "$fallbackName $routeLabel"
        else -> routeLabel
    }
}

internal fun detectSmartConfigProviderName(entries: List<SmartConfigEntry>): String? =
    entries.firstNotNullOfOrNull { entry ->
        when {
            entry.node.displayName.contains("foxhole", ignoreCase = true) -> "Foxhole"
            else -> null
        }
    }

internal fun slugifySmartConfigKey(value: String): String =
    value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

internal fun protocolDisplayLabel(protocol: ProtocolHint): String =
    when (protocol) {
        ProtocolHint.VLESS -> "VLESS"
        ProtocolHint.TROJAN -> "TROJAN"
        ProtocolHint.SHADOWSOCKS -> "SHADOWSOCKS"
        ProtocolHint.WIREGUARD -> "WIREGUARD"
        ProtocolHint.HYSTERIA2 -> "HYSTERIA2"
        ProtocolHint.VMESS -> "VMESS"
        ProtocolHint.OUTLINE -> "OUTLINE"
        ProtocolHint.SING_BOX -> "SING-BOX"
        ProtocolHint.UNKNOWN -> "UNKNOWN"
    }

}
