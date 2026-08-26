package com.foxhole.core.runtime

import com.foxhole.core.model.AppliedTorRoute
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
    protocolTestTrafficFreeze: Boolean = false,
    selfPackageName: String? = null,
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
            ?.flatMap { rule ->
                toRouteRules(
                    rule = rule,
                    siteRoutingAction = expert.siteRoutingAction,
                    torOutboundTag = TOR_OVER_VPN_OUTBOUND_TAG.takeIf { privacyRouteActive },
                )
            }.orEmpty()
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
    val torFailClosedRules =
        buildFailClosedBlockRules(
            settings = settings,
            torLaneCarried = privacyRouteActive,
            vpnLaneCarried = true,
        )
    val combinedRules =
        buildJsonArray {
            if (protocolTestTrafficFreeze) {
                add(
                    packageRouteRule(
                        packageNames = listOf(requireNotNull(selfPackageName)),
                        action = RoutingRuleAction.PROXY,
                    ),
                )
            }
            appRules.forEach(::add)
            torFailClosedRules.forEach(::add)
            add(runtimeProxyRouteRule(runtimeProxyOutboundTag(privacyRouteActive, splitPlan)))
            if (blockUnsupportedUdp) {
                hijackDnsRules().forEach(::add)
                if (expert.bypassLan) {
                    add(bypassLanRule())
                }
                unsupportedUdpRejectRules().forEach(::add)
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
            if (protocolTestTrafficFreeze) {
                "block"
            } else if (splitPlan.torAllApps) {
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
    privacyRoute.permitted &&
        privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
        traffic.mode == TrafficMode.TUNNEL &&
        (privacyRoute.bypassVpnTunnel || vpnProtocolHint?.isUdpTransport() != true) &&
        when (privacyRoute.scope) {
            PrivacyRouteScope.ALL_APPS -> true
            PrivacyRouteScope.SELECTED_APPS -> expert.torLanePackages().isNotEmpty()
        }

fun Settings.appliedTorRouteOrNull(vpnProtocolHint: ProtocolHint?): AppliedTorRoute? {
    if (!isTorPrivacyRouteActive(vpnProtocolHint)) {
        return null
    }
    return AppliedTorRoute(
        scope = privacyRoute.scope,
        bypassVpnTunnel = privacyRoute.bypassVpnTunnel,
        selectedPackages =
        if (privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
            expert.torLanePackages()
        } else {
            emptyList()
        },
    )
}

fun Settings.torAllAppsCollidesWithVpnIncludeSplit(): Boolean =
    privacyRoute.permitted &&
        privacyRoute.enabled &&
        privacyRoute.scope == PrivacyRouteScope.ALL_APPS &&
        traffic.mode == TrafficMode.TUNNEL &&
        expert.vpnIncludedPackages().isNotEmpty()

const val TOR_ALL_APPS_NEEDS_FULL_TUNNEL_MARKER = "tor_all_apps_needs_full_tunnel"

internal fun requireBuildableTorScope(
    privacyRouteActive: Boolean,
    splitPlan: RuntimeSplitPlan,
) {
    if (privacyRouteActive && splitPlan.torAllApps && splitPlan.vpnMode == VpnAppSelectionMode.INCLUDE_ONLY) {
        throw RuntimeConfigUnsupportedException(TOR_ALL_APPS_NEEDS_FULL_TUNNEL_MARKER)
    }
}

class RuntimeConfigUnsupportedException(
    val marker: String,
) : IllegalStateException(marker)

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
            ?.flatMap { rule ->
                toRouteRules(
                    rule = rule,
                    siteRoutingAction = expert.siteRoutingAction,
                    torOutboundTag = "proxy",
                )
            }.orEmpty()
    val combinedRules =
        buildJsonArray {
            buildAppRouteRules(expert).forEach(::add)
            buildFailClosedBlockRules(
                settings = settings,
                torLaneCarried = true,
                vpnLaneCarried = settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS,
            ).forEach(::add)
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
        splitPlan =
        buildSplitPlan(
            settings,
            privacyRouteActive = settings.privacyRoute.permitted && settings.privacyRoute.enabled,
        ),
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
        splitPlan.torAllApps ->
            if (udpPolicy == PrivacyRouteUdpPolicy.BLOCK) {
                nonDnsUdpRouteRules(outboundTag = udpOutbound)
            } else {
                listOf(udpRouteRule(udpOutbound))
            }

        splitPlan.torTcpPackages.isNotEmpty() ->
            splitPlan.torUdpBlockedPackages.distinct().sorted().flatMap { packageName ->
                if (udpPolicy == PrivacyRouteUdpPolicy.BLOCK) {
                    nonDnsUdpRouteRules(outboundTag = udpOutbound, packageName = packageName)
                } else {
                    listOf(
                        packageNetworkRouteRule(
                            listOf(packageName),
                            network = "udp",
                            outboundTag = udpOutbound,
                        ),
                    )
                }
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
        TOR_OVER_VPN_OUTBOUND_TAG
    } else {
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

private fun nonDnsUdpRouteRules(
    outboundTag: String,
    packageName: String? = null,
): List<JsonObject> =
    NON_DNS_UDP_PORT_RANGES.map { portRange ->
        buildJsonObject {
            packageName?.let { put("package_name", it) }
            put("network", "udp")
            put("port_range", portRange)
            put("action", "route")
            put("outbound", outboundTag)
        }
    }

internal fun unsupportedUdpRejectRules(): List<JsonObject> =
    NON_DNS_UDP_PORT_RANGES.map { portRange ->
        buildJsonObject {
            put("network", "udp")
            put("port_range", portRange)
            put("action", "reject")
            put("method", "default")
            put("no_drop", true)
        }
    }

private val NON_DNS_UDP_PORT_RANGES = listOf("1-52", "54-65535")

internal fun shouldBlockUnsupportedUdp(
    base: JsonObject,
    vpnProtocolHint: ProtocolHint?,
): Boolean {
    val primaryOutbound = base.primaryProxyOutbound()
    val inherentlyTcpOnly = primaryOutbound?.stringField("type") == "naive"
    val explicitlyTcpOnly = primaryOutbound?.isTcpOnlyNetwork() == true
    return inherentlyTcpOnly || (vpnProtocolHint?.isUdpTransport() != true && explicitlyTcpOnly)
}

internal fun shouldUseStreamSafeManagedDns(
    base: JsonObject,
    vpnProtocolHint: ProtocolHint?,
): Boolean =
    vpnProtocolHint == ProtocolHint.SHADOWSOCKS ||
        shouldBlockUnsupportedUdp(base, vpnProtocolHint)

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
            ?.flatMap { rule ->
                toRouteRules(
                    rule = rule,
                    siteRoutingAction = expert.siteRoutingAction,
                    torOutboundTag = null,
                )
            }.orEmpty()
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
            dnsFilterRuntimePaths?.foxCoreBootstrap != null -> emptyList()
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

internal fun buildFailClosedBlockRules(
    settings: Settings,
    torLaneCarried: Boolean,
    vpnLaneCarried: Boolean,
): List<JsonObject> {
    if (!settings.privacyRoute.blockAppsWhenTorUnavailable) {
        return emptyList()
    }
    val blockedPackages =
        settings.expert.failClosedBlockPackages(
            torLaneCarried = torLaneCarried,
            vpnLaneCarried = vpnLaneCarried,
        )
    if (blockedPackages.isEmpty()) {
        return emptyList()
    }
    return listOf(
        buildJsonObject {
            putJsonArray("package_name") {
                blockedPackages.forEach { add(JsonPrimitive(it)) }
            }
            put("action", "reject")
            put("method", "default")
        },
    )
}

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

internal fun toRouteRules(
    rule: RoutingRule,
    siteRoutingAction: RoutingRuleAction,
    torOutboundTag: String?,
): List<JsonObject> {
    val action = rule.runtimeAction(siteRoutingAction)
    if (action != RoutingRuleAction.TOR) {
        return listOf(toRouteRule(rule, action.outboundTag))
    }
    val blockRule = toRouteRule(rule, RoutingRuleAction.BLOCK.outboundTag)
    val torTag = torOutboundTag ?: return listOf(blockRule)
    val networks = rule.matchNetworks.map(String::lowercase).ifEmpty { TCP_UDP_NETWORKS }
    return buildList {
        if (ROUTE_NETWORK_TCP in networks) add(toRouteRule(rule, torTag, forcedNetwork = ROUTE_NETWORK_TCP))
        if (ROUTE_NETWORK_UDP in networks) {
            add(toRouteRule(rule, RoutingRuleAction.BLOCK.outboundTag, forcedNetwork = ROUTE_NETWORK_UDP))
        }
    }.ifEmpty { listOf(blockRule) }
}

@Suppress("CyclomaticComplexMethod")
private fun toRouteRule(
    rule: RoutingRule,
    outboundTag: String,
    forcedNetwork: String? = null,
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
        val networks = forcedNetwork?.let(::listOf) ?: rule.matchNetworks
        if (networks.isNotEmpty()) {
            putJsonArray("network") {
                networks.forEach { add(JsonPrimitive(it)) }
            }
        }
        put("action", "route")
        put("outbound", outboundTag)
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

internal fun dnsFilterSniffRejectRule(
    dnsSettings: DnsSettings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): JsonObject? {
    if (!dnsSettings.bundledAdGuardFilterEnabled() || dnsFilterRuntimePaths == null) {
        return null
    }

    if (dnsFilterRuntimePaths.foxCoreBootstrap != null) {
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
