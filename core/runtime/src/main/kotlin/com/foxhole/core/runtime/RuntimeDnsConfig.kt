package com.foxhole.core.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.SecureDnsMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.disableUnverifiedRuleSetRuntimeDns
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

internal fun DnsSettings.bundledAdGuardFilterEnabled(): Boolean =
    filteringEnabled && (blockAds || blockTrackers || blockAppTelemetry || blockMaliciousDomains)

internal fun buildFoxholeDnsConfig(
    strategy: String,
    dnsSettings: DnsSettings = DnsSettings(),
    privateDnsState: PrivateDnsState?,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
    extraServers: List<JsonObject> = emptyList(),
    finalTag: String = DNS_REMOTE_TAG,
    includeRemote: Boolean = true,
    remoteDetourTag: String? = "proxy",
    torSocksDetour: Boolean = false,
    tunnelCarriesUdp: Boolean = true,
): JsonObject =
    buildJsonObject {
        put(
            "servers",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("tag", DNS_LOCAL_TAG)
                        put("type", "local")
                    },
                )
                add(
                    foxholeDirectDnsServer(),
                )
                if (includeRemote) {
                    // The remote resolver rides the tunnel whenever DNS resolves through the VPN;
                    // only an explicit opt-out (both toggles off) drops the detour. The standalone
                    // tor-only runtime always forces the detour so apps can resolve over Tor.
                    val remoteDetour =
                        if (torSocksDetour) {
                            remoteDetourTag
                        } else {
                            remoteDetourTag?.takeIf { dnsSettings.resolvesThroughTunnel() }
                        }
                    add(
                        foxholeRemoteDnsServer(
                            dnsSettings = dnsSettings,
                            privateDnsState = privateDnsState,
                            detourTag = remoteDetour,
                            torSocksDetour = torSocksDetour,
                            tunnelCarriesUdp = tunnelCarriesUdp,
                        ),
                    )
                    // A tunnelled hostname-addressed resolver must resolve that hostname in-tunnel
                    // too: add the IP-literal bootstrap resolver its domain_resolver points at, so
                    // the bootstrap lookup does not leak on the underlying network.
                    if (remoteDetour != null &&
                        remoteResolverRequiresBootstrap(
                            dnsSettings,
                            privateDnsState,
                            remoteDetour,
                            torSocksDetour,
                            tunnelCarriesUdp,
                        )
                    ) {
                        add(foxholeBootstrapDnsServer(remoteDetour))
                    }
                }
                extraServers.forEach(::add)
            },
        )
        val rules = buildDnsRules(dnsSettings, dnsFilterRuntimePaths)
        if (rules.isNotEmpty()) {
            put("rules", JsonArray(rules))
        }
        put("strategy", strategy)
        put("final", finalTag)
    }

internal fun wireGuardDnsServers(dns: JsonObject?): List<JsonObject> =
    dns
        ?.get("servers")
        ?.jsonArray
        ?.map { it.jsonObject }
        ?.filter { server -> server["tag"]?.jsonPrimitive?.contentOrNull == WIREGUARD_DNS_TAG }
        .orEmpty()

internal data class ManagedDnsRoute(
    val finalTag: String,
    val includeRemote: Boolean,
)

// VPN_PROVIDER (default) and THROUGH_VPN both keep every query inside the tunnel. DIRECT is the
// single explicit opt-out and the only mode that can leak plaintext DNS to the local network;
// the UI gates it behind a confirmation.
enum class DnsRouteMode {
    VPN_PROVIDER,
    THROUGH_VPN,
    DIRECT,
}

internal fun DnsSettings.dnsRouteMode(): DnsRouteMode =
    when {
        useVpnProviderDns -> DnsRouteMode.VPN_PROVIDER
        dnsThroughVpn -> DnsRouteMode.THROUGH_VPN
        else -> DnsRouteMode.DIRECT
    }

internal fun DnsSettings.resolvesThroughTunnel(): Boolean = dnsRouteMode() != DnsRouteMode.DIRECT

// DNS is hijacked only when explicitly asked (intercept toggle, or "replace system DNS"). By
// default an app querying a hard-coded resolver (8.8.8.8:53) has that packet carried through
// the tunnel, not rewritten; system queries still hit the in-tunnel resolver via the TUN DNS
// server, so nothing leaks off-tunnel.
internal fun DnsSettings.shouldInterceptDns(): Boolean = interceptDnsRequests || replaceSystemDns

internal fun managedDnsRoute(
    base: JsonObject,
    dnsSettings: DnsSettings,
    privacyRouteActive: Boolean,
    wireGuardDnsPresent: Boolean = false,
): ManagedDnsRoute {
    if (privacyRouteActive) {
        return ManagedDnsRoute(finalTag = DNS_REMOTE_TAG, includeRemote = true)
    }
    val wireGuardSelected = selectedProxyEndpointType(base).equals("wireguard", ignoreCase = true)
    return when (dnsSettings.dnsRouteMode()) {
        // Prefer the profile's own advertised resolver (a selected WireGuard endpoint pushes
        // one), else the managed remote resolver. Either way DNS stays inside the VPN.
        DnsRouteMode.VPN_PROVIDER ->
            if (wireGuardSelected && wireGuardDnsPresent) {
                ManagedDnsRoute(finalTag = WIREGUARD_DNS_TAG, includeRemote = true)
            } else {
                ManagedDnsRoute(finalTag = DNS_REMOTE_TAG, includeRemote = true)
            }
        DnsRouteMode.THROUGH_VPN ->
            ManagedDnsRoute(finalTag = DNS_REMOTE_TAG, includeRemote = true)
        // Explicit opt-out: resolve on the underlying network. A protected (DoH/DoT) resolver still
        // resolves off-tunnel but stays encrypted; plain mode falls back to the system resolver.
        DnsRouteMode.DIRECT ->
            if (dnsSettings.secureMode == SecureDnsMode.PLAIN) {
                ManagedDnsRoute(finalTag = DNS_DIRECT_TAG, includeRemote = dnsSettings.filteringEnabled)
            } else {
                ManagedDnsRoute(finalTag = DNS_REMOTE_TAG, includeRemote = true)
            }
    }
}

internal fun selectedProxyEndpointType(base: JsonObject): String? {
    val selectedTag = selectedProxyTag(base) ?: return null
    return base["endpoints"]
        ?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { endpoint -> endpoint["tag"]?.jsonPrimitive?.contentOrNull == selectedTag }
        ?.get("type")
        ?.jsonPrimitive
        ?.contentOrNull
}

internal fun selectedProxyTag(base: JsonObject): String? {
    val selector =
        base["outbounds"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { outbound ->
                outbound["type"]?.jsonPrimitive?.contentOrNull == "selector" &&
                    outbound["tag"]?.jsonPrimitive?.contentOrNull == "proxy"
            }
            ?: return null
    return selector["default"]?.jsonPrimitive?.contentOrNull
        ?: selector["outbounds"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
}

internal fun foxholeDirectDnsServer(): JsonObject =
    buildJsonObject {
        put("tag", DNS_DIRECT_TAG)
        put("type", "local")
    }

// Deliberately NO per-app DNS rule for the VPN split. The sing-box-era assembler emitted one,
// but FoxCore terminates DNS in ONE interceptor with ONE upstream lane (foxcore-tun/src/dns.rs):
// the query is answered before any per-app decision exists, and the translator refuses the shape
// (POLICY_UNREPRESENTABLE) — so EVERY config with a split was rejected before the tun was built
// and the profile could not connect at all (Pixel: the include-split leg of
// LiveApplicationSplitAndroidTest never reached CONNECTED). Stated cost: the split separates an
// app's TRAFFIC, not its DNS — buildVpnSplitRouteRules remains the whole of the split.

internal fun DnsSettings.localGuardDnsSettings(forcePublicDoH: Boolean): DnsSettings =
    if (forcePublicDoH && secureMode == SecureDnsMode.PLAIN) {
        copy(
            server = FOXHOLE_REMOTE_DNS_SERVER,
            secureMode = SecureDnsMode.DOH,
            dnsThroughVpn = false,
        )
    } else {
        copy(dnsThroughVpn = false)
    }

@Suppress("ComplexCondition")
internal fun DnsSettings.torDetourSafeDnsSettings(
    detourTag: String?,
    torSocksDetour: Boolean = false,
    tunnelCarriesUdp: Boolean = true,
): DnsSettings =
    // A plain UDP DNS query cannot survive an outbound that carries no UDP (ERR_NAME_NOT_RESOLVED
    // for every app): upgrade the remote resolver to DoH, which rides any TCP outbound. Applies to
    // the in-VPN Tor outbound, tor-only Tor SOCKS, AND a TCP-only proxy detour; a UDP-capable
    // tunnel keeps the user's plain choice.
    if (secureMode == SecureDnsMode.PLAIN &&
        detourTag != null &&
        (detourTag == TOR_OVER_VPN_OUTBOUND_TAG || torSocksDetour || !tunnelCarriesUdp)
    ) {
        copy(
            server = FOXHOLE_REMOTE_DNS_SERVER,
            secureMode = SecureDnsMode.DOH,
        )
    } else {
        this
    }

internal fun DnsSettings.localGuardVerifiedRuleSetSettings(
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): DnsSettings =
    if (filteringEnabled && dnsFilterRuntimePaths == null) {
        disableUnverifiedRuleSetRuntimeDns()
    } else {
        this
    }

internal fun Settings.verifiedRuleSetRuntimeSettings(
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): Settings =
    if (dns.filteringEnabled && dnsFilterRuntimePaths == null) {
        copy(dns = dns.disableUnverifiedRuleSetRuntimeDns())
    } else {
        this
    }

internal fun foxholeRemoteDnsServer(
    dnsSettings: DnsSettings,
    privateDnsState: PrivateDnsState?,
    detourTag: String?,
    torSocksDetour: Boolean = false,
    tunnelCarriesUdp: Boolean = true,
): JsonObject {
    val strictPrivateDnsHostname =
        privateDnsState
            ?.takeIf { state -> state.mode == PrivateDnsMode.STRICT }
            ?.hostname
            ?.takeIf(String::isNotBlank)
    // A tunnelled resolver bootstraps via the in-tunnel IP-literal resolver so its A/AAAA lookup
    // is not leaked in cleartext; off-tunnel resolvers keep dns-direct.
    val bootstrapResolverTag = if (detourTag != null) DNS_BOOTSTRAP_TAG else DNS_DIRECT_TAG
    return buildJsonObject {
        put("tag", DNS_REMOTE_TAG)
        if (strictPrivateDnsHostname != null) {
            put("server", strictPrivateDnsHostname)
            put("type", SecureDnsMode.DOT.configType)
            put("server_port", SecureDnsMode.DOT.defaultPort)
            put("domain_resolver", bootstrapResolverTag)
        } else {
            val effectiveDnsSettings =
                dnsSettings.torDetourSafeDnsSettings(detourTag, torSocksDetour, tunnelCarriesUdp)
            put("server", effectiveDnsSettings.server)
            put("type", effectiveDnsSettings.secureMode.configType)
            put("server_port", effectiveDnsSettings.secureMode.defaultPort)
            if (effectiveDnsSettings.secureMode == SecureDnsMode.DOH) {
                put("path", "/dns-query")
            }
            if (effectiveDnsSettings.server.requiresDnsDomainResolver()) {
                put("domain_resolver", bootstrapResolverTag)
            }
        }
        detourTag?.let { put("detour", it) }
    }
}

// IP-literal DoH resolver pinned to the tunnel detour; only the domain_resolver bootstrap for a
// tunnelled hostname-addressed upstream, keeping that lookup encrypted and in-tunnel.
internal fun foxholeBootstrapDnsServer(detourTag: String): JsonObject =
    buildJsonObject {
        put("tag", DNS_BOOTSTRAP_TAG)
        put("server", FOXHOLE_REMOTE_DNS_SERVER)
        put("type", SecureDnsMode.DOH.configType)
        put("server_port", SecureDnsMode.DOH.defaultPort)
        put("path", "/dns-query")
        put("detour", detourTag)
    }

// True when the tunnelled remote resolver is hostname-addressed and needs the bootstrap resolver.
internal fun remoteResolverRequiresBootstrap(
    dnsSettings: DnsSettings,
    privateDnsState: PrivateDnsState?,
    detourTag: String,
    torSocksDetour: Boolean,
    tunnelCarriesUdp: Boolean,
): Boolean {
    val strictHost =
        privateDnsState
            ?.takeIf { state -> state.mode == PrivateDnsMode.STRICT }
            ?.hostname
            ?.takeIf(String::isNotBlank)
    val resolverServer =
        strictHost
            ?: dnsSettings.torDetourSafeDnsSettings(detourTag, torSocksDetour, tunnelCarriesUdp).server
    return resolverServer.requiresDnsDomainResolver()
}

internal fun buildDnsRules(
    dnsSettings: DnsSettings,
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): List<JsonObject> =
    buildList {
        if (!dnsSettings.filteringEnabled) {
            return@buildList
        }
        // A bypass only means something with a filter attached: FoxCore's schema has no shape for
        // it outside a filtering config, so the translator refuses the whole document
        // (POLICY_UNREPRESENTABLE on $.dns.rules) before the TUN exists and the profile cannot
        // connect. Reachable from settings: filtering on, all categories off, one bypass entry.
        if (!dnsSettings.bundledAdGuardFilterEnabled() || dnsFilterRuntimePaths == null) {
            return@buildList
        }
        val bypassPackages = normalizedRuntimePackages(dnsSettings.appBypassPackages)
        if (bypassPackages.isNotEmpty()) {
            add(
                buildJsonObject {
                    putJsonArray("package_name") {
                        bypassPackages.forEach { add(JsonPrimitive(it)) }
                    }
                    put("action", "route")
                    put("server", DNS_REMOTE_TAG)
                },
            )
        }
        val bypassDomains =
            dnsSettings.domainBypassRules
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        if (bypassDomains.isNotEmpty()) {
            add(
                buildJsonObject {
                    putJsonArray("domain_suffix") {
                        bypassDomains.forEach { add(JsonPrimitive(it)) }
                    }
                    put("action", "route")
                    put("server", DNS_REMOTE_TAG)
                },
            )
        }
        val adGuardVpnCompatibilityDomains =
            dnsFilterRuntimePaths
                .adGuardVpnCompatibilityDomains
                .filter(String::isNotBlank)
        if (adGuardVpnCompatibilityDomains.isNotEmpty()) {
            add(
                buildJsonObject {
                    putJsonArray("domain_suffix") {
                        adGuardVpnCompatibilityDomains.forEach { add(JsonPrimitive(it)) }
                    }
                    put("action", "route")
                    put("server", DNS_REMOTE_TAG)
                },
            )
        }
        run {
            val categoryRuleSets = dnsSettings.activeDnsFilterCategoryRuleSets(dnsFilterRuntimePaths)
            if (categoryRuleSets.isNotEmpty()) {
                // One rule per category, in severity order: first match wins, so an overlapping
                // domain logs the most severe category's tag.
                categoryRuleSets.forEach { (tag, _) ->
                    add(
                        buildJsonObject {
                            putJsonArray("rule_set") {
                                add(JsonPrimitive(tag))
                            }
                            put("action", "predefined")
                            put("rcode", "NXDOMAIN")
                        },
                    )
                }
            } else {
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") {
                            add(JsonPrimitive(DNS_ADGUARD_RULE_SET_TAG))
                        }
                        put("action", "predefined")
                        put("rcode", "NXDOMAIN")
                    },
                )
            }
        }
    }

internal fun String.requiresDnsDomainResolver(): Boolean {
    val host = trim().removeSurrounding("[", "]")
    if (host.isBlank()) {
        return false
    }
    return !host.isIpv4Literal() && !host.isIpv6Literal()
}

internal fun String.isIpv4Literal(): Boolean {
    val parts = split('.')
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { value -> value in 0..255 } == true
        }
}

internal fun String.isIpv6Literal(): Boolean =
    count { it == ':' } >= 2 &&
        all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' || char == ':' || char == '.' }
