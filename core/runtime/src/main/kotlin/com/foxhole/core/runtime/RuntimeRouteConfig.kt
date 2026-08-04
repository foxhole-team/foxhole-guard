package com.foxhole.core.runtime

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteUdpPolicy
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.RoutingRule
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.dnsRuleSetTag
import com.foxhole.core.model.isUdpTransport
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

// Route assembly for RuntimeConfigAssembler: route patching, Tor/proxy/udp/package route
// rules, resolver-for-route, route-rule builders. Behaviour-preserving Phase B extraction;
// json-free transforms, resolved same-package by the assembler orchestration methods.

internal fun JsonObject.primaryProxyOutbound(): JsonObject? {
    val outbounds = this["outbounds"]?.jsonArray.orEmpty().map { it.jsonObject }
    val outboundsByTag = outbounds.mapNotNull { outbound ->
        outbound.stringField("tag")?.let { tag -> tag to outbound }
    }.toMap()
    val selector =
        outboundsByTag["proxy"]?.takeIf { it.stringField("type") == "selector" }
            ?: outbounds.firstOrNull { it.stringField("type") == "selector" }
    val selectedTag =
        selector?.stringField("default")
            ?: selector
                ?.get("outbounds")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.contentOrNull
    return selectedTag?.let(outboundsByTag::get)
        ?: outbounds.firstOrNull { outbound ->
            outbound.stringField("type") !in setOf("selector", "direct", "block", "dns")
        }
}

@Suppress("CyclomaticComplexMethod")
internal fun patchRoute(
    base: JsonObject,
    existing: JsonObject?,
    dns: JsonObject,
    activePreset: RoutingPreset?,
    settings: Settings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    privacyRouteActive: Boolean,
    splitPlan: RuntimeSplitPlan,
    vpnProtocolHint: ProtocolHint?,
): JsonObject {
    val expert = settings.expert
    val source = existing ?: buildJsonObject {}
    val preserveSource = existing != null && !isFoxholeManagedRoute(existing)
    val blockUnsupportedUdp = shouldBlockUnsupportedUdp(base, vpnProtocolHint)
    val baseRules =
        source["rules"]
            ?.jsonArray
            ?.filterNot { element -> isFoxholeManagedRouteRule(element.jsonObject) }
            .orEmpty()
    val presetRules =
        activePreset
            ?.takeIf { it.enabled }
            ?.rules
            ?.filter { it.enabled }
            ?.map { rule -> toRouteRule(rule, expert.siteRoutingAction) }
            .orEmpty()
    val appRules = buildAppRouteRules(expert)
    val privacyRouteRules =
        if (privacyRouteActive) {
            buildTorPrivacyRouteRules(
                splitPlan = splitPlan,
                udpPolicy = settings.privacyRoute.udpPolicy,
            )
        } else {
            emptyList()
        }
    val torFailClosedRules = buildTorFailClosedBlockRules(settings, privacyRouteActive)
    val combinedRules =
        buildJsonArray {
            appRules.forEach(::add)
            torFailClosedRules.forEach(::add)
            add(runtimeProxyRouteRule(runtimeProxyOutboundTag(privacyRouteActive, splitPlan)))
            if (blockUnsupportedUdp) {
                if (settings.dns.shouldInterceptDns()) {
                    hijackDnsRules().forEach(::add)
                }
                if (expert.bypassLan) {
                    add(bypassLanRule())
                }
                add(unsupportedUdpRejectRule())
            }
            if (expert.sniff) {
                add(sniffRule())
                dnsFilterSniffRejectRule(settings.dns, dnsFilterRuntimePaths)?.let(::add)
            }
            if (!blockUnsupportedUdp) {
                if (settings.dns.shouldInterceptDns()) {
                    hijackDnsRules().forEach(::add)
                }
                if (expert.bypassLan) {
                    add(bypassLanRule())
                }
            }
            privacyRouteRules.forEach(::add)
            buildVpnSplitRouteRules(splitPlan).forEach(::add)
            if (activePreset?.enabled == true && activePreset.overrideMode == RoutingPresetOverrideMode.FORCE_LOCAL) {
                presetRules.forEach(::add)
            } else {
                baseRules.forEach(::add)
                presetRules.forEach(::add)
            }
        }
    val ruleSets =
        mergedRouteRuleSets(
            source = source,
            dnsSettings = settings.dns,
            dnsFilterRuntimePaths = dnsFilterRuntimePaths,
        )

    return buildJsonObject {
        if (preserveSource) {
            source.forEach { (key, value) ->
                when (key) {
                    "rules" -> put(key, combinedRules)
                    "rule_set" -> Unit
                    else -> put(key, value)
                }
            }
        }
        put("rules", combinedRules)
        ruleSets?.let { put("rule_set", it) }
        put(
            "final",
            if (splitPlan.torAllApps) {
                TOR_OVER_VPN_OUTBOUND_TAG
            } else {
                tunnelFinalOutbound(source)
            },
        )
        resolverForRoute(dns, source)?.let { put("default_domain_resolver", it) }
        source["auto_detect_interface"]?.let { put("auto_detect_interface", it) } ?: put("auto_detect_interface", true)
    }
}

fun Settings.isTorPrivacyRouteActive(vpnProtocolHint: ProtocolHint?): Boolean =
    privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
        traffic.mode == TrafficMode.TUNNEL &&
        (privacyRoute.bypassVpnTunnel || vpnProtocolHint?.isUdpTransport() != true) &&
        when (privacyRoute.scope) {
            PrivacyRouteScope.ALL_APPS -> true
            PrivacyRouteScope.SELECTED_APPS -> expert.torLanePackages().isNotEmpty()
        }

internal fun patchTorOnlyRoute(
    dns: JsonObject,
    activePreset: RoutingPreset?,
    settings: Settings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): JsonObject {
    val expert = settings.expert
    val presetRules =
        activePreset
            ?.takeIf { it.enabled }
            ?.rules
            ?.filter { it.enabled }
            ?.map { rule -> toRouteRule(rule, expert.siteRoutingAction) }
            .orEmpty()
    val combinedRules =
        buildJsonArray {
            buildAppRouteRules(expert).forEach(::add)
            add(runtimeProxyRouteRule("proxy"))
            if (expert.sniff) {
                add(sniffRule())
                dnsFilterSniffRejectRule(settings.dns, dnsFilterRuntimePaths)?.let(::add)
            }
            if (settings.dns.shouldInterceptDns()) {
                hijackDnsRules().forEach(::add)
            }
            if (expert.bypassLan) {
                add(bypassLanRule())
            }
            buildTorPrivacyRouteRules(
                settings = settings,
                outboundTag = "proxy",
                udpPolicy = PrivacyRouteUdpPolicy.BLOCK,
            ).forEach(::add)
            presetRules.forEach(::add)
        }
    val ruleSets =
        mergedRouteRuleSets(
            source = buildJsonObject {},
            dnsSettings = settings.dns,
            dnsFilterRuntimePaths = dnsFilterRuntimePaths,
        )
    return buildJsonObject {
        put("rules", combinedRules)
        ruleSets?.let { put("rule_set", it) }
        // Selected-apps scope must NOT funnel the rest of the device into Tor: Tor carries only
        // the selected apps' TCP (matched by the rules above); everything else keeps ordinary
        // direct internet. final=proxy here starved every other app (their UDP/DNS cannot ride
        // Tor) and the whole device looked offline. All-apps scope keeps the Tor-final by design.
        put(
            "final",
            if (settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) "proxy" else "direct",
        )
        resolverForRoute(dns, buildJsonObject {})?.let { put("default_domain_resolver", it) }
        put("auto_detect_interface", true)
    }
}

internal fun buildTorPrivacyRouteRules(
    settings: Settings,
    outboundTag: String = TOR_OVER_VPN_OUTBOUND_TAG,
    udpPolicy: PrivacyRouteUdpPolicy = settings.privacyRoute.udpPolicy,
): List<JsonObject> =
    buildTorPrivacyRouteRules(
        splitPlan = buildSplitPlan(settings, privacyRouteActive = settings.privacyRoute.enabled),
        outboundTag = outboundTag,
        udpPolicy = udpPolicy,
    )

internal fun buildTorPrivacyRouteRules(
    splitPlan: RuntimeSplitPlan,
    outboundTag: String = TOR_OVER_VPN_OUTBOUND_TAG,
    udpPolicy: PrivacyRouteUdpPolicy = PrivacyRouteUdpPolicy.VPN,
): List<JsonObject> {
    val udpOutbound =
        when (udpPolicy) {
            PrivacyRouteUdpPolicy.VPN -> "proxy"
            PrivacyRouteUdpPolicy.BLOCK -> "block"
        }
    return when {
        splitPlan.torAllApps -> listOf(udpRouteRule(udpOutbound))
        // One rule per package, not one rule listing them all: a network-qualified rule cannot be
        // expressed as a per-app action, so the translator turns it into a route — and a route may
        // name only a single package. Bundling several Tor apps into one rule made the whole
        // profile unrepresentable ("the profile configuration is invalid", nothing connected), and
        // only ever with more than one app pinned to Tor.
        splitPlan.torTcpPackages.isNotEmpty() ->
            splitPlan.torUdpBlockedPackages.distinct().sorted().map { packageName ->
                packageNetworkRouteRule(
                    listOf(packageName),
                    network = "udp",
                    outboundTag = udpOutbound,
                )
            } +
                splitPlan.torTcpPackages.distinct().sorted().map { packageName ->
                    packageNetworkRouteRule(
                        listOf(packageName),
                        network = "tcp",
                        outboundTag = outboundTag,
                    )
                }
        else -> emptyList()
    }
}

internal fun runtimeProxyOutboundTag(
    privacyRouteActive: Boolean,
    splitPlan: RuntimeSplitPlan,
): String =
    if (privacyRouteActive && splitPlan.torAllApps) {
        // All traffic rides Tor anyway, so the app's own loopback-proxy probes go through Tor too
        // and confirm the real exit (they are held out of the dashboard VPN identity).
        TOR_OVER_VPN_OUTBOUND_TAG
    } else {
        // Selected-apps Tor: only the chosen packages ride Tor. The app's own probes must observe
        // the plain VPN egress — routing them through Tor starved the dashboard network card of
        // the VPN identity and let a VPN-bound fallback probe masquerade as a "Tor exit".
        "proxy"
    }

internal fun runtimeProxyRouteRule(
    outboundTag: String,
    inboundTag: String = RUNTIME_LOOPBACK_PROXY_INBOUND_TAG,
): JsonObject =
    buildJsonObject {
        putJsonArray("inbound") {
            add(JsonPrimitive(inboundTag))
        }
        put("network", "tcp")
        put("action", "route")
        put("outbound", outboundTag)
    }

internal fun udpRouteRule(outboundTag: String): JsonObject =
    buildJsonObject {
        put("network", "udp")
        put("action", "route")
        put("outbound", outboundTag)
    }

internal fun unsupportedUdpRejectRule(): JsonObject =
    buildJsonObject {
        put("network", "udp")
        put("action", "reject")
        put("method", "default")
        put("no_drop", true)
    }

internal fun shouldBlockUnsupportedUdp(
    base: JsonObject,
    vpnProtocolHint: ProtocolHint?,
): Boolean =
    vpnProtocolHint?.isUdpTransport() != true &&
        base.primaryProxyOutbound()?.isTcpOnlyNetwork() == true

internal fun packageNetworkRouteRule(
    packageNames: List<String>,
    network: String,
    outboundTag: String,
): JsonObject =
    buildJsonObject {
        putJsonArray("package_name") {
            packageNames.distinct().sorted().forEach { add(JsonPrimitive(it)) }
        }
        put("network", network)
        put("action", "route")
        put("outbound", outboundTag)
    }

@Suppress("CyclomaticComplexMethod")
internal fun patchProxyRoute(
    existing: JsonObject?,
    dns: JsonObject,
    activePreset: RoutingPreset?,
    settings: Settings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    runtimeProxyRouteInboundTag: String? = RUNTIME_LOOPBACK_PROXY_INBOUND_TAG,
): JsonObject {
    val expert = settings.expert
    val source = existing ?: buildJsonObject {}
    val preserveSource = existing != null && !isFoxholeManagedRoute(existing)
    val baseRules =
        source["rules"]
            ?.jsonArray
            ?.filterNot { element -> isFoxholeManagedRouteRule(element.jsonObject) }
            .orEmpty()
    val presetRules =
        activePreset
            ?.takeIf { it.enabled }
            ?.rules
            ?.filter { it.enabled }
            ?.map { rule -> toRouteRule(rule, expert.siteRoutingAction) }
            .orEmpty()
    val appRules = buildAppRouteRules(expert)
    val combinedRules =
        buildJsonArray {
            runtimeProxyRouteInboundTag?.let { inboundTag ->
                add(runtimeProxyRouteRule(outboundTag = "proxy", inboundTag = inboundTag))
            }
            appRules.forEach(::add)
            if (activePreset?.enabled == true && activePreset.overrideMode == RoutingPresetOverrideMode.FORCE_LOCAL) {
                presetRules.forEach(::add)
            } else {
                baseRules.forEach(::add)
                presetRules.forEach(::add)
            }
        }
    val ruleSets =
        mergedRouteRuleSets(
            source = source,
            dnsSettings = settings.dns,
            dnsFilterRuntimePaths = dnsFilterRuntimePaths,
        )

    return buildJsonObject {
        if (preserveSource) {
            source.forEach { (key, value) ->
                when (key) {
                    "rules" -> put(key, combinedRules)
                    "rule_set" -> Unit
                    else -> put(key, value)
                }
            }
        }
        put("rules", combinedRules)
        ruleSets?.let { put("rule_set", it) }
        source["final"]?.let { put("final", it) } ?: put("final", "proxy")
        resolverForRoute(dns, source)?.let { put("default_domain_resolver", it) }
        if (!preserveSource) {
            put("auto_detect_interface", true)
        }
    }
}

internal fun mergedRouteRuleSets(
    source: JsonObject,
    dnsSettings: DnsSettings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): JsonArray? {
    val foxholeTags = foxholeDnsRuleSetTags()
    val existing =
        source["rule_set"]
            ?.jsonArray
            ?.filterNot { element -> element.jsonObject["tag"]?.jsonPrimitive?.contentOrNull in foxholeTags }
            .orEmpty()
    val categoryRuleSets =
        dnsSettings.activeDnsFilterCategoryRuleSets(dnsFilterRuntimePaths).map { (tag, path) ->
            buildJsonObject {
                put("type", "local")
                put("tag", tag)
                put("format", "binary")
                put("path", path)
            }
        }
    val foxholeRuleSets =
        when {
            !dnsSettings.bundledAdGuardFilterEnabled() || dnsFilterRuntimePaths == null -> emptyList()
            categoryRuleSets.isNotEmpty() -> categoryRuleSets
            else ->
                listOf(
                    buildJsonObject {
                        put("type", "local")
                        put("tag", DNS_ADGUARD_RULE_SET_TAG)
                        put("format", "binary")
                        put("path", dnsFilterRuntimePaths.adGuardDnsFilterPath)
                    },
                )
        }
    val merged = existing + foxholeRuleSets
    return merged.takeIf(List<JsonElement>::isNotEmpty)?.let(::JsonArray)
}

internal fun foxholeDnsRuleSetTags(): Set<String> =
    buildSet {
        add(DNS_ADGUARD_RULE_SET_TAG)
        DnsFilterCategory.entries.forEach { category -> add(category.dnsRuleSetTag) }
    }

internal fun resolverForRoute(
    dns: JsonObject,
    sourceRoute: JsonObject,
): String? {
    val tags = dnsServerTags(dns)
    if (isFoxholeManagedRoute(sourceRoute) && DNS_DIRECT_TAG in tags) {
        return DNS_DIRECT_TAG
    }
    sourceRoute["default_domain_resolver"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it in tags }
        ?.let { return it }
    return when {
        DNS_DIRECT_TAG in tags -> DNS_DIRECT_TAG
        tags.isNotEmpty() -> tags.first()
        else -> null
    }
}

internal fun dnsServerTags(dns: JsonObject): List<String> =
    dns["servers"]
        ?.jsonArray
        ?.mapNotNull { server -> server.jsonObject["tag"]?.jsonPrimitive?.contentOrNull }
        .orEmpty()

internal fun buildAppRouteRules(expert: ExpertSettings): List<JsonObject> =
    buildList {
        val blocked = expert.blockedLanePackages()
        if (expert.blockedPackagesEnabled && blocked.isNotEmpty()) {
            add(packageRouteRule(blocked, RoutingRuleAction.BLOCK))
        }
    }

/**
 * Fail-closed rule for the Tor lane: when the Tor route is NOT engaged but the user asked to block
 * Tor apps without Tor, the Tor-lane packages are rejected outright instead of leaking to the plain
 * VPN/direct path. Emitted before the split/preset rules so nothing downstream can re-route them.
 */
internal fun buildTorFailClosedBlockRules(
    settings: Settings,
    privacyRouteActive: Boolean,
): List<JsonObject> {
    if (privacyRouteActive || !settings.privacyRoute.blockAppsWhenTorUnavailable) {
        return emptyList()
    }
    val torPackages = settings.expert.torLanePackages()
    if (torPackages.isEmpty()) {
        return emptyList()
    }
    return listOf(
        buildJsonObject {
            putJsonArray("package_name") {
                torPackages.forEach { add(JsonPrimitive(it)) }
            }
            put("action", "reject")
            put("method", "default")
        },
    )
}

// The VPN split expressed in the normalized route plan. INCLUDE keeps only selected apps on the
// proxy path (everything else — including connections with no resolvable owner, matching the old
// Builder-split behaviour where they never entered the tun) goes direct; EXCLUDE sends just the
// selected apps direct. Tor-selected apps are already merged into/subtracted from the plan's
// lists by buildSplitPlan, and the tor rules run before these, so the two never fight.
internal fun buildVpnSplitRouteRules(splitPlan: RuntimeSplitPlan): List<JsonObject> =
    buildList {
        when (splitPlan.vpnMode) {
            VpnAppSelectionMode.INCLUDE_ONLY ->
                add(
                    buildJsonObject {
                        putJsonArray("package_name") {
                            splitPlan.vpnIncludedPackages.distinct().sorted().forEach { add(JsonPrimitive(it)) }
                        }
                        put("invert", true)
                        put("action", "route")
                        put("outbound", "direct")
                    },
                )
            VpnAppSelectionMode.EXCLUDE_SELECTED ->
                add(
                    buildJsonObject {
                        putJsonArray("package_name") {
                            splitPlan.vpnExcludedPackages.distinct().sorted().forEach { add(JsonPrimitive(it)) }
                        }
                        put("action", "route")
                        put("outbound", "direct")
                    },
                )
            VpnAppSelectionMode.FULL_DEVICE -> Unit
        }
    }

internal fun packageRouteRule(
    packageNames: List<String>,
    action: RoutingRuleAction,
): JsonObject =
    buildJsonObject {
        putJsonArray("package_name") {
            packageNames.distinct().sorted().forEach { add(JsonPrimitive(it)) }
        }
        put("action", "route")
        put("outbound", action.outboundTag)
    }

internal fun tunnelFinalOutbound(source: JsonObject): String = source["final"]?.jsonPrimitive?.contentOrNull ?: "proxy"

@Suppress("CyclomaticComplexMethod")
internal fun toRouteRule(
    rule: RoutingRule,
    siteRoutingAction: RoutingRuleAction,
): JsonObject =
    buildJsonObject {
        if (rule.matchDomains.isNotEmpty()) {
            val exact = rule.matchDomains.filterNot {
                it.startsWith("*.") || it.startsWith(".") || it.startsWith(SITE_KEYWORD_PREFIX) || it.startsWith(SITE_REGEX_PREFIX)
            }
            val suffix = rule.matchDomains.filter { it.startsWith("*.") || it.startsWith(".") }.map {
                it.removePrefix(
                    "*."
                ).removePrefix(".")
            }
            val keywords = rule.matchDomains.filter {
                it.startsWith(
                    SITE_KEYWORD_PREFIX
                )
            }.map { it.removePrefix(SITE_KEYWORD_PREFIX) }
            val regexes = rule.matchDomains.filter {
                it.startsWith(
                    SITE_REGEX_PREFIX
                )
            }.map { it.removePrefix(SITE_REGEX_PREFIX) }
            if (exact.isNotEmpty()) {
                putJsonArray("domain") {
                    exact.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (suffix.isNotEmpty()) {
                putJsonArray("domain_suffix") {
                    suffix.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (keywords.isNotEmpty()) {
                putJsonArray("domain_keyword") {
                    keywords.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (regexes.isNotEmpty()) {
                putJsonArray("domain_regex") {
                    regexes.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
        if (rule.matchIpCidrs.isNotEmpty()) {
            putJsonArray("ip_cidr") {
                rule.matchIpCidrs.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (rule.matchPorts.isNotEmpty()) {
            val normalizedPorts = normalizeRoutePorts(rule.matchPorts)
            normalizedPorts.ports.takeIf { it.isNotEmpty() }?.let { ports ->
                if (ports.size == 1) {
                    put("port", ports.first())
                } else {
                    putJsonArray("port") {
                        ports.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
            normalizedPorts.portRanges.takeIf { it.isNotEmpty() }?.let { ranges ->
                if (ranges.size == 1) {
                    put("port_range", ranges.first())
                } else {
                    putJsonArray("port_range") {
                        ranges.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }
        if (rule.matchProtocols.isNotEmpty()) {
            putJsonArray("protocol") {
                rule.matchProtocols.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (rule.matchNetworks.isNotEmpty()) {
            putJsonArray("network") {
                rule.matchNetworks.forEach { add(JsonPrimitive(it)) }
            }
        }
        put("action", "route")
        put("outbound", rule.runtimeAction(siteRoutingAction).outboundTag)
    }

internal fun normalizeRoutePorts(matchPorts: List<String>): NormalizedRoutePort {
    val ports = mutableListOf<Int>()
    val portRanges = mutableListOf<String>()
    matchPorts
        .asSequence()
        .flatMap { token -> token.split(',').asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { token ->
            token.toIntOrNull()?.takeIf { it in 1..65535 }?.let { numericPort ->
                ports += numericPort
                return@forEach
            }
            if (token.matches(PORT_RANGE_REGEX)) {
                val (start, end) = token.split('-', limit = 2).map(String::toInt)
                require(start in 1..65535 && end in 1..65535 && start <= end) {
                    "invalid route port range: $token"
                }
                portRanges += token
                return@forEach
            }
            error("unsupported route port matcher: $token")
        }
    return NormalizedRoutePort(
        ports = ports.distinct(),
        portRanges = portRanges.distinct(),
    )
}

internal fun sniffRule(): JsonObject =
    buildJsonObject {
        put("action", "sniff")
    }

/**
 * Route-level enforcement of the DNS filter on sniffed hostnames. The DNS rules only see queries
 * that pass the in-tunnel resolver; an app pinned to its own resolver (with intercept/replace off)
 * bypasses them entirely. With domain sniffing on, the connection itself still exposes the
 * hostname — this rule rejects it against the same category rule sets, so the filter keeps
 * working in every mode as long as sniffing is enabled. DNS/domain bypass exceptions are honored
 * via inverted sub-rules.
 */
internal fun dnsFilterSniffRejectRule(
    dnsSettings: DnsSettings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): JsonObject? {
    if (!dnsSettings.bundledAdGuardFilterEnabled() || dnsFilterRuntimePaths == null) {
        return null
    }
    val tags =
        dnsSettings
            .activeDnsFilterCategoryRuleSets(dnsFilterRuntimePaths)
            .map { (tag, _) -> tag }
            .ifEmpty { listOf(DNS_ADGUARD_RULE_SET_TAG) }
    val ruleSetMatch =
        buildJsonObject {
            putJsonArray("rule_set") { tags.forEach { add(JsonPrimitive(it)) } }
        }
    val bypassPackages = normalizedRuntimePackages(dnsSettings.appBypassPackages)
    val bypassDomains =
        dnsSettings.domainBypassRules
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    if (bypassPackages.isEmpty() && bypassDomains.isEmpty()) {
        return buildJsonObject {
            ruleSetMatch.forEach { (key, value) -> put(key, value) }
            put("action", "reject")
        }
    }
    return buildJsonObject {
        put("type", "logical")
        put("mode", "and")
        putJsonArray("rules") {
            add(ruleSetMatch)
            if (bypassPackages.isNotEmpty()) {
                add(
                    buildJsonObject {
                        putJsonArray("package_name") { bypassPackages.forEach { add(JsonPrimitive(it)) } }
                        put("invert", true)
                    },
                )
            }
            if (bypassDomains.isNotEmpty()) {
                add(
                    buildJsonObject {
                        putJsonArray("domain_suffix") { bypassDomains.forEach { add(JsonPrimitive(it)) } }
                        put("invert", true)
                    },
                )
            }
        }
        put("action", "reject")
    }
}

internal fun hijackDnsRules(): List<JsonObject> =
    listOf(
        buildJsonObject {
            put("port", 53)
            put("action", "hijack-dns")
        },
        buildJsonObject {
            put("protocol", "dns")
            put("action", "hijack-dns")
        },
    )

internal fun bypassLanRule(): JsonObject =
    buildJsonObject {
        put("ip_is_private", true)
        put("action", "route")
        put("outbound", "direct")
    }
