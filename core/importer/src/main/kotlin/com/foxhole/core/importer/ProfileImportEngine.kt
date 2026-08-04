package com.foxhole.core.importer

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class ProfileImportEngine(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportSmartConfigSupport(json, remoteHostResolver) {
    fun parseUserInput(
        input: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ParsedImport =
        parseUserInputWithStrategy(
            input = input,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        ).parsed

    internal fun parseUserInputWithStrategy(
        input: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ProfileImportStrategyResult {
        val trimmed = normalizeInput(input)
        require(trimmed.isNotBlank()) { "empty input" }
        val context =
            UserInputStrategyContext(
                input = trimmed,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
            )
        userInputStrategies.forEach { strategy ->
            strategy.tryParse(context)?.let { parsed ->
                return ProfileImportStrategyResult(strategyId = strategy.id, parsed = parsed)
            }
        }
        throw IllegalArgumentException("unsupported import format")
    }

    private val userInputStrategies: List<UserInputImportStrategy> =
        listOf(
            SubscriptionUrlStrategy(),
            SmartConfigStrategy(),
            WireGuardTextStrategy(),
            RawXrayJsonStrategy(),
            Hysteria2YamlStrategy(),
            EmbeddedShareUriStrategy(),
            DirectNodeLinesStrategy(),
        )

    private inner class SubscriptionUrlStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.SUBSCRIPTION_URL

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? {
            val candidate = extractSubscriptionUrlCandidate(context.input) ?: return null
            val url =
                candidate.ensurePublicUrl(
                    allowHttp = false,
                    resolveHost = true,
                    resolver = remoteHostResolver,
                )
            require(url.isHttps) { "only https subscriptions are allowed" }
            return ParsedImport(
                sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                protocolHint = ProtocolHint.UNKNOWN,
                displayName = url.host.ifBlank { "subscription" },
                normalizedConfigJson = null,
                sourceUrl = url.toString(),
            )
        }
    }

    private inner class SmartConfigStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.SMART_CONFIG

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? =
            parseSmartConfigImport(
                raw = context.input,
                fallbackName = null,
                allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                allowInsecureTls = context.allowInsecureTls,
            )?.let { parsed ->
                ParsedImport(
                    sourceType = ProfileSourceType.SHARE_URI,
                    protocolHint = parsed.protocolHint,
                    displayName = parsed.displayName,
                    normalizedConfigJson = parsed.normalizedConfigJson,
                    nodesCount = parsed.nodesCount,
                    subscriptionExpiresAt = parsed.subscriptionExpiresAt,
                    protocolOptions = parsed.protocolOptions,
                    selectedProtocolOptionId = parsed.selectedProtocolOptionId,
                    entryReports = parsed.entryReports,
                )
            }
    }

    private inner class WireGuardTextStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.WIREGUARD_TEXT

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? {
            if (!looksLikeWireGuard(context.input)) {
                return null
            }
            return ParsedImport(
                sourceType = ProfileSourceType.RAW_WIREGUARD_TEXT,
                protocolHint = ProtocolHint.WIREGUARD,
                displayName = "wireguard",
                normalizedConfigJson =
                buildConfigFromNodes(
                    listOf(parseWireGuardConfig(context.input, "wireguard", context.allowPrivateOutboundHosts)),
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                ),
            )
        }
    }

    private inner class RawXrayJsonStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.RAW_XRAY_JSON

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? {
            if (!looksLikeJson(context.input)) {
                return null
            }
            val objectValue = json.parseToJsonElement(context.input).jsonObject
            if (!looksLikeXrayJson(objectValue)) {
                return null
            }
            val converted =
                normalizeRawXrayConfig(
                    objectValue = objectValue,
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                    allowInsecureTls = context.allowInsecureTls,
                )
            return ParsedImport(
                sourceType = ProfileSourceType.RAW_CONFIG_JSON,
                protocolHint = converted.protocolHint,
                displayName = converted.displayName,
                normalizedConfigJson = converted.normalizedConfigJson,
                subscriptionExpiresAt = converted.subscriptionExpiresAt,
            )
        }
    }

    @Suppress("Indentation")
    private inner class Hysteria2YamlStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.DIRECT_NODE_LINES

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? {
            val node =
                parseHysteria2YamlClientConfig(
                    raw = context.input,
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                    allowInsecureTls = context.allowInsecureTls,
                ) ?: return null
            return ParsedImport(
                sourceType = ProfileSourceType.SHARE_URI,
                protocolHint = node.protocolHint,
                displayName = node.displayName,
                normalizedConfigJson =
                buildConfigFromNodes(
                    listOf(node),
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                ),
                nodesCount = 1,
                subscriptionExpiresAt = node.subscriptionExpiresAt,
            )
        }
    }

    @Suppress("Indentation", "ReturnCount")
    private inner class EmbeddedShareUriStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.DIRECT_NODE_LINES

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? {
            val candidate = extractSingleShareUriCandidate(context.input) ?: return null
            if (context.input.startsWith(candidate, ignoreCase = true)) {
                return null
            }
            val node = parseSingleNode(candidate, context.allowPrivateOutboundHosts, context.allowInsecureTls)
            return ParsedImport(
                sourceType = ProfileSourceType.SHARE_URI,
                protocolHint = node.protocolHint,
                displayName = node.displayName,
                normalizedConfigJson =
                buildConfigFromNodes(
                    listOf(node),
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                ),
                nodesCount = 1,
                subscriptionExpiresAt = node.subscriptionExpiresAt,
            )
        }
    }

    private inner class DirectNodeLinesStrategy : UserInputImportStrategy {
        override val id = ProfileImportStrategyId.DIRECT_NODE_LINES

        override fun tryParse(context: UserInputStrategyContext): ParsedImport? {
            val directNodes =
                parseNodeLines(
                    raw = context.input,
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                    allowInsecureTls = context.allowInsecureTls,
                )
            val nodes = deduplicateNodeIdentities(directNodes.nodes)
            if (nodes.isEmpty()) {
                return null
            }
            return ParsedImport(
                sourceType = ProfileSourceType.SHARE_URI,
                protocolHint = nodes.first().protocolHint,
                displayName = nodes.first().displayName,
                normalizedConfigJson =
                buildConfigFromNodes(
                    nodes,
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                ),
                nodesCount = nodes.size,
                subscriptionExpiresAt = nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
                entryReports = directNodes.reports,
            )
        }
    }

    internal fun parseSubscriptionContentWithStrategy(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ProfileSubscriptionContentStrategyResult {
        val trimmed = rawContent.trim()
        require(trimmed.isNotBlank()) { "subscription is empty" }
        val context =
            SubscriptionContentStrategyContext(
                input = trimmed,
                fallbackName = fallbackName,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
            )
        subscriptionContentStrategies.forEach { strategy ->
            strategy.tryParse(context)?.let { parsed ->
                return ProfileSubscriptionContentStrategyResult(strategyId = strategy.id, parsed = parsed)
            }
        }
        throw IllegalArgumentException("unsupported subscription payload")
    }

    private val subscriptionContentStrategies: List<SubscriptionContentImportStrategy> =
        listOf(
            UserInputSubscriptionContentStrategy(),
            SmartConfigPayloadSubscriptionContentStrategy(),
            Base64PayloadSubscriptionContentStrategy(),
            DirectPayloadSubscriptionContentStrategy(),
        )

    private inner class UserInputSubscriptionContentStrategy : SubscriptionContentImportStrategy {
        override val id = ProfileSubscriptionContentStrategyId.USER_INPUT

        override fun tryParse(context: SubscriptionContentStrategyContext): ParsedImport? =
            runCatching {
                parseUserInput(
                    input = context.input,
                    allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                    allowInsecureTls = context.allowInsecureTls,
                )
            }.getOrNull()?.let { parsed ->
                parsed.copy(
                    sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                    displayName = parsed.displayName.ifBlank { context.fallbackName },
                )
            }
    }

    private inner class SmartConfigPayloadSubscriptionContentStrategy : SubscriptionContentImportStrategy {
        override val id = ProfileSubscriptionContentStrategyId.SMART_CONFIG_PAYLOAD

        override fun tryParse(context: SubscriptionContentStrategyContext): ParsedImport? =
            parseSubscriptionPayloadImport(
                raw = context.input,
                fallbackName = context.fallbackName,
                allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                allowInsecureTls = context.allowInsecureTls,
                includeNodeLines = false,
            )
    }

    private inner class Base64PayloadSubscriptionContentStrategy : SubscriptionContentImportStrategy {
        override val id = ProfileSubscriptionContentStrategyId.BASE64_SUBSCRIPTION_PAYLOAD

        override fun tryParse(context: SubscriptionContentStrategyContext): ParsedImport? {
            val decoded = decodeSubscriptionCandidate(context.input) ?: return null
            return parseSubscriptionPayloadImport(
                raw = decoded,
                fallbackName = context.fallbackName,
                allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                allowInsecureTls = context.allowInsecureTls,
            )
        }
    }

    private inner class DirectPayloadSubscriptionContentStrategy : SubscriptionContentImportStrategy {
        override val id = ProfileSubscriptionContentStrategyId.DIRECT_SUBSCRIPTION_PAYLOAD

        override fun tryParse(context: SubscriptionContentStrategyContext): ParsedImport? =
            parseSubscriptionPayloadImport(
                raw = context.input,
                fallbackName = context.fallbackName,
                allowPrivateOutboundHosts = context.allowPrivateOutboundHosts,
                allowInsecureTls = context.allowInsecureTls,
            )
    }

    fun parseSubscriptionProfiles(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
        groupCompatibleSingleServerMultiProtocol: Boolean = false,
    ): ParsedSubscriptionImport {
        val trimmed = normalizeInput(rawContent)
        require(trimmed.isNotBlank()) { "subscription is empty" }

        parseSubscriptionPayloadProfiles(
            raw = trimmed,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
            includeNodeLines = false,
            groupCompatibleSingleServerMultiProtocol = groupCompatibleSingleServerMultiProtocol,
        )?.let { return it }

        decodeSubscriptionCandidate(trimmed)?.let { decoded ->
            parseSubscriptionPayloadProfiles(
                raw = decoded,
                fallbackName = fallbackName,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
                groupCompatibleSingleServerMultiProtocol = groupCompatibleSingleServerMultiProtocol,
            )?.let { return it }
        }

        parseSubscriptionPayloadProfiles(
            raw = trimmed,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
            groupCompatibleSingleServerMultiProtocol = groupCompatibleSingleServerMultiProtocol,
        )?.let { return it }

        runCatching {
            parseUserInput(
                input = trimmed,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
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
                entryReports = parsed.entryReports,
            )
        }

        if (!includeNodeLines) {
            return null
        }
        val parsedLines = parseNodeLines(raw, allowPrivateOutboundHosts, allowInsecureTls)
        val nodes = deduplicateNodeIdentities(parsedLines.nodes)
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
            entryReports = parsedLines.reports,
        )
    }

    @Suppress("ReturnCount")
    private fun parseSubscriptionPayloadProfiles(
        raw: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
        includeNodeLines: Boolean = true,
        groupCompatibleSingleServerMultiProtocol: Boolean = false,
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
        val parsedLines = parseNodeLines(raw, allowPrivateOutboundHosts, allowInsecureTls)
        val nodes = deduplicateNodeIdentities(parsedLines.nodes)
        if (nodes.isEmpty()) {
            return null
        }
        if (groupCompatibleSingleServerMultiProtocol) {
            buildMultiProtocolSubscriptionProfile(
                nodes = nodes,
                fallbackName = fallbackName,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            )?.let { return it.copy(entryReports = parsedLines.reports) }
        }
        return buildSubscriptionProfiles(
            nodes = nodes,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
        ).copy(entryReports = parsedLines.reports)
    }

    private fun buildMultiProtocolSubscriptionProfile(
        nodes: List<ProxyNode>,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean,
    ): ParsedSubscriptionImport? {
        if (nodes.size < 2 || nodes.any { node -> node.protocolHint == ProtocolHint.UNKNOWN }) {
            return null
        }
        // A fetched smart subscription is one selectable profile even when each protocol uses its
        // own endpoint. Requiring one common host split the VLESS/TROJAN/WireGuard/etc. bundle into
        // unrelated profile cards. Keep ordinary same-protocol server lists separate: only a
        // genuinely multi-protocol payload becomes a smart profile.
        if (nodes.map(ProxyNode::protocolHint).distinct().size < 2) {
            return null
        }
        val usedIds = mutableMapOf<String, Int>()
        val protocolOptions =
            nodes.map { node ->
                val baseId = node.protocolHint.name.lowercase()
                val count = (usedIds[baseId] ?: 0) + 1
                usedIds[baseId] = count
                StoredProfileProtocolOption(
                    id = if (count == 1) baseId else "${baseId}_$count",
                    displayName = protocolDisplayLabel(node.protocolHint),
                    protocolHint = node.protocolHint,
                    normalizedConfigJson = buildConfigFromNodes(
                        listOf(node),
                        allowPrivateOutboundHosts = allowPrivateOutboundHosts
                    ),
                )
            }
        val selectedOption = protocolOptions.first()
        val displayName = fallbackName.ifBlank {
            nodes.firstNotNullOfOrNull { node -> node.displayName.takeIf(String::isNotBlank) } ?: "VPN"
        }
        return ParsedSubscriptionImport(
            displayName = displayName,
            profiles =
            listOf(
                ParsedSubscriptionProfile(
                    displayName = displayName,
                    protocolHint = selectedOption.protocolHint,
                    normalizedConfigJson = selectedOption.normalizedConfigJson,
                    subscriptionExpiresAt = nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
                    protocolOptions = protocolOptions,
                    selectedProtocolOptionId = selectedOption.id,
                ),
            ),
            subscriptionExpiresAt = nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    fun sanitizeResolvedConfig(
        raw: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
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
        raw.replace(legacyQuotedPortRegex) { match ->
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

    @Suppress("CyclomaticComplexMethod")
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
            if (value.matches(portRangeRegex)) {
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
                    normalizedConfigJson = buildConfigFromNodes(
                        listOf(node),
                        allowPrivateOutboundHosts = allowPrivateOutboundHosts
                    ),
                    subscriptionExpiresAt = node.subscriptionExpiresAt,
                )
            }
        return ParsedSubscriptionImport(
            displayName = if (profiles.size == 1) profiles.first().displayName else fallbackName,
            profiles = profiles,
            subscriptionExpiresAt = nodes.mapNotNull(ProxyNode::subscriptionExpiresAt).minOrNull(),
        )
    }
}

private val legacyQuotedPortRegex = Regex("""("port"\s*:\s*)"([1-9]\d{0,4})"""")
private val portRangeRegex = Regex("""\d{1,5}-\d{1,5}""")
