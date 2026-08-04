
package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class FoxCorePolicyTranslation(
    val dns: JsonObject,
    val routes: JsonArray,
    val traffic: JsonObject,
    val document: JsonObject,
    /**
     * The resolver a packet-tunnel profile declares, for Android's TUN builder. Non-null only when
     * the primary outbound is a packet tunnel: every other shape lets the engine intercept DNS and
     * takes the advertised address out of [dns] instead.
     */
    val packetTunnelDnsAdvertise: String? = null,
)

@Suppress("LargeClass")
internal object FoxCorePolicyTranslator {
    fun translate(
        root: JsonObject,
        primaryIsPacketTunnel: Boolean,
        overlays: Set<FoxCoreOverlay>,
        expectedRevision: Long?,
        dnsAdvertiseOverride: String? = null,
        dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
    ): FoxCorePolicyTranslation {
        if (primaryIsPacketTunnel && overlays.isNotEmpty()) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_UNAVAILABLE,
                "$.outbounds",
            )
        }
        val routePlan = translateRoutes(root, overlays)
        val dns =
            translateDns(
                root = root,
                primaryIsPacketTunnel = primaryIsPacketTunnel,
                overlays = overlays,
                advertiseOverride = dnsAdvertiseOverride,
                dnsRuleSetBootstrap = dnsRuleSetBootstrap,
            )
        val traffic =
            buildJsonObject {
                put("default_action", routePlan.defaultAction)
                if (routePlan.applications.isNotEmpty()) {
                    put(
                        "applications",
                        buildJsonArray {
                            routePlan.applications.forEach { (packageName, action) ->
                                add(
                                    buildJsonObject {
                                        put("package", packageName)
                                        put("action", action)
                                    },
                                )
                            }
                        },
                    )
                }
                // Explicit false is deliberate: the old config cannot arm a private network that
                // was not successfully migrated into the immutable outbound registry.
                put("tor_enabled", FoxCoreOverlay.TOR in overlays)
                put("i2p_enabled", FoxCoreOverlay.I2P in overlays)
                put("kill_switch", false)
                put("quarantine_new_apps", false)
            }
        val document =
            buildJsonObject {
                expectedRevision?.let { put("expected_revision", it) }
                put("dns", dns.config)
                if (routePlan.routes.isNotEmpty()) {
                    put("routes", routePlan.routes)
                }
                put("traffic", traffic)
            }
        return FoxCorePolicyTranslation(
            dns = dns.config,
            routes = routePlan.routes,
            traffic = traffic,
            document = document,
            packetTunnelDnsAdvertise = dns.packetTunnelAdvertise,
        )
    }

    private fun translateRoutes(
        root: JsonObject,
        overlays: Set<FoxCoreOverlay>,
    ): RouteTranslation {
        val source =
            root["route"]
                ?.asFoxCoreObject("$.route")
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.route")
        source.requireOnlyKeys(ROUTE_KEYS, "$.route")
        source.optionalBoolean("auto_detect_interface", "$.route")?.let { enabled ->
            if (!enabled) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                    "$.route.auto_detect_interface",
                )
            }
        }
        source.optionalBoolean("override_android_vpn", "$.route")?.let { enabled ->
            if (enabled) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                    "$.route.override_android_vpn",
                )
            }
        }
        source.optionalString("default_domain_resolver", "$.route")?.let { resolver ->
            if (resolver !in setOf(DNS_DIRECT_TAG, DNS_REMOTE_TAG)) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                    "$.route.default_domain_resolver",
                )
            }
        }
        if (source["rule_set"]?.asFoxCoreArray("$.route.rule_set")?.isNotEmpty() == true) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                "$.route.rule_set",
            )
        }
        val finalAction = routeTargetAction(source.optionalString("final", "$.route") ?: "proxy", "$.route.final")
        if (finalAction == RouteTargetAction.Tor && FoxCoreOverlay.TOR !in overlays) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, "$.route.final")
        }
        if (finalAction == RouteTargetAction.I2p && FoxCoreOverlay.I2P !in overlays) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, "$.route.final")
        }
        val mutable =
            MutableRouteTranslation(
                defaultAction = finalAction.trafficAction,
            )
        source["rules"]?.asFoxCoreArray("$.route.rules")?.forEachIndexed { index, element ->
            translateRouteRule(
                source = element.asFoxCoreObject("$.route.rules[$index]"),
                path = "$.route.rules[$index]",
                overlays = overlays,
                target = mutable,
            )
        }
        return RouteTranslation(
            defaultAction = mutable.defaultAction,
            applications = mutable.applications.toMap(),
            routes = JsonArray(mutable.routes),
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun translateRouteRule(
        source: JsonObject,
        path: String,
        overlays: Set<FoxCoreOverlay>,
        target: MutableRouteTranslation,
    ) {
        source.requireOnlyKeys(ROUTE_RULE_KEYS, path)
        when (source.requiredString("action", path)) {
            "sniff" -> {
                if (source.keys != setOf("action")) {
                    rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
                }
                return
            }
            "hijack-dns" -> {
                if (!isManagedDnsHijack(source)) {
                    rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
                }
                return
            }
            "route", "reject" -> Unit
            else -> rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.action")
        }
        if (source["inbound"] != null) {
            if (!isManagedRuntimeLoopbackRule(source)) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
            }
            return
        }
        if (source["type"] != null || source["rules"] != null || source["rule_set"] != null) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
        if (source["protocol"] != null ||
            source["domain_keyword"] != null ||
            source["domain_regex"] != null
        ) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
        if (source.optionalBoolean("invert", path) == true) {
            translateInvertedApplicationRule(source, path, target)
            return
        }
        val action =
            if (source.requiredString("action", path) == "reject") {
                RouteTargetAction.Block
            } else {
                routeTargetAction(source.requiredString("outbound", path), "$path.outbound")
            }
        if (action == RouteTargetAction.I2p && FoxCoreOverlay.I2P !in overlays) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, path)
        }
        if (action == RouteTargetAction.Tor && FoxCoreOverlay.TOR !in overlays) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, path)
        }
        val packages = source.stringOrArrayValues("package_name", path)
        if (action == RouteTargetAction.I2p && packages.isNotEmpty()) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
        val hasNonPackageMatchers =
            source.keys.any {
                it in setOf(
                    "domain",
                    "domain_suffix",
                    "ip_cidr",
                    "port",
                    "port_range",
                    "network",
                    "ip_is_private",
                )
            }
        if (packages.isNotEmpty() && !hasNonPackageMatchers) {
            packages.forEach { packageName ->
                target.addApplication(packageName, action.trafficAction, path)
            }
            return
        }
        if (packages.size > 1) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.package_name")
        }
        if (source.optionalBoolean("no_drop", path) == false) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.no_drop")
        }
        source.optionalString("method", path)?.let { method ->
            if (method != "default") {
                rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.method")
            }
        }
        target.routes +=
            buildRouteRule(
                source = source,
                path = path,
                packageName = packages.singleOrNull(),
                action = action,
            )
    }

    private fun translateInvertedApplicationRule(
        source: JsonObject,
        path: String,
        target: MutableRouteTranslation,
    ) {
        val allowedKeys = setOf("package_name", "invert", "action", "outbound")
        if (!source.keys.all { it in allowedKeys } ||
            source.requiredString("action", path) != "route" ||
            source.requiredString("outbound", path) != "direct"
        ) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
        // A default that already moved means a second inverted rule (or a non-VPN final): two
        // "everything else goes direct" statements cannot both be the default.
        if (target.defaultAction != "vpn") {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
        val included = source.stringOrArrayValues("package_name", path)
        if (included.isEmpty()) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.package_name")
        }
        target.defaultAction = "direct"
        // Packages an EARLIER rule already classified keep that classification — first match wins,
        // exactly as in the rule list this is translated from. This is not a detail: the assembler
        // puts the firewall's blocked packages INSIDE the include set (so the block rule can see
        // them at all) and emits their reject rule first, so demanding an empty application map
        // here — or re-adding them as "vpn" through the conflict-checking [addApplication] — made
        // "include split + firewall" an unrepresentable config. It was refused before the tun was
        // built, and the profile simply would not connect while any app was blocked.
        included.forEach { packageName -> target.retainApplication(packageName, "vpn", path) }
    }

    private fun buildRouteRule(
        source: JsonObject,
        path: String,
        packageName: String?,
        action: RouteTargetAction,
    ): JsonObject =
        buildJsonObject {
            packageName?.let { put("package", it) }
            val exactDomains = source.stringOrArrayValues("domain", path)
            if (exactDomains.isNotEmpty()) {
                put("exact_domains", JsonArray(exactDomains.map(::JsonPrimitive)))
            }
            val suffixDomains = source.stringOrArrayValues("domain_suffix", path)
            if (suffixDomains.isNotEmpty()) {
                put("domain_suffixes", JsonArray(suffixDomains.map(::JsonPrimitive)))
            }
            val cidrs =
                buildList {
                    addAll(source.stringOrArrayValues("ip_cidr", path))
                    if (source.optionalBoolean("ip_is_private", path) == true) {
                        addAll(PRIVATE_NETWORK_CIDRS)
                    }
                }.distinct()
            if (cidrs.isNotEmpty()) {
                put("cidrs", JsonArray(cidrs.map(::JsonPrimitive)))
            }
            val ports = translatePorts(source, path)
            if (ports.isNotEmpty()) {
                put("ports", JsonArray(ports))
            }
            source.singleNetworkValue(path)?.let { put("transport", it) }
            put("action", action.routeAction)
        }.also { translated ->
            if (translated.keys == setOf("action")) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
            }
        }

    private fun translatePorts(
        source: JsonObject,
        path: String,
    ): List<JsonObject> =
        buildList {
            source.intOrArrayValues("port", path).forEach { port ->
                if (port !in 1..65_535) {
                    rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.port")
                }
                add(portRange(port, port))
            }
            source.stringOrArrayValues("port_range", path).forEach { range ->
                val parts = range.split('-', limit = 2)
                val start = parts.firstOrNull()?.toIntOrNull()
                val end = parts.getOrNull(1)?.toIntOrNull()
                if (start == null || end == null) {
                    rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.port_range")
                }
                if (start !in 1..65_535 || end !in start..65_535) {
                    rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.port_range")
                }
                add(portRange(start, end))
            }
        }

    private fun portRange(
        start: Int,
        end: Int,
    ): JsonObject =
        buildJsonObject {
            put("start", start)
            put("end", end)
        }

    @Suppress("CyclomaticComplexMethod")
    private fun translateDns(
        root: JsonObject,
        primaryIsPacketTunnel: Boolean,
        overlays: Set<FoxCoreOverlay>,
        advertiseOverride: String?,
        dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap?,
    ): DnsTranslation {
        val source =
            root["dns"]
                ?.asFoxCoreObject("$.dns")
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.dns")
        source.requireOnlyKeys(DNS_KEYS, "$.dns")
        source.optionalString("strategy", "$.dns")?.let { strategy ->
            if (strategy !in DNS_STRATEGIES) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.dns.strategy")
            }
        }
        val servers =
            source["servers"]
                ?.asFoxCoreArray("$.dns.servers")
                ?.mapIndexed { index, element ->
                    element.asFoxCoreObject("$.dns.servers[$index]")
                }
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.dns.servers")
        if (primaryIsPacketTunnel) {
            // Deliberately ahead of the server and rule validation below. On this shape the engine
            // is handed no upstream and no rule set, so nothing in `$.dns` reaches it except the one
            // address lifted out here — and a resolver or filter the user configured for the other
            // profiles must not decide whether this one starts.
            return packetTunnelDns(servers)
        }
        validateDnsServers(servers, overlays)
        val filter = translateDnsRules(source, overlays)
        if (filter.enabled && dnsRuleSetBootstrap == null) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                "$.dns.rules",
            )
        }
        if (!filter.enabled &&
            (filter.allowSuffixes.isNotEmpty() || filter.bypassPackages.isNotEmpty())
        ) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                "$.dns.rules",
            )
        }
        val finalTag = source.requiredString("final", "$.dns")
        val selectedIndexes =
            servers.mapIndexedNotNull { index, server ->
                index.takeIf { server.optionalString("tag", "$.dns.servers[$index]") == finalTag }
            }
        if (selectedIndexes.size != 1) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.dns.final")
        }
        val selectedIndex = selectedIndexes.single()
        val selected = servers[selectedIndex]
        val selectedPath = "$.dns.servers[$selectedIndex]"
        val upstream = translateDnsUpstream(selected, selectedPath, servers, overlays)
        val mode =
            when {
                overlays.isNotEmpty() -> "fake_ip"
                else -> "real_ip"
            }
        val config =
            buildJsonObject {
                put("advertise", advertiseOverride ?: upstream.advertise)
                put("mode", mode)
                put("upstreams", buildJsonArray { add(upstream.config) })
                put("route", upstream.route)
                if (filter.enabled) {
                    val bootstrap = requireNotNull(dnsRuleSetBootstrap)
                    put(
                        "rule_sets",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", bootstrap.name)
                                    put("public_key", bootstrap.publicKeyBase64)
                                    put("minimum_sequence", bootstrap.minimumSequence)
                                    put("required", true)
                                },
                            )
                        },
                    )
                    if (filter.allowSuffixes.isNotEmpty() || filter.bypassPackages.isNotEmpty()) {
                        put(
                            "blocklist",
                            buildJsonObject {
                                if (filter.allowSuffixes.isNotEmpty()) {
                                    put(
                                        "allow_suffixes",
                                        buildJsonArray {
                                            filter.allowSuffixes.forEach { domain ->
                                                add(JsonPrimitive(domain))
                                            }
                                        },
                                    )
                                }
                                if (filter.bypassPackages.isNotEmpty()) {
                                    put(
                                        "bypass_packages",
                                        buildJsonArray {
                                            filter.bypassPackages.forEach { packageName ->
                                                add(JsonPrimitive(packageName))
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        return DnsTranslation(config = config, packetTunnelAdvertise = null)
    }

    /**
     * DNS for a WireGuard/AmneziaWG primary: not intercepted at all.
     *
     * The engine refuses `dns.route='primary'` on an L3 packet tunnel, and rightly so — the tunnel
     * carries IP packets and offers no stream outbound to resolve through, so every intercepted
     * lookup would have left beside the tunnel in the clear. Before the engine grew that refusal a
     * WireGuard profile connected and quietly leaked its DNS; afterwards it stopped starting at all.
     * Neither is the behaviour of a WireGuard client, which simply declares the resolver from the
     * config to the system and lets the queries ride the tunnel as ordinary packets. So this returns
     * a document the engine's `intercepts()` reads as false — no `advertise`, no `upstreams`,
     * `real_ip` — and hands the declared resolver back for the TUN instead.
     *
     * The price, accepted knowingly: the DNS filter and fake-IP do not work on these profiles.
     * Nothing intercepts the queries, so there is nothing to filter them with.
     */
    private fun packetTunnelDns(servers: List<JsonObject>): DnsTranslation {
        val resolver =
            packetTunnelResolver(servers)
                ?: rejectFoxCoreConfig(
                    FoxCoreConfigRejection.PACKET_TUNNEL_DNS_MISSING,
                    "$.dns.servers",
                    "this profile is an L3 packet tunnel and carries no resolver of its own; " +
                        "queries are not intercepted on this kind of profile, so there is nothing " +
                        "to advertise and nothing that would resolve them inside the tunnel. Add a " +
                        "DNS address to the profile.",
                )
        return DnsTranslation(
            config = buildJsonObject { put("mode", "real_ip") },
            packetTunnelAdvertise = resolver,
        )
    }

    /**
     * The address a packet-tunnel profile declares as its resolver.
     *
     * Matched on the managed tag the importer writes for a WireGuard `DNS =` line rather than on
     * `detour`: the managed remote resolver carries `detour: "proxy"` too, and it is a public
     * address this app chose, not one the profile carries. Advertising that on the TUN would be the
     * invented resolver the refusal above exists to avoid.
     *
     * Only an IP literal can be advertised — VpnService.Builder.addDnsServer takes a numeric
     * address — so a hostname resolver reads as "no resolver" and is refused rather than dropped.
     */
    private fun packetTunnelResolver(servers: List<JsonObject>): String? {
        servers.forEachIndexed { index, server ->
            val path = "$.dns.servers[$index]"
            if (server.optionalString("tag", path) == WIREGUARD_DNS_TAG) {
                return server.optionalString("server", path)?.takeIf(String::isFoxCoreIpLiteral)
            }
        }
        return null
    }

    @Suppress("CyclomaticComplexMethod")
    private fun translateDnsUpstream(
        source: JsonObject,
        path: String,
        allServers: List<JsonObject>,
        overlays: Set<FoxCoreOverlay>,
    ): DnsUpstreamTranslation {
        source.requireOnlyKeys(DNS_SERVER_KEYS, path)
        val type = source.requiredString("type", path).lowercase()
        if (type in setOf("local", "fakeip")) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
        val host = source.requiredString("server", path)
        val port = source.optionalInt("server_port", path) ?: defaultDnsPort(type, "$path.type")
        if (port !in 1..65_535) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.server_port")
        }
        val serverIp =
            host.takeIf(String::isFoxCoreIpLiteral)
                ?: source.optionalString("server_ip", path)?.takeIf(String::isFoxCoreIpLiteral)
                ?: bootstrapServerIp(source, path, allServers)
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        val route =
            when (source.optionalString("detour", path)) {
                null -> "direct"
                "proxy" -> "primary"
                TOR_OVER_VPN_OUTBOUND_TAG, "tor" ->
                    if (FoxCoreOverlay.TOR in overlays) {
                        "tor"
                    } else {
                        rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, "$path.detour")
                    }
                else -> rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.detour")
            }
        val config =
            when (type) {
                "udp", "tcp" ->
                    buildJsonObject {
                        put("type", type)
                        put("address", socketAddress(host, port))
                    }
                "tls" ->
                    buildJsonObject {
                        put("type", "dot")
                        put("host", host)
                        put("port", port)
                        if (host != serverIp) {
                            put("server_ip", serverIp)
                        }
                    }
                "https" ->
                    buildJsonObject {
                        put("type", "doh")
                        put(
                            "url",
                            "https://${urlHost(host)}:$port" +
                                (source.optionalString("path", path)?.takeIf(String::isNotBlank) ?: "/dns-query"),
                        )
                        if (host != serverIp) {
                            put("server_ip", serverIp)
                        }
                    }
                else -> rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.type")
            }
        return DnsUpstreamTranslation(
            config = config,
            advertise = serverIp,
            route = route,
        )
    }

    private fun validateDnsServers(
        servers: List<JsonObject>,
        overlays: Set<FoxCoreOverlay>,
    ) {
        val tags = mutableSetOf<String>()
        servers.forEachIndexed { index, server ->
            val path = "$.dns.servers[$index]"
            server.requireOnlyKeys(DNS_ALL_SERVER_KEYS, path)
            val tag = server.requiredString("tag", path)
            if (!tags.add(tag)) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.tag")
            }
            when (server.requiredString("type", path).lowercase()) {
                "local" -> {
                    if (server.keys != setOf("tag", "type") ||
                        tag !in setOf(DNS_LOCAL_TAG, DNS_DIRECT_TAG)
                    ) {
                        rejectFoxCoreConfig(
                            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                            path,
                        )
                    }
                }
                "fakeip" -> {
                    if (!isSupportedLegacyFakeIpServer(server, tag, overlays, path)) {
                        rejectFoxCoreConfig(
                            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                            path,
                        )
                    }
                }
                "udp", "tcp", "tls", "https" -> {
                    server.requireOnlyKeys(DNS_SERVER_KEYS, path)
                    translateDnsUpstream(server, path, servers, overlays)
                }
                else ->
                    rejectFoxCoreConfig(
                        FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                        "$path.type",
                    )
            }
        }
    }

    private fun isSupportedLegacyFakeIpServer(
        server: JsonObject,
        tag: String,
        overlays: Set<FoxCoreOverlay>,
        path: String,
    ): Boolean {
        if (overlays.isEmpty() || tag != I2P_FAKEIP_DNS_TAG) {
            return false
        }
        if (server.keys.any { it !in DNS_FAKE_IP_SERVER_KEYS }) {
            return false
        }
        if (server.requiredString("inet4_range", path) != I2P_FAKEIP_INET4_RANGE) {
            return false
        }
        return server["inet6_range"] == null
    }

    private fun bootstrapServerIp(
        selected: JsonObject,
        path: String,
        allServers: List<JsonObject>,
    ): String? {
        val resolverTag = selected.optionalString("domain_resolver", path) ?: return null
        val candidate =
            allServers.singleOrNull { server ->
                server["tag"]?.jsonPrimitive?.contentOrNull == resolverTag
            } ?: return null
        return candidate.optionalString("server", "$.dns.servers")
            ?.takeIf(String::isFoxCoreIpLiteral)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun translateDnsRules(
        source: JsonObject,
        overlays: Set<FoxCoreOverlay>,
    ): DnsFilterTranslation {
        var filterEnabled = false
        val allowSuffixes = linkedSetOf<String>()
        val bypassPackages = linkedSetOf<String>()
        source["rules"]?.asFoxCoreArray("$.dns.rules")?.forEachIndexed { index, element ->
            val rule = element.asFoxCoreObject("$.dns.rules[$index]")
            val path = "$.dns.rules[$index]"
            val i2pRule =
                FoxCoreOverlay.I2P in overlays &&
                    rule.keys.all { it in setOf("domain_suffix", "server") } &&
                    rule.stringOrArrayValues("domain_suffix", path) == listOf(I2P_DOMAIN_SUFFIX) &&
                    rule.optionalString("server", path) == I2P_FAKEIP_DNS_TAG
            val torRule =
                FoxCoreOverlay.TOR in overlays &&
                    rule.keys.all { it in setOf("domain_suffix", "server") } &&
                    rule.stringOrArrayValues("domain_suffix", path) == listOf(TOR_DOMAIN_SUFFIX) &&
                    rule.optionalString("server", path) == I2P_FAKEIP_DNS_TAG
            if (i2pRule || torRule) {
                return@forEachIndexed
            }
            val ruleSetBlock =
                rule["rule_set"] != null &&
                    rule.keys.all { it in setOf("rule_set", "action", "rcode") } &&
                    rule.stringOrArrayValues("rule_set", path).isNotEmpty() &&
                    rule.optionalString("action", path) == "predefined" &&
                    rule.optionalString("rcode", path) == "NXDOMAIN"
            if (ruleSetBlock) {
                filterEnabled = true
                return@forEachIndexed
            }
            val bypass =
                rule.keys.all {
                    it in setOf("package_name", "domain_suffix", "action", "server")
                } &&
                    rule.optionalString("action", path) == "route" &&
                    rule.optionalString("server", path) == DNS_REMOTE_TAG
            if (bypass) {
                val packages = rule.stringOrArrayValues("package_name", path)
                val domains = rule.stringOrArrayValues("domain_suffix", path)
                if (packages.isNotEmpty() || domains.isNotEmpty()) {
                    bypassPackages += packages
                    allowSuffixes += domains
                    return@forEachIndexed
                }
            }
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                path,
            )
        }
        return DnsFilterTranslation(
            enabled = filterEnabled,
            allowSuffixes = allowSuffixes.toList(),
            bypassPackages = bypassPackages.toList(),
        )
    }

    private fun routeTargetAction(
        source: String,
        path: String,
    ): RouteTargetAction =
        when (source) {
            "proxy" -> RouteTargetAction.Vpn
            "direct" -> RouteTargetAction.Direct
            "block" -> RouteTargetAction.Block
            I2P_OUTBOUND_TAG -> RouteTargetAction.I2p
            TOR_OVER_VPN_OUTBOUND_TAG, "tor" -> RouteTargetAction.Tor
            else -> rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
}
