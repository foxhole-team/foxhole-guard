package com.foxhole.core.importer

import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.SubscriptionEntryReport
import com.foxhole.core.model.SubscriptionEntryStatus
import com.foxhole.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json

// The smart-config leg of the import hierarchy: FoxHole smart-config documents are parsed,
// grouped by profile and labeled here. Split from ProfileImportEngine.kt (same pattern as the
// Node/Xray/Core supports below it).
internal open class ProfileImportSmartConfigSupport(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) : ProfileImportXraySupport(json, remoteHostResolver) {
    internal fun parseSmartConfigImport(
        raw: String,
        fallbackName: String?,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): SmartConfigImport? {
        val document =
            parseSmartConfigDocument(
                raw = raw,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
            ) ?: return null
        val entries = document.entries
        if (entries.isEmpty()) {
            return null
        }
        val protocolOptions =
            entries.mapIndexed { index, entry ->
                StoredProfileProtocolOption(
                    id = smartConfigOptionId(entry, index),
                    displayName = smartConfigOptionDisplayName(entry),
                    protocolHint = entry.node.protocolHint,
                    normalizedConfigJson = buildConfigFromNodes(
                        listOf(entry.node),
                        allowPrivateOutboundHosts = allowPrivateOutboundHosts
                    ),
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
            entryReports = document.reports,
        )
    }

    internal fun parseSmartConfigSubscriptionImport(
        raw: String,
        fallbackName: String?,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ParsedSubscriptionImport? {
        val document =
            parseSmartConfigDocument(
                raw = raw,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
            ) ?: return null
        val entries = document.entries
        if (entries.isEmpty()) {
            return null
        }
        val profiles =
            groupSmartConfigEntriesByProfile(entries).map { routeEntries ->
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
            entryReports = document.reports,
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
            deduplicateSmartConfigEntries(entries).map { entry ->
                val baseId = entry.node.protocolHint.name.lowercase()
                val count = (usedIds[baseId] ?: 0) + 1
                usedIds[baseId] = count
                StoredProfileProtocolOption(
                    id = if (count == 1) baseId else "${baseId}_$count",
                    displayName = protocolDisplayLabel(entry.node.protocolHint),
                    protocolHint = entry.node.protocolHint,
                    normalizedConfigJson = buildConfigFromNodes(
                        listOf(entry.node),
                        allowPrivateOutboundHosts = allowPrivateOutboundHosts
                    ),
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

    // Within one route group, the same server offered as both plain ss:// and Outline ss://...?outline=1
    // is one protocol option, not two. Keyed on the tag-stripped outbound; the plain representation wins.
    internal fun deduplicateSmartConfigEntries(entries: List<SmartConfigEntry>): List<SmartConfigEntry> {
        val chosen = LinkedHashMap<kotlinx.serialization.json.JsonObject, SmartConfigEntry>()
        entries.forEach { entry ->
            val key = nodeIdentityBody(entry.node)
            val existing = chosen[key]
            when {
                existing == null -> chosen[key] = entry
                existing.node.protocolHint == ProtocolHint.OUTLINE && entry.node.protocolHint != ProtocolHint.OUTLINE ->
                    chosen[key] = entry
            }
        }
        return chosen.values.toList()
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    internal fun parseSmartConfigEntries(
        raw: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): List<SmartConfigEntry> =
        parseSmartConfigDocument(
            raw = raw,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )?.entries.orEmpty()

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun parseSmartConfigDocument(
        raw: String,
        allowPrivateOutboundHosts: Boolean,
        allowInsecureTls: Boolean,
    ): ParsedSmartConfigDocument? {
        val lines = raw.lineSequence().map(String::trimEnd).toList()
        val normalizedLines = lines.map(::normalizeInput)
        if (normalizedLines.none { parseSmartConfigHeading(it) != null }) {
            return null
        }
        val entries = mutableListOf<SmartConfigEntry>()
        val reports = mutableListOf<SubscriptionEntryReport>()
        var index = 0
        while (index < normalizedLines.size) {
            val trimmed = normalizedLines[index]
            if (trimmed.isBlank()) {
                index += 1
                continue
            }
            val heading = parseSmartConfigHeading(trimmed) ?: return null
            val headingLine = index + 1
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
            val declaredProtocol = smartConfigDeclaredProtocol(heading.protocolLabel)
            if (declaredProtocol == null) {
                reports +=
                    SubscriptionEntryReport(
                        protocolLabel = safeSmartConfigProtocolLabel(heading.protocolLabel),
                        protocolHint = ProtocolHint.UNKNOWN,
                        status = SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
                        sourceLine = headingLine,
                        reason = "unsupported protocol",
                    )
                continue
            }
            require(payloadLines.isNotEmpty()) {
                "invalid supported smart config entry at line $headingLine: missing payload"
            }
            val payload = payloadLines.joinToString(separator = "\n").trim()
            val parsedNode =
                if (declaredProtocol == ProtocolHint.WIREGUARD) {
                    require(looksLikeWireGuard(payload)) {
                        "invalid supported smart config entry at line $headingLine: malformed WireGuard config"
                    }
                    parseWireGuardConfig(
                        raw = payload,
                        displayName = smartConfigOptionDisplayName(heading),
                        allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                    )
                } else {
                    parseSingleNode(payload, allowPrivateOutboundHosts, allowInsecureTls)
                }
            require(parsedNode.protocolHint == declaredProtocol) {
                "invalid supported smart config entry at line $headingLine: " +
                    "declared ${heading.protocolLabel}, payload is ${protocolDisplayLabel(parsedNode.protocolHint)}"
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
                    profileGroupKey = metadataLines.firstNotNullOfOrNull(::profileGroupKeyFromMetadataComment),
                )
            reports +=
                SubscriptionEntryReport(
                    protocolLabel = protocolDisplayLabel(parsedNode.protocolHint),
                    protocolHint = parsedNode.protocolHint,
                    status = SubscriptionEntryStatus.ACCEPTED,
                    sourceLine = headingLine,
                )
        }
        return ParsedSmartConfigDocument(entries = entries, reports = reports)
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

    internal fun profileGroupKeyFromMetadataComment(value: String): String? =
        SMART_CONFIG_PROFILE_ID_REGEX
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    internal fun groupSmartConfigEntriesByProfile(entries: List<SmartConfigEntry>): List<List<SmartConfigEntry>> {
        val routeGroups = linkedMapOf<String, MutableList<SmartConfigEntry>>()
        entries.forEach { entry ->
            val routeKey = slugifySmartConfigKey(entry.heading.routeLabel).ifBlank { "default" }
            routeGroups.getOrPut(routeKey) { mutableListOf() }.add(entry)
        }
        val profileGroups = linkedMapOf<String, MutableList<SmartConfigEntry>>()
        routeGroups.forEach { (routeKey, routeEntries) ->
            val metadataGroupKeys =
                routeEntries
                    .mapNotNull { entry ->
                        entry.profileGroupKey
                            ?.let(::slugifySmartConfigKey)
                            ?.takeIf(String::isNotBlank)
                    }
                    .distinct()
            val splitByProfileKey = metadataGroupKeys.size > 1
            val splitByDisplayName = !splitByProfileKey && shouldSplitSmartConfigRouteByDisplayName(routeEntries)
            routeEntries.forEach { entry ->
                val groupKey =
                    when {
                        splitByProfileKey ->
                            entry.profileGroupKey
                                ?.let(::slugifySmartConfigKey)
                                ?.takeIf(String::isNotBlank)
                                ?: routeKey
                        splitByDisplayName ->
                            entry.node.displayName
                                .let(::slugifySmartConfigKey)
                                .takeIf(String::isNotBlank)
                                ?: routeKey
                        else -> routeKey
                    }
                profileGroups.getOrPut("$routeKey:$groupKey") { mutableListOf() }.add(entry)
            }
        }
        return profileGroups.values.toList()
    }

    private fun shouldSplitSmartConfigRouteByDisplayName(entries: List<SmartConfigEntry>): Boolean {
        val hasDuplicateProtocol =
            entries
                .groupingBy { entry -> entry.node.protocolHint }
                .eachCount()
                .values
                .any { count -> count > 1 }
        if (!hasDuplicateProtocol) {
            return false
        }
        val displayNames = entries.map { entry -> entry.node.displayName.trim() }
        return displayNames.all(String::isNotBlank) &&
            displayNames.map { name -> name.lowercase() }.distinct().size > 1
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
            "tor+i2p" -> "TOR + I2P"
            else ->
                value
                    .trim()
                    .replace("+", " + ")
                    .replace(whitespaceRunsRegex, " ")
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
            .replace(smartConfigSlugSanitizeRegex, "_")
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
            ProtocolHint.NAIVE -> "NAIVE"
            ProtocolHint.TUIC -> "TUIC"
            ProtocolHint.ANYTLS -> "ANYTLS"
            ProtocolHint.TOR -> "TOR"
            ProtocolHint.LOCAL_GUARD -> "LOCAL-GUARD"
            ProtocolHint.CUSTOM_CONFIG -> "CUSTOM-CONFIG"
            ProtocolHint.UNKNOWN -> "UNKNOWN"
        }

    private fun smartConfigDeclaredProtocol(value: String): ProtocolHint? =
        when (value.lowercase().filter(Char::isLetterOrDigit)) {
            "vless" -> ProtocolHint.VLESS
            "trojan" -> ProtocolHint.TROJAN
            "shadowsocks", "shadowsocks2022", "ss" -> ProtocolHint.SHADOWSOCKS
            "wireguard", "wg" -> ProtocolHint.WIREGUARD
            "hysteria2", "hy2" -> ProtocolHint.HYSTERIA2
            "vmess" -> ProtocolHint.VMESS
            "outline" -> ProtocolHint.OUTLINE
            "naive", "naivehttps" -> ProtocolHint.NAIVE
            "tuic" -> ProtocolHint.TUIC
            "anytls" -> ProtocolHint.ANYTLS
            else -> null
        }

    private fun safeSmartConfigProtocolLabel(value: String): String =
        value
            .take(MAX_SMART_CONFIG_PROTOCOL_LABEL_LENGTH)
            .filter { character -> character.isLetterOrDigit() || character in "+-_. " }
            .trim()
            .uppercase()
            .ifBlank { "UNKNOWN" }

    private data class ParsedSmartConfigDocument(
        val entries: List<SmartConfigEntry>,
        val reports: List<SubscriptionEntryReport>,
    )

    private companion object {
        const val MAX_SMART_CONFIG_PROTOCOL_LABEL_LENGTH = 32
    }
}

private val whitespaceRunsRegex = Regex("\\s+")
private val smartConfigSlugSanitizeRegex = Regex("[^a-z0-9]+")
