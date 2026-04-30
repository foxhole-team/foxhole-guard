package com.foxhole.beta.vpn

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.V2RayApiSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
import kotlinx.serialization.json.putJsonObject

class RuntimeConfigAssembler(
    private val json: Json,
    private val lanProxyAddressProvider: LanProxyAddressProvider = DisabledLanProxyAddressProvider,
) {
    @Serializable
    private data class RuntimeFingerprintSettings(
        val traffic: TrafficSettings,
        val expert: ExpertSettings,
    )

    fun assemble(
        baseConfigJson: String,
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode? = null,
    ): String {
        validate(settings.expert)
        val base = json.parseToJsonElement(baseConfigJson).jsonObject
        return when (settings.traffic.mode) {
            TrafficMode.TUNNEL -> assembleTunnel(base, settings, activePreset, privateDnsMode)
            TrafficMode.PROXY -> assembleProxy(base, settings, activePreset)
        }
    }

    private fun assembleTunnel(
        base: JsonObject,
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode?,
    ): String {
        val tunInbound = base["inbounds"]?.jsonArray?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "tun" }?.jsonObject
            ?: error("base config must define a tun inbound")
        val patchedTun = patchTunInbound(tunInbound, settings.traffic, settings.expert)
        val inbounds =
            buildJsonArray {
                add(patchedTun)
                buildLocalSurfaceInbounds(settings.expert.localSurfaces).forEach(::add)
            }
        val patchedDns = patchDns(base["dns"]?.jsonObject, settings.traffic, privateDnsMode)
        val patchedRoute = patchRoute(base["route"]?.jsonObject, patchedDns, activePreset, settings.expert)
        val patchedExperimental = patchExperimental(base["experimental"]?.jsonObject, settings.expert.localSurfaces)

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                base.forEach { (key, value) ->
                    when (key) {
                        "inbounds" -> put(key, inbounds)
                        "dns" -> put(key, patchedDns)
                        "route" -> put(key, patchedRoute)
                        "experimental" -> {
                            if (patchedExperimental.isNotEmpty()) {
                                put(key, patchedExperimental)
                            }
                        }
                        else -> put(key, value)
                    }
                }
                if (!base.containsKey("log")) {
                    putJsonObject("log") {
                        put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                        put("timestamp", true)
                    }
                }
                if (!base.containsKey("experimental") && patchedExperimental.isNotEmpty()) {
                    put("experimental", patchedExperimental)
                }
            },
        )
    }

    private fun assembleProxy(
        base: JsonObject,
        settings: Settings,
        activePreset: RoutingPreset?,
    ): String {
        val localSurfaces = settings.expert.localSurfaces
        require(localSurfaces.socks.enabled || localSurfaces.http.enabled || localSurfaces.mixed.enabled) {
            "proxy mode requires at least one enabled local proxy surface"
        }
        val inbounds =
            buildJsonArray {
                buildLocalSurfaceInbounds(localSurfaces).forEach(::add)
            }
        val patchedDns = patchDns(base["dns"]?.jsonObject, settings.traffic)
        val patchedRoute = patchProxyRoute(base["route"]?.jsonObject, patchedDns, activePreset)
        val patchedExperimental = patchExperimental(base["experimental"]?.jsonObject, localSurfaces)

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                base.forEach { (key, value) ->
                    when (key) {
                        "inbounds" -> put(key, inbounds)
                        "dns" -> put(key, patchedDns)
                        "route" -> put(key, patchedRoute)
                        "experimental" -> {
                            if (patchedExperimental.isNotEmpty()) {
                                put(key, patchedExperimental)
                            }
                        }
                        else -> put(key, value)
                    }
                }
                if (!base.containsKey("log")) {
                    putJsonObject("log") {
                        put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                        put("timestamp", true)
                    }
                }
                if (!base.containsKey("experimental") && patchedExperimental.isNotEmpty()) {
                    put("experimental", patchedExperimental)
                }
            },
        )
    }

    fun runtimeFingerprint(
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode? = null,
    ): Int {
        val settingsFingerprint =
            json.encodeToString(
                RuntimeFingerprintSettings(
                    traffic = settings.traffic,
                    expert = settings.expert.runtimeFingerprintSettings(),
                ),
            ).hashCode()
        val lanFingerprint =
            settings.expert.localSurfaces
                .takeIf { it.allowLanAccess }
                ?.let { lanProxyAddressProvider.currentWifiIpv4Address() }
                ?.hashCode()
                ?: 0
        val dnsFingerprint = privateDnsMode?.hashCode() ?: 0
        return (((settingsFingerprint * 31) + (activePreset?.hashCode() ?: 0)) * 31 + lanFingerprint) * 31 + dnsFingerprint
    }

    fun validate(expert: ExpertSettings) {
        val enabledPorts =
            buildMap<Int, String> {
                addPort(expert.localSurfaces.socks, "SOCKS")
                addPort(expert.localSurfaces.http, "HTTP")
                addPort(expert.localSurfaces.mixed, "Mixed")
                addPort(expert.localSurfaces.clashApi, "Clash API")
                addPort(expert.localSurfaces.v2RayApi, "V2Ray API")
            }
        require(enabledPorts.size == enabledPorts.keys.distinct().size) { "enabled local surfaces must not share the same port" }
        if (expert.perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL) {
            require(expert.selectedPackages.isNotEmpty()) { "select at least one application for per-app routing" }
        }
    }

    private fun MutableMap<Int, String>.addPort(
        surface: ProxyInboundSettings,
        label: String,
    ) {
        if (!surface.enabled) {
            return
        }
        validateLocalHost(surface.host, label)
        val previous = put(surface.port, label)
        require(previous == null) { "$label port conflicts with $previous" }
    }

    private fun MutableMap<Int, String>.addPort(
        surface: ClashApiSettings,
        label: String,
    ) {
        if (!surface.enabled) {
            return
        }
        validateLocalHost(surface.host, label)
        val previous = put(surface.port, label)
        require(previous == null) { "$label port conflicts with $previous" }
    }

    private fun MutableMap<Int, String>.addPort(
        surface: V2RayApiSettings,
        label: String,
    ) {
        if (!surface.enabled) {
            return
        }
        validateLocalHost(surface.host, label)
        val previous = put(surface.port, label)
        require(previous == null) { "$label port conflicts with $previous" }
    }

    private fun validateLocalHost(
        host: String,
        label: String,
    ) {
        val normalized = host.trim().lowercase()
        require(normalized in LOCAL_HOSTS) { "$label host must stay on a local loopback address" }
    }

    private fun ExpertSettings.runtimeFingerprintSettings(): ExpertSettings =
        copy(
            unlockedAt = null,
            warningAcknowledgedAt = null,
            blockScreenshots = false,
            networkActivityLogging = false,
            diagnosticsRetention = DiagnosticsRetention.HOURS_24,
            allowHttpConfigImports = false,
            allowInsecureTls = true,
        )

    private fun patchTunInbound(
        tunInbound: JsonObject,
        traffic: TrafficSettings,
        expert: ExpertSettings,
    ): JsonObject =
        buildJsonObject {
            tunInbound.forEach { (key, value) ->
                when (key) {
                    "mtu" -> put(key, traffic.mtu)
                    "stack" -> put(key, traffic.tunStack.configValue)
                    "strict_route" -> put(key, expert.strictRoute)
                    "sniff", "sniff_override_destination", "include_package", "exclude_package" -> Unit
                    else -> put(key, value)
                }
            }
            put("mtu", traffic.mtu)
            put("stack", traffic.tunStack.configValue)
            put("strict_route", expert.strictRoute)
            if (expert.sniff) {
                put("sniff", true)
                put("sniff_override_destination", !expert.routeOnly)
            }
            when (expert.perAppRoutingMode) {
                PerAppRoutingMode.FULL_TUNNEL -> Unit
                PerAppRoutingMode.INCLUDE_SELECTED_APPS ->
                    putJsonArray("include_package") {
                        expert.selectedPackages.forEach { add(JsonPrimitive(it)) }
                    }
                PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
                    putJsonArray("exclude_package") {
                        expert.selectedPackages.forEach { add(JsonPrimitive(it)) }
                    }
            }
        }

    private fun patchDns(
        existing: JsonObject?,
        traffic: TrafficSettings,
        privateDnsMode: PrivateDnsMode? = null,
    ): JsonObject {
        val effectiveStrategy =
            when {
                traffic.preferIpv6 && traffic.domainStrategy == com.foxhole.beta.core.model.DomainStrategy.PREFER_IPV4 ->
                    com.foxhole.beta.core.model.DomainStrategy.PREFER_IPV6
                else -> traffic.domainStrategy
            }
        if (existing == null || !existing.hasDnsServers() || isFoxholeManagedDns(existing)) {
            return buildFoxholeDnsConfig(effectiveStrategy.configValue, privateDnsMode)
        }
        val source = existing
        return buildJsonObject {
            source.forEach { (key, value) ->
                if (key == "strategy") {
                    put(key, effectiveStrategy.configValue)
                } else {
                    put(key, value)
                }
            }
            if (!source.containsKey("strategy")) {
                put("strategy", effectiveStrategy.configValue)
            }
        }
    }

    private fun patchRoute(
        existing: JsonObject?,
        dns: JsonObject,
        activePreset: RoutingPreset?,
        expert: ExpertSettings,
    ): JsonObject {
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
                ?.map(::toRouteRule)
                .orEmpty()
        val combinedRules =
            buildJsonArray {
                if (expert.sniff) {
                    add(sniffRule())
                }
                hijackDnsRules().forEach(::add)
                if (expert.bypassLan) {
                    add(bypassLanRule())
                }
                if (activePreset?.enabled == true && activePreset.overrideMode == RoutingPresetOverrideMode.FORCE_LOCAL) {
                    presetRules.forEach(::add)
                } else {
                    baseRules.forEach(::add)
                    presetRules.forEach(::add)
                }
            }

        return buildJsonObject {
            if (preserveSource) {
                source.forEach { (key, value) ->
                    if (key == "rules") {
                        put(key, combinedRules)
                    } else {
                        put(key, value)
                    }
                }
            }
            put("rules", combinedRules)
            source["final"]?.let { put("final", it) } ?: put("final", "proxy")
            resolverForRoute(dns, source)?.let { put("default_domain_resolver", it) }
            source["auto_detect_interface"]?.let { put("auto_detect_interface", it) } ?: put("auto_detect_interface", true)
        }
    }

    private fun patchProxyRoute(
        existing: JsonObject?,
        dns: JsonObject,
        activePreset: RoutingPreset?,
    ): JsonObject {
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
                ?.map(::toRouteRule)
                .orEmpty()
        val combinedRules =
            buildJsonArray {
                if (activePreset?.enabled == true && activePreset.overrideMode == RoutingPresetOverrideMode.FORCE_LOCAL) {
                    presetRules.forEach(::add)
                } else {
                    baseRules.forEach(::add)
                    presetRules.forEach(::add)
                }
            }

        return buildJsonObject {
            if (preserveSource) {
                source.forEach { (key, value) ->
                    if (key == "rules") {
                        put(key, combinedRules)
                    } else {
                        put(key, value)
                    }
                }
            }
            put("rules", combinedRules)
            source["final"]?.let { put("final", it) } ?: put("final", "proxy")
            resolverForRoute(dns, source)?.let { put("default_domain_resolver", it) }
            if (!preserveSource) {
                put("auto_detect_interface", true)
            }
        }
    }

    private fun resolverForRoute(
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

    private fun dnsServerTags(dns: JsonObject): List<String> =
        dns["servers"]
            ?.jsonArray
            ?.mapNotNull { server -> server.jsonObject["tag"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()

    private fun buildLocalSurfaceInbounds(localSurfaces: LocalSurfaceSettings): List<JsonObject> =
        buildList {
            val lanListenAddress = localSurfaces.currentLanListenAddressOrNull()
            if (!hasEnabledProxySurface(localSurfaces) && localSurfaces.allowLanAccess) {
                lanListenAddress?.let { lanHost ->
                    add(
                        proxyInbound(
                            type = "http",
                            tag = "http-in-lan",
                            listenHost = lanHost,
                            port = localSurfaces.http.port,
                            auth = localSurfaces.auth,
                        ),
                    )
                }
                return@buildList
            }
            if (localSurfaces.socks.enabled) {
                addAll(
                    proxyInbounds(
                        type = "socks",
                        tag = "socks-in",
                        surface = localSurfaces.socks,
                        auth = localSurfaces.auth,
                        lanListenAddress = lanListenAddress,
                        lanOnly = localSurfaces.allowLanAccess,
                    ),
                )
            }
            if (localSurfaces.http.enabled) {
                addAll(
                    proxyInbounds(
                        type = "http",
                        tag = "http-in",
                        surface = localSurfaces.http,
                        auth = localSurfaces.auth,
                        lanListenAddress = lanListenAddress,
                        lanOnly = localSurfaces.allowLanAccess,
                    ),
                )
            }
            if (localSurfaces.mixed.enabled) {
                addAll(
                    proxyInbounds(
                        type = "mixed",
                        tag = "mixed-in",
                        surface = localSurfaces.mixed,
                        auth = localSurfaces.auth,
                        lanListenAddress = lanListenAddress,
                        lanOnly = localSurfaces.allowLanAccess,
                    ),
                )
            }
        }

    private fun proxyInbounds(
        type: String,
        tag: String,
        surface: ProxyInboundSettings,
        auth: LocalAuthSettings,
        lanListenAddress: String?,
        lanOnly: Boolean,
    ): List<JsonObject> =
        buildList {
            if (!lanOnly) {
                add(proxyInbound(type = type, tag = tag, listenHost = surface.host, port = surface.port, auth = auth))
            }
            lanListenAddress
                ?.takeIf { it != surface.host || lanOnly }
                ?.let { lanHost ->
                    add(proxyInbound(type = type, tag = "$tag-lan", listenHost = lanHost, port = surface.port, auth = auth))
                }
        }

    private fun proxyInbound(
        type: String,
        tag: String,
        listenHost: String,
        port: Int,
        auth: LocalAuthSettings,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            put("tag", tag)
            put("listen", listenHost)
            put("listen_port", port)
            if (auth.enabled) {
                putJsonArray("users") {
                    add(
                        buildJsonObject {
                            put("username", auth.username)
                            put("password", auth.password)
                        },
                    )
                }
            }
        }

    private fun patchExperimental(
        existing: JsonObject?,
        localSurfaces: LocalSurfaceSettings,
    ): JsonObject =
        buildJsonObject {
            existing?.forEach { (key, value) ->
                if (key != "clash_api" && key != "v2ray_api") {
                    put(key, value)
                }
            }
            if (localSurfaces.clashApi.enabled) {
                putJsonObject("clash_api") {
                    put("external_controller", "${localSurfaces.clashApi.host}:${localSurfaces.clashApi.port}")
                    put("secret", localSurfaces.auth.apiSecret)
                }
            }
        }

    private fun toRouteRule(rule: RoutingRule): JsonObject =
        buildJsonObject {
            if (rule.matchDomains.isNotEmpty()) {
                val exact = rule.matchDomains.filterNot { it.startsWith("*.") || it.startsWith(".") }
                val suffix = rule.matchDomains.filter { it.startsWith("*.") || it.startsWith(".") }.map { it.removePrefix("*.").removePrefix(".") }
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
            put("outbound", rule.action.outboundTag)
        }

    private fun normalizeRoutePorts(matchPorts: List<String>): NormalizedRoutePort {
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

    private fun LocalSurfaceSettings.currentLanListenAddressOrNull(): String? =
        lanProxyAddressProvider.currentWifiIpv4Address()?.takeIf { allowLanAccess }

    private fun hasEnabledProxySurface(localSurfaces: LocalSurfaceSettings): Boolean =
        localSurfaces.socks.enabled || localSurfaces.http.enabled || localSurfaces.mixed.enabled

    private fun JsonObject.hasDnsServers(): Boolean =
        this["servers"]?.jsonArray?.isNotEmpty() == true

    private fun buildFoxholeDnsConfig(
        strategy: String,
        privateDnsMode: PrivateDnsMode?,
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
                    add(
                        foxholeRemoteDnsServer(privateDnsMode),
                    )
                },
            )
            put("strategy", strategy)
            put("final", DNS_REMOTE_TAG)
        }

    private fun foxholeDirectDnsServer(): JsonObject =
        buildJsonObject {
            put("tag", DNS_DIRECT_TAG)
            put("type", "local")
        }

    private fun foxholeRemoteDnsServer(_privateDnsMode: PrivateDnsMode?): JsonObject =
        buildJsonObject {
            put("tag", DNS_REMOTE_TAG)
            put("server", FOXHOLE_REMOTE_DNS_SERVER)
            put("type", "https")
            put("server_port", 443)
            put("path", "/dns-query")
            put("detour", "proxy")
        }

    private fun sniffRule(): JsonObject =
        buildJsonObject {
            put("action", "sniff")
        }

    private fun hijackDnsRules(): List<JsonObject> =
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

    private fun bypassLanRule(): JsonObject =
        buildJsonObject {
            put("ip_is_private", true)
            put("action", "route")
            put("outbound", "direct")
        }

    private fun isFoxholeManagedDns(dns: JsonObject): Boolean {
        val servers = dns["servers"]?.jsonArray ?: return false
        val byTag =
            servers
                .mapNotNull { server ->
                    val objectValue = server.jsonObject
                    objectValue["tag"]?.jsonPrimitive?.contentOrNull?.let { it to objectValue }
                }.toMap()
        val local = byTag[DNS_LOCAL_TAG] ?: return false
        val remote = byTag[DNS_REMOTE_TAG] ?: return false
        val localMatches =
            (local["type"]?.jsonPrimitive?.contentOrNull == "local") ||
                (local["address"]?.jsonPrimitive?.contentOrNull == "local")
        val direct = byTag[DNS_DIRECT_TAG]
        val directMatches =
            direct == null ||
                direct["type"]?.jsonPrimitive?.contentOrNull == "local" ||
                direct["address"]?.jsonPrimitive?.contentOrNull == "local" ||
                isFoxholeBootstrapDnsServer(direct)
        val remoteMatches =
                (
                    remote["type"]?.jsonPrimitive?.contentOrNull == "tcp" &&
                    remote["server"]?.jsonPrimitive?.contentOrNull == FOXHOLE_REMOTE_DNS_SERVER &&
                    remote["server_port"]?.jsonPrimitive?.contentOrNull == "53"
                ) ||
                (
                    remote["type"]?.jsonPrimitive?.contentOrNull == "udp" &&
                        remote["server"]?.jsonPrimitive?.contentOrNull == FOXHOLE_REMOTE_DNS_SERVER &&
                        remote["server_port"]?.jsonPrimitive?.contentOrNull == "53"
                    ) ||
                (
                    remote["type"]?.jsonPrimitive?.contentOrNull == "https" &&
                        remote["server"]?.jsonPrimitive?.contentOrNull == FOXHOLE_REMOTE_DNS_SERVER &&
                        remote["server_port"]?.jsonPrimitive?.contentOrNull == "443" &&
                        remote["path"]?.jsonPrimitive?.contentOrNull == "/dns-query"
                    ) ||
                (
                    remote["address"]?.jsonPrimitive?.contentOrNull == FOXHOLE_DOH_ADDRESS
                    )
        val remoteDetourMatches =
            when (remote["type"]?.jsonPrimitive?.contentOrNull) {
                "tcp", "udp" -> !remote.containsKey("detour")
                else -> remote["detour"]?.jsonPrimitive?.contentOrNull == "proxy"
            }
        return localMatches &&
            directMatches &&
            remoteMatches &&
            remoteDetourMatches
    }

    private fun isFoxholeBootstrapDnsServer(server: JsonObject): Boolean {
        if (server.containsKey("detour")) {
            return false
        }
        val type = server["type"]?.jsonPrimitive?.contentOrNull
        val address = server["address"]?.jsonPrimitive?.contentOrNull
        if (address == FOXHOLE_DOH_ADDRESS) {
            return true
        }
        if (server["server"]?.jsonPrimitive?.contentOrNull != FOXHOLE_REMOTE_DNS_SERVER) {
            return false
        }
        val port = server["server_port"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "udp", "tcp" -> port == "53"
            "https" -> port == "443" && server["path"]?.jsonPrimitive?.contentOrNull == "/dns-query"
            else -> false
        }
    }

    private fun isFoxholeManagedRoute(route: JsonObject): Boolean {
        if (!route.keys.all { it in FOXHOLE_ROUTE_KEYS }) {
            return false
        }
        if (route["final"]?.jsonPrimitive?.contentOrNull?.let { it != "proxy" } == true) {
            return false
        }
        val resolver = route["default_domain_resolver"]?.jsonPrimitive?.contentOrNull
        if (resolver != null && resolver !in setOf(DNS_LOCAL_TAG, DNS_DIRECT_TAG, DNS_REMOTE_TAG)) {
            return false
        }
        return route["rules"]?.jsonArray.orEmpty().all { isFoxholeManagedRouteRule(it.jsonObject) }
    }

    private fun isFoxholeManagedRouteRule(rule: JsonObject): Boolean {
        val action = rule["action"]?.jsonPrimitive?.contentOrNull ?: return false
        return when {
            action == "sniff" ->
                rule.keys.all { it == "action" }
            action == "hijack-dns" &&
                foxholeHijackMatch(rule) ->
                rule.keys.all { it in setOf("protocol", "port", "action") }
            action == "route" &&
                rule["outbound"]?.jsonPrimitive?.contentOrNull == "direct" &&
                rule["ip_is_private"]?.jsonPrimitive?.contentOrNull == "true" ->
                rule.keys.all { it in setOf("ip_is_private", "action", "outbound") }
            else -> false
        }
    }

    private fun foxholeHijackMatch(rule: JsonObject): Boolean {
        val protocol = rule["protocol"]?.jsonPrimitive?.contentOrNull
        if (protocol == "dns") {
            return true
        }
        return foxholeHijackPorts(rule["port"])
    }

    private fun foxholeHijackPorts(port: JsonElement?): Boolean {
        if (port == null) {
            return true
        }
        return when (port) {
            is JsonPrimitive -> port.contentOrNull == "53"
            else -> false
        }
    }

    private companion object {
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        val FOXHOLE_ROUTE_KEYS = setOf("rules", "final", "default_domain_resolver", "auto_detect_interface")
        const val DNS_LOCAL_TAG = "dns-local"
        const val DNS_DIRECT_TAG = "dns-direct"
        const val DNS_REMOTE_TAG = "dns-remote"
        const val FOXHOLE_REMOTE_DNS_SERVER = "1.1.1.1"
        const val FOXHOLE_DOH_ADDRESS = "https://1.1.1.1/dns-query"
        val PORT_RANGE_REGEX = Regex("""\d{1,5}-\d{1,5}""")
    }
}

private data class NormalizedRoutePort(
    val ports: List<Int>,
    val portRanges: List<String>,
)

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()
