package com.foxhole.beta.vpn

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SecureDnsMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.V2RayApiSettings
import com.foxhole.beta.core.model.isUdpTransport
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
        val dns: DnsSettings,
        val privacyRoute: PrivacyRouteSettings,
        val expert: ExpertSettings,
    )

    private data class RuntimeSplitPlan(
        val vpnMode: VpnAppSelectionMode,
        val vpnIncludedPackages: List<String>,
        val vpnExcludedPackages: List<String>,
        val torAllApps: Boolean,
        val torTcpPackages: List<String>,
        val torUdpBlockedPackages: List<String>,
        val blockedPackages: List<String>,
        val directPackages: List<String> = emptyList(),
        val warnings: List<SplitWarning> = emptyList(),
    )

    private enum class VpnAppSelectionMode {
        FULL_DEVICE,
        INCLUDE_ONLY,
        EXCLUDE_SELECTED,
    }

    private enum class SplitWarning {
        TOR_SELECTED_APPS_FORCE_TUN_INCLUDE,
    }

    fun assemble(
        baseConfigJson: String,
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode? = null,
        torRuntimePaths: TorRuntimePaths? = null,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        vpnProtocolHint: ProtocolHint? = null,
    ): String {
        validate(settings.expert)
        val base = json.parseToJsonElement(baseConfigJson).jsonObject
        return when (settings.traffic.mode) {
            TrafficMode.TUNNEL ->
                assembleTunnel(
                    base = base,
                    settings = settings,
                    activePreset = activePreset,
                    privateDnsMode = privateDnsMode,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    vpnProtocolHint = vpnProtocolHint,
                )
            TrafficMode.PROXY -> assembleProxy(base, settings, activePreset, dnsFilterRuntimePaths)
        }
    }

    internal fun assembleLocalGuard(
        settings: Settings,
        mode: LocalGuardMode,
    ): String {
        val dnsSettings =
            if (settings.expert.systemDnsProtectionEnabled) {
                settings.dns.systemDnsProtectionSettings()
            } else {
                settings.dns
            }
        val dns =
            buildFoxholeDnsConfig(
                strategy = settings.traffic.domainStrategy.configValue,
                dnsSettings = dnsSettings,
                privateDnsMode = null,
                finalTag =
                    if (settings.expert.systemDnsProtectionEnabled) {
                        DNS_REMOTE_TAG
                    } else {
                        DNS_DIRECT_TAG
                    },
                includeRemote = settings.expert.systemDnsProtectionEnabled,
                remoteDetourTag = "direct",
            )
        val route =
            buildJsonObject {
                val rules =
                    buildJsonArray {
                        if (settings.expert.systemDnsProtectionEnabled || mode == LocalGuardMode.JOURNAL) {
                            hijackDnsRules().forEach(::add)
                        }
                        if (mode != LocalGuardMode.DNS && settings.expert.blockAppsAlways) {
                            buildAppRouteRules(settings.expert).forEach(::add)
                        }
                    }
                put("rules", rules)
                put("final", "direct")
                val defaultResolver =
                    if (settings.expert.systemDnsProtectionEnabled) {
                        DNS_REMOTE_TAG
                    } else {
                        resolverForRoute(dns, buildJsonObject {})
                    }
                defaultResolver?.let { put("default_domain_resolver", it) }
                put("auto_detect_interface", true)
            }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonArray("inbounds") {
                    add(localGuardTunInbound(settings, mode))
                }
                putJsonArray("outbounds") {
                    add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                    add(buildJsonObject { put("type", "block"); put("tag", "block") })
                }
                put("dns", dns)
                put("route", route)
                putJsonObject("log") {
                    put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                    put("timestamp", true)
                }
            },
        )
    }

    internal fun assembleTorOnly(
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode? = null,
        torRuntimePaths: TorRuntimePaths,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
    ): String {
        validate(settings.expert)
        require(settings.privacyRoute.directTorEnabled) { "direct TOR route is disabled" }
        require(
            settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS ||
                settings.privacyRoute.selectedPackages.any(String::isNotBlank),
        ) { "direct TOR route has no selected apps" }
        val dns =
            buildFoxholeDnsConfig(
                strategy = settings.traffic.domainStrategy.configValue,
                dnsSettings = settings.dns,
                privateDnsMode = privateDnsMode,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
            )
        val route =
            patchTorOnlyRoute(
                dns = dns,
                activePreset = activePreset,
                settings = settings,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
            )
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonArray("inbounds") {
                    add(torOnlyTunInbound(settings))
                    add(runtimeLoopbackProxyInbound(settings.expert.localSurfaces))
                    buildLocalSurfaceInbounds(settings.expert.localSurfaces, includeLocalProxy = false).forEach(::add)
                }
                putJsonArray("outbounds") {
                    add(torDirectProxyOutbound(torRuntimePaths))
                    add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                    add(buildJsonObject { put("type", "block"); put("tag", "block") })
                }
                put("dns", dns)
                put("route", route)
                putJsonObject("log") {
                    put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                    put("timestamp", true)
                }
            },
        )
    }

    private fun assembleTunnel(
        base: JsonObject,
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode?,
        torRuntimePaths: TorRuntimePaths?,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
        vpnProtocolHint: ProtocolHint?,
    ): String {
        val tunInbound = base["inbounds"]?.jsonArray?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "tun" }?.jsonObject
            ?: error("base config must define a tun inbound")
        val privacyRouteActive = settings.isTorPrivacyRouteActive(vpnProtocolHint)
        val splitPlan = buildSplitPlan(settings, privacyRouteActive)
        val runtimeBase =
            base
                .withTcpReliabilityOutbounds()
                .withTorPrivacyRouteOutbound(
                    paths = torRuntimePaths.takeIf { privacyRouteActive },
                    detourThroughVpn = !settings.privacyRoute.bypassVpnTunnel,
                )
        val patchedTun =
            patchTunInbound(
                tunInbound = tunInbound,
                traffic = settings.traffic,
                dnsSettings = settings.dns,
                expert = settings.expert,
                splitPlan = splitPlan,
                mtu = effectiveTunMtu(runtimeBase, settings.traffic.mtu),
                stack = effectiveTunnelTunStack(settings.traffic.tunStack),
            )
        val inbounds =
            buildJsonArray {
                add(patchedTun)
                add(runtimeLoopbackProxyInbound(settings.expert.localSurfaces))
                buildLocalSurfaceInbounds(settings.expert.localSurfaces, includeLocalProxy = false).forEach(::add)
            }
        val patchedDns =
            patchDns(
                existing = runtimeBase["dns"]?.jsonObject,
                base = runtimeBase,
                traffic = settings.traffic,
                dnsSettings = settings.dns,
                privateDnsMode = privateDnsMode,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                privacyRouteActive = privacyRouteActive,
            )
        val patchedRoute =
            patchRoute(
                existing = runtimeBase["route"]?.jsonObject,
                dns = patchedDns,
                activePreset = activePreset,
                settings = settings,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                privacyRouteActive = privacyRouteActive,
                splitPlan = splitPlan,
            )
        val patchedExperimental =
            patchExperimental(
                runtimeBase["experimental"]?.jsonObject,
                settings.expert.localSurfaces,
            )

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                runtimeBase.forEach { (key, value) ->
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
                if (!runtimeBase.containsKey("log")) {
                    putJsonObject("log") {
                        put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                        put("timestamp", true)
                    }
                }
                if (!runtimeBase.containsKey("experimental") && patchedExperimental.isNotEmpty()) {
                    put("experimental", patchedExperimental)
                }
            },
        )
    }

    private fun assembleProxy(
        base: JsonObject,
        settings: Settings,
        activePreset: RoutingPreset?,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    ): String {
        val localSurfaces = settings.expert.localSurfaces
        val inbounds =
            buildJsonArray {
                buildLocalSurfaceInbounds(localSurfaces, includeLocalProxy = true).forEach(::add)
            }
        val runtimeBase = base.withTcpReliabilityOutbounds()
        val patchedDns =
            patchDns(runtimeBase["dns"]?.jsonObject, runtimeBase, settings.traffic, settings.dns, dnsFilterRuntimePaths)
        val patchedRoute =
            patchProxyRoute(runtimeBase["route"]?.jsonObject, patchedDns, activePreset, settings, dnsFilterRuntimePaths)
        val patchedExperimental = patchExperimental(runtimeBase["experimental"]?.jsonObject, localSurfaces)

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                runtimeBase.forEach { (key, value) ->
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
                if (!runtimeBase.containsKey("log")) {
                    putJsonObject("log") {
                        put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                        put("timestamp", true)
                    }
                }
                if (!runtimeBase.containsKey("experimental") && patchedExperimental.isNotEmpty()) {
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
                    dns = settings.dns,
                    privacyRoute = settings.privacyRoute,
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
            buildMap<String, String> {
                addPort(expert.localSurfaces.proxySurface(), "Local Proxy")
                addPort(expert.localSurfaces.clashApi, "Clash API")
                addPort(expert.localSurfaces.v2RayApi, "V2Ray API")
            }
        require(enabledPorts.size == enabledPorts.keys.distinct().size) { "enabled local surfaces must not share the same endpoint" }
    }

    private fun MutableMap<String, String>.addPort(
        surface: ProxyInboundSettings,
        label: String,
        validateHost: Boolean = true,
    ) {
        if (validateHost) {
            validateLocalHost(surface.host, label)
        }
        val previous = put("${surface.host}:${surface.port}", label)
        require(previous == null) { "$label port conflicts with $previous" }
    }

    private fun MutableMap<String, String>.addPort(
        surface: ClashApiSettings,
        label: String,
    ) {
        if (!surface.enabled) {
            return
        }
        validateLocalHost(surface.host, label)
        val previous = put("${surface.host}:${surface.port}", label)
        require(previous == null) { "$label port conflicts with $previous" }
    }

    private fun MutableMap<String, String>.addPort(
        surface: V2RayApiSettings,
        label: String,
    ) {
        if (!surface.enabled) {
            return
        }
        validateLocalHost(surface.host, label)
        val previous = put("${surface.host}:${surface.port}", label)
        require(previous == null) { "$label port conflicts with $previous" }
    }

    private fun validateLocalHost(
        host: String,
        label: String,
    ) {
        val normalized = host.trim().lowercase()
        require(normalized in LOCAL_HOSTS) { "$label host must stay on a local loopback address" }
    }

    private fun ExpertSettings.runtimeFingerprintSettings(): ExpertSettings {
        val runtimeSelectedPackages =
            selectedPackages.takeIf { perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL }.orEmpty()
        val runtimeBlockedPackages =
            blockedPackages.takeIf { blockedPackagesEnabled }.orEmpty()
        return copy(
            unlockedAt = null,
            warningAcknowledgedAt = null,
            blockScreenshots = false,
            killSwitchEnabled = false,
            firewallEnabled = false,
            networkActivityLogging = false,
            networkActivityPersistentLogging = false,
            diagnosticsRetention = DiagnosticsRetention.HOURS_24,
            allowInsecureTls = true,
            selectedPackages = runtimeSelectedPackages,
            blockedPackages = runtimeBlockedPackages,
            blockedPackagesEnabled = runtimeBlockedPackages.isNotEmpty(),
            blockAppsAlways = false,
        )
    }

    private fun patchTunInbound(
        tunInbound: JsonObject,
        traffic: TrafficSettings,
        dnsSettings: DnsSettings,
        expert: ExpertSettings,
        splitPlan: RuntimeSplitPlan,
        mtu: Int,
        stack: TunStack,
    ): JsonObject {
        val includePackages = splitPlan.vpnIncludedPackages
        val excludePackages = splitPlan.vpnExcludedPackages
        return buildJsonObject {
            tunInbound.forEach { (key, value) ->
                when (key) {
                    "mtu" -> put(key, mtu)
                    "stack" -> put(key, stack.configValue)
                    "strict_route" -> put(key, expert.strictRoute || dnsSettings.blockOutsideTunnel)
                    "sniff",
                    "sniff_override_destination",
                    "sniff_timeout",
                    "domain_strategy",
                    "include_package",
                    "exclude_package" -> Unit
                    else -> put(key, value)
                }
            }
            put("mtu", mtu)
            put("stack", stack.configValue)
            put("strict_route", expert.strictRoute || dnsSettings.blockOutsideTunnel)
            when {
                includePackages.isNotEmpty() ->
                    putJsonArray("include_package") {
                        includePackages.forEach { add(JsonPrimitive(it)) }
                    }
                excludePackages.isNotEmpty() ->
                    putJsonArray("exclude_package") {
                        excludePackages.forEach { add(JsonPrimitive(it)) }
                    }
            }
        }
    }

    private fun effectiveTunMtu(
        base: JsonObject,
        configuredMtu: Int,
    ): Int {
        val wireGuardMtu =
            base["endpoints"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .filter { endpoint -> endpoint["type"]?.jsonPrimitive?.contentOrNull.equals("wireguard", ignoreCase = true) }
                .mapNotNull { endpoint -> endpoint["mtu"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
                .minOrNull()
        return wireGuardMtu?.let { minOf(configuredMtu, it) } ?: configuredMtu
    }

    private fun effectiveTunnelTunStack(
        configured: TunStack,
    ): TunStack = configured

    private fun buildSplitPlan(
        settings: Settings,
        privacyRouteActive: Boolean,
    ): RuntimeSplitPlan {
        val blockedPackages =
            normalizedRuntimePackages(
                settings.expert.blockedPackages.takeIf { settings.expert.blockedPackagesEnabled }.orEmpty(),
            )
        val selectedTorPackages =
            if (privacyRouteActive && settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
                normalizedRuntimePackages(settings.privacyRoute.selectedPackages)
            } else {
                emptyList()
            }
        val torAllApps = privacyRouteActive && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS
        val baseIncluded = settings.expert.vpnIncludedPackages()
        val baseExcluded = settings.expert.vpnExcludedPackages()
        val includePackages =
            if (selectedTorPackages.isNotEmpty()) {
                normalizedRuntimePackages(selectedTorPackages + blockedPackages)
            } else {
                baseIncluded
            }
        val excludePackages =
            if (selectedTorPackages.isNotEmpty()) {
                emptyList()
            } else {
                baseExcluded
            }
        val vpnMode =
            when {
                includePackages.isNotEmpty() -> VpnAppSelectionMode.INCLUDE_ONLY
                excludePackages.isNotEmpty() -> VpnAppSelectionMode.EXCLUDE_SELECTED
                else -> VpnAppSelectionMode.FULL_DEVICE
            }
        return RuntimeSplitPlan(
            vpnMode = vpnMode,
            vpnIncludedPackages = includePackages,
            vpnExcludedPackages = excludePackages,
            torAllApps = torAllApps,
            torTcpPackages = selectedTorPackages,
            torUdpBlockedPackages = selectedTorPackages,
            blockedPackages = blockedPackages,
            warnings =
                if (selectedTorPackages.isNotEmpty()) {
                    listOf(SplitWarning.TOR_SELECTED_APPS_FORCE_TUN_INCLUDE)
                } else {
                    emptyList()
                },
        )
    }

    private fun localGuardTunInbound(
        settings: Settings,
        mode: LocalGuardMode,
    ): JsonObject =
        buildJsonObject {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "foxhole")
            put("mtu", settings.traffic.mtu)
            put("auto_route", true)
            put("strict_route", false)
            put("stack", settings.traffic.tunStack.configValue)
            if (mode == LocalGuardMode.DNS) {
                putJsonArray("route_address") {
                    add(JsonPrimitive("$ADGUARD_DNS_PRIMARY/32"))
                    add(JsonPrimitive("$ADGUARD_DNS_SECONDARY/32"))
                }
            }
            putJsonArray("address") {
                add(JsonPrimitive("172.19.0.1/30"))
                add(JsonPrimitive("fdfe:dcba:9876::1/126"))
            }
            if (
                mode == LocalGuardMode.FIREWALL &&
                settings.expert.blockAppsAlways &&
                settings.expert.blockedPackagesEnabled
            ) {
                val packages = normalizedRuntimePackages(settings.expert.blockedPackages)
                if (packages.isNotEmpty()) {
                    putJsonArray("include_package") {
                        packages.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }

    private fun torOnlyTunInbound(settings: Settings): JsonObject =
        buildJsonObject {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "foxhole")
            put("mtu", settings.traffic.mtu)
            put("auto_route", true)
            put("strict_route", settings.expert.strictRoute || settings.dns.blockOutsideTunnel)
            put("stack", settings.traffic.tunStack.configValue)
            putJsonArray("address") {
                add(JsonPrimitive("172.19.0.1/30"))
                add(JsonPrimitive("fdfe:dcba:9876::1/126"))
            }
            val torPackages =
                if (settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
                    normalizedRuntimePackages(settings.privacyRoute.selectedPackages)
                } else {
                    emptyList()
                }
            val includePackages = torPackages.ifEmpty { settings.expert.vpnIncludedPackages() }
            val excludePackages = if (torPackages.isEmpty()) settings.expert.vpnExcludedPackages() else emptyList()
            when {
                includePackages.isNotEmpty() ->
                    putJsonArray("include_package") {
                        includePackages.forEach { add(JsonPrimitive(it)) }
                    }
                excludePackages.isNotEmpty() ->
                    putJsonArray("exclude_package") {
                        excludePackages.forEach { add(JsonPrimitive(it)) }
                    }
            }
        }

    private fun JsonObject.withTcpReliabilityOutbounds(): JsonObject {
        val patchedOutbounds = patchTcpReliabilityOutbounds(this["outbounds"]?.jsonArray) ?: return this
        return buildJsonObject {
            this@withTcpReliabilityOutbounds.forEach { (key, value) ->
                if (key == "outbounds") {
                    put(key, patchedOutbounds)
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun JsonObject.withTorPrivacyRouteOutbound(
        paths: TorRuntimePaths?,
        detourThroughVpn: Boolean,
    ): JsonObject {
        paths ?: return this
        val patchedOutbounds =
            buildJsonArray {
                this@withTorPrivacyRouteOutbound["outbounds"]
                    ?.jsonArray
                    .orEmpty()
                    .map { it.jsonObject }
                    .filterNot { outbound -> outbound["tag"]?.jsonPrimitive?.contentOrNull == TOR_OVER_VPN_OUTBOUND_TAG }
                    .forEach(::add)
                add(torOverVpnOutbound(paths, detourThroughVpn))
            }
        return buildJsonObject {
            this@withTorPrivacyRouteOutbound.forEach { (key, value) ->
                if (key == "outbounds") {
                    put(key, patchedOutbounds)
                } else {
                    put(key, value)
                }
            }
            if (!containsKey("outbounds")) {
                put("outbounds", patchedOutbounds)
            }
        }
    }

    private fun torOverVpnOutbound(
        paths: TorRuntimePaths,
        detourThroughVpn: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("type", "tor")
            put("tag", TOR_OVER_VPN_OUTBOUND_TAG)
            put("executable_path", paths.executablePath)
            paths.torrcDefaultsFilePath?.let { defaultsPath ->
                putJsonArray("extra_args") {
                    add(JsonPrimitive("--defaults-torrc"))
                    add(JsonPrimitive(defaultsPath))
                }
            }
            put("data_directory", paths.dataDirectory)
            putJsonObject("torrc") {
                put("ClientOnly", "1")
                put("AvoidDiskWrites", "1")
                paths.geoIpFilePath?.let { put("GeoIPFile", it) }
                paths.geoIpv6FilePath?.let { put("GeoIPv6File", it) }
            }
            if (detourThroughVpn) {
                put("detour", "proxy")
            }
        }

    private fun torDirectProxyOutbound(paths: TorRuntimePaths): JsonObject =
        buildJsonObject {
            put("type", "tor")
            put("tag", "proxy")
            put("executable_path", paths.executablePath)
            paths.torrcDefaultsFilePath?.let { defaultsPath ->
                putJsonArray("extra_args") {
                    add(JsonPrimitive("--defaults-torrc"))
                    add(JsonPrimitive(defaultsPath))
                }
            }
            put("data_directory", paths.dataDirectory)
            putJsonObject("torrc") {
                put("ClientOnly", "1")
                put("AvoidDiskWrites", "1")
                paths.geoIpFilePath?.let { put("GeoIPFile", it) }
                paths.geoIpv6FilePath?.let { put("GeoIPv6File", it) }
            }
        }

    private fun patchTcpReliabilityOutbounds(outbounds: JsonArray?): JsonArray? =
        outbounds?.let { source ->
            buildJsonArray {
                source.forEach { outbound ->
                    add(patchTcpReliabilityOutbound(outbound.jsonObject))
                }
            }
        }

    private fun patchTcpReliabilityOutbound(outbound: JsonObject): JsonObject {
        if (!outbound.requiresTcpReliabilityPatch()) {
            return outbound
        }
        return buildJsonObject {
            outbound.forEach { (key, value) -> put(key, value) }
            if (outbound["disable_tcp_keep_alive"]?.jsonPrimitive?.contentOrNull != "true") {
                if (!outbound.containsKey("tcp_keep_alive")) {
                    put("tcp_keep_alive", MOBILE_TCP_KEEP_ALIVE)
                }
                if (!outbound.containsKey("tcp_keep_alive_interval")) {
                    put("tcp_keep_alive_interval", MOBILE_TCP_KEEP_ALIVE_INTERVAL)
                }
            }
        }
    }

    private fun JsonObject.requiresTcpReliabilityPatch(): Boolean {
        val type = this["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val transportType =
            this["transport"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.lowercase()
                ?: "tcp"
        return type in TCP_RELIABILITY_OUTBOUND_TYPES &&
            !containsKey("detour") &&
            transportType in TCP_RELIABILITY_TRANSPORT_TYPES
    }

    private fun patchDns(
        existing: JsonObject?,
        base: JsonObject,
        traffic: TrafficSettings,
        dnsSettings: DnsSettings = DnsSettings(),
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        privateDnsMode: PrivateDnsMode? = null,
        privacyRouteActive: Boolean = false,
    ): JsonObject {
        val effectiveStrategy =
            when {
                traffic.preferIpv6 && traffic.domainStrategy == com.foxhole.beta.core.model.DomainStrategy.PREFER_IPV4 ->
                    com.foxhole.beta.core.model.DomainStrategy.PREFER_IPV6
                else -> traffic.domainStrategy
        }
        if (existing == null || !existing.hasDnsServers() || isFoxholeManagedDns(existing)) {
            val wireGuardDnsServers = wireGuardDnsServers(existing)
            val dnsRoute = managedDnsRoute(base, dnsSettings, privacyRouteActive)
            return buildFoxholeDnsConfig(
                strategy = effectiveStrategy.configValue,
                dnsSettings = dnsSettings,
                privateDnsMode = privateDnsMode,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                extraServers = wireGuardDnsServers,
                finalTag = dnsRoute.finalTag,
                includeRemote = dnsRoute.includeRemote,
                remoteDetourTag = if (privacyRouteActive) TOR_OVER_VPN_OUTBOUND_TAG else "proxy",
            )
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

    @Suppress("CyclomaticComplexMethod")
    private fun patchRoute(
        existing: JsonObject?,
        dns: JsonObject,
        activePreset: RoutingPreset?,
        settings: Settings,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
        privacyRouteActive: Boolean,
        splitPlan: RuntimeSplitPlan,
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
        val privacyRouteRules =
            if (privacyRouteActive) {
                buildTorPrivacyRouteRules(splitPlan)
            } else {
                emptyList()
            }
        val combinedRules =
            buildJsonArray {
                appRules.forEach(::add)
                if (expert.sniff) {
                    add(sniffRule())
                }
                if (settings.dns.interceptDnsRequests) {
                    hijackDnsRules().forEach(::add)
                }
                if (expert.bypassLan) {
                    add(bypassLanRule())
                }
                privacyRouteRules.forEach(::add)
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

    private fun Settings.isTorPrivacyRouteActive(vpnProtocolHint: ProtocolHint?): Boolean =
        privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
            traffic.mode == TrafficMode.TUNNEL &&
            (privacyRoute.bypassVpnTunnel || vpnProtocolHint?.isUdpTransport() != true) &&
            when (privacyRoute.scope) {
                PrivacyRouteScope.ALL_APPS -> true
                PrivacyRouteScope.SELECTED_APPS -> privacyRoute.selectedPackages.any(String::isNotBlank)
            }

    private fun patchTorOnlyRoute(
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
                if (expert.sniff) {
                    add(sniffRule())
                }
                if (settings.dns.interceptDnsRequests) {
                    hijackDnsRules().forEach(::add)
                }
                if (expert.bypassLan) {
                    add(bypassLanRule())
                }
                buildTorPrivacyRouteRules(settings).forEach(::add)
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
            put("final", "proxy")
            resolverForRoute(dns, buildJsonObject {})?.let { put("default_domain_resolver", it) }
            put("auto_detect_interface", true)
        }
    }

    private fun buildTorPrivacyRouteRules(settings: Settings): List<JsonObject> =
        buildTorPrivacyRouteRules(buildSplitPlan(settings, privacyRouteActive = settings.privacyRoute.enabled))

    private fun buildTorPrivacyRouteRules(splitPlan: RuntimeSplitPlan): List<JsonObject> =
        when {
            splitPlan.torAllApps -> listOf(runtimeProxyTorRouteRule(), udpBlockRule())
            splitPlan.torTcpPackages.isNotEmpty() ->
                listOf(
                    runtimeProxyTorRouteRule(),
                    packageNetworkRouteRule(
                        splitPlan.torUdpBlockedPackages,
                        network = "udp",
                        outboundTag = "block",
                    ),
                    packageNetworkRouteRule(
                        splitPlan.torTcpPackages,
                        network = "tcp",
                        outboundTag = TOR_OVER_VPN_OUTBOUND_TAG,
                    ),
                )
            else -> emptyList()
        }

    private fun runtimeProxyTorRouteRule(): JsonObject =
        buildJsonObject {
            putJsonArray("inbound") {
                add(JsonPrimitive(RUNTIME_LOOPBACK_PROXY_INBOUND_TAG))
            }
            put("network", "tcp")
            put("action", "route")
            put("outbound", TOR_OVER_VPN_OUTBOUND_TAG)
        }

    private fun udpBlockRule(): JsonObject =
        buildJsonObject {
            put("network", "udp")
            put("action", "route")
            put("outbound", "block")
        }

    private fun packageNetworkRouteRule(
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

    private fun patchProxyRoute(
        existing: JsonObject?,
        dns: JsonObject,
        activePreset: RoutingPreset?,
        settings: Settings,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
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

    private fun mergedRouteRuleSets(
        source: JsonObject,
        dnsSettings: DnsSettings,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    ): JsonArray? {
        val existing =
            source["rule_set"]
                ?.jsonArray
                ?.filterNot { element -> element.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == DNS_ADGUARD_RULE_SET_TAG }
                .orEmpty()
        val foxholeRuleSet =
            if (dnsSettings.bundledAdGuardFilterEnabled() && dnsFilterRuntimePaths != null) {
                buildJsonObject {
                    put("type", "local")
                    put("tag", DNS_ADGUARD_RULE_SET_TAG)
                    put("format", "binary")
                    put("path", dnsFilterRuntimePaths.adGuardDnsFilterPath)
                }
            } else {
                null
            }
        val merged = existing + listOfNotNull(foxholeRuleSet)
        return merged.takeIf(List<JsonElement>::isNotEmpty)?.let(::JsonArray)
    }

    private fun DnsSettings.bundledAdGuardFilterEnabled(): Boolean =
        filteringEnabled && (blockAds || blockTrackers || blockAppTelemetry || blockMaliciousDomains)

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

    private fun buildLocalSurfaceInbounds(
        localSurfaces: LocalSurfaceSettings,
        includeLocalProxy: Boolean,
    ): List<JsonObject> =
        buildList {
            val lanListenAddress = localSurfaces.currentLanListenAddressOrNull()
            if (includeLocalProxy) {
                val localSurface = localSurfaces.proxySurface()
                add(
                    proxyInbound(
                        type = localSurfaces.proxyMode.inboundType,
                        tag = "${localSurfaces.proxyMode.inboundTag}-in",
                        listenHost = localSurface.host,
                        port = localSurface.port,
                        auth = localSurfaces.auth,
                    ),
                )
            }
            lanListenAddress?.let { lanHost ->
                val lanSurface = localSurfaces.lanProxySurface()
                add(
                    proxyInbound(
                        type = localSurfaces.lanProxyMode.inboundType,
                        tag = "${localSurfaces.lanProxyMode.inboundTag}-in-lan",
                        listenHost = lanHost,
                        port = lanSurface.port,
                        auth = localSurfaces.lanAuth,
                    ),
                )
            }
        }

    private fun runtimeLoopbackProxyInbound(localSurfaces: LocalSurfaceSettings): JsonObject {
        val localSurface = localSurfaces.proxySurface()
        return proxyInbound(
            type = ProxySurfaceMode.ALL.inboundType,
            tag = RUNTIME_LOOPBACK_PROXY_INBOUND_TAG,
            listenHost = localSurface.host,
            port = localSurface.port,
            auth = LocalAuthSettings(enabled = false),
        )
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

    private fun buildAppRouteRules(expert: ExpertSettings): List<JsonObject> =
        buildList {
            if (expert.blockedPackagesEnabled && expert.blockedPackages.isNotEmpty()) {
                add(packageRouteRule(expert.blockedPackages, RoutingRuleAction.BLOCK))
            }
        }

    private fun ExpertSettings.vpnIncludedPackages(): List<String> =
        when (perAppRoutingMode) {
            PerAppRoutingMode.INCLUDE_SELECTED_APPS ->
                normalizedRuntimePackages(
                    selectedPackages +
                        blockedPackages.takeIf { blockedPackagesEnabled }.orEmpty(),
                )
            PerAppRoutingMode.FULL_TUNNEL,
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
            -> emptyList()
        }

    private fun ExpertSettings.vpnExcludedPackages(): List<String> =
        when (perAppRoutingMode) {
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> normalizedRuntimePackages(selectedPackages)
            PerAppRoutingMode.FULL_TUNNEL,
            PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            -> emptyList()
        }

    private fun normalizedRuntimePackages(packageNames: List<String>): List<String> =
        packageNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()

    private fun packageRouteRule(
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

    private fun tunnelFinalOutbound(source: JsonObject): String = source["final"]?.jsonPrimitive?.contentOrNull ?: "proxy"

    private fun toRouteRule(
        rule: RoutingRule,
        siteRoutingAction: RoutingRuleAction,
    ): JsonObject =
        buildJsonObject {
            if (rule.matchDomains.isNotEmpty()) {
                val exact = rule.matchDomains.filterNot {
                    it.startsWith("*.") || it.startsWith(".") || it.startsWith(SITE_KEYWORD_PREFIX) || it.startsWith(SITE_REGEX_PREFIX)
                }
                val suffix = rule.matchDomains.filter { it.startsWith("*.") || it.startsWith(".") }.map { it.removePrefix("*.").removePrefix(".") }
                val keywords = rule.matchDomains.filter { it.startsWith(SITE_KEYWORD_PREFIX) }.map { it.removePrefix(SITE_KEYWORD_PREFIX) }
                val regexes = rule.matchDomains.filter { it.startsWith(SITE_REGEX_PREFIX) }.map { it.removePrefix(SITE_REGEX_PREFIX) }
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

    private fun LocalSurfaceSettings.proxySurface(): ProxyInboundSettings =
        when (proxyMode) {
            ProxySurfaceMode.SOCKS5 -> socks
            ProxySurfaceMode.HTTP -> http
            ProxySurfaceMode.ALL -> mixed
        }

    private fun LocalSurfaceSettings.lanProxySurface(): ProxyInboundSettings =
        when (lanProxyMode) {
            ProxySurfaceMode.SOCKS5 -> socks
            ProxySurfaceMode.HTTP -> http
            ProxySurfaceMode.ALL -> mixed
        }

    private val ProxySurfaceMode.inboundType: String
        get() =
            when (this) {
                ProxySurfaceMode.SOCKS5 -> "socks"
                ProxySurfaceMode.HTTP -> "http"
                ProxySurfaceMode.ALL -> "mixed"
            }

    private val ProxySurfaceMode.inboundTag: String
        get() =
            when (this) {
                ProxySurfaceMode.SOCKS5 -> "socks"
                ProxySurfaceMode.HTTP -> "http"
                ProxySurfaceMode.ALL -> "mixed"
            }

    private fun JsonObject.hasDnsServers(): Boolean =
        this["servers"]?.jsonArray?.isNotEmpty() == true

    private fun buildFoxholeDnsConfig(
        strategy: String,
        dnsSettings: DnsSettings = DnsSettings(),
        privateDnsMode: PrivateDnsMode?,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        extraServers: List<JsonObject> = emptyList(),
        finalTag: String = DNS_REMOTE_TAG,
        includeRemote: Boolean = true,
        remoteDetourTag: String = "proxy",
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
                        add(
                            foxholeRemoteDnsServer(
                                dnsSettings = dnsSettings,
                                _privateDnsMode = privateDnsMode,
                                detourTag = remoteDetourTag.takeIf { dnsSettings.dnsThroughVpn },
                            ),
                        )
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

    private fun wireGuardDnsServers(dns: JsonObject?): List<JsonObject> =
        dns
            ?.get("servers")
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { server -> server["tag"]?.jsonPrimitive?.contentOrNull == WIREGUARD_DNS_TAG }
            .orEmpty()

    private data class ManagedDnsRoute(
        val finalTag: String,
        val includeRemote: Boolean,
    )

    private fun managedDnsRoute(
        base: JsonObject,
        dnsSettings: DnsSettings,
        privacyRouteActive: Boolean,
    ): ManagedDnsRoute {
        val wireGuardSelected = selectedProxyEndpointType(base).equals("wireguard", ignoreCase = true)
        val proxiedPlainDns = dnsSettings.secureMode == SecureDnsMode.PLAIN && dnsSettings.dnsThroughVpn
        val finalTag =
            when {
                privacyRouteActive -> DNS_REMOTE_TAG
                wireGuardSelected || proxiedPlainDns -> DNS_DIRECT_TAG
                else -> DNS_REMOTE_TAG
            }
        val includeRemote =
            when {
                privacyRouteActive -> true
                proxiedPlainDns -> dnsSettings.filteringEnabled
                else -> true
            }
        return ManagedDnsRoute(finalTag = finalTag, includeRemote = includeRemote)
    }

    private fun selectedProxyEndpointType(base: JsonObject): String? {
        val selectedTag = selectedProxyTag(base) ?: return null
        return base["endpoints"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { endpoint -> endpoint["tag"]?.jsonPrimitive?.contentOrNull == selectedTag }
            ?.get("type")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private fun selectedProxyTag(base: JsonObject): String? {
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

    private fun foxholeDirectDnsServer(): JsonObject =
        buildJsonObject {
            put("tag", DNS_DIRECT_TAG)
            put("type", "local")
        }

    private fun DnsSettings.systemDnsProtectionSettings(): DnsSettings =
        copy(
            server = ADGUARD_DNS_PRIMARY,
            secureMode = SecureDnsMode.PLAIN,
            dnsThroughVpn = false,
            filteringEnabled = false,
        )

    private fun foxholeRemoteDnsServer(
        dnsSettings: DnsSettings,
        _privateDnsMode: PrivateDnsMode?,
        detourTag: String?,
    ): JsonObject =
        buildJsonObject {
            put("tag", DNS_REMOTE_TAG)
            put("server", dnsSettings.server)
            put("type", dnsSettings.secureMode.configType)
            put("server_port", dnsSettings.secureMode.defaultPort)
            if (dnsSettings.secureMode == SecureDnsMode.DOH) {
                put("path", "/dns-query")
            }
            if (dnsSettings.server.requiresDnsDomainResolver()) {
                put("domain_resolver", DNS_DIRECT_TAG)
            }
            detourTag?.let { put("detour", it) }
        }

    private fun buildDnsRules(
        dnsSettings: DnsSettings,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    ): List<JsonObject> =
        buildList {
            if (!dnsSettings.filteringEnabled) {
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
                    ?.adGuardVpnCompatibilityDomains
                    .orEmpty()
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
            if (dnsSettings.bundledAdGuardFilterEnabled() && dnsFilterRuntimePaths != null) {
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

    private fun String.requiresDnsDomainResolver(): Boolean {
        val host = trim().removeSurrounding("[", "]")
        if (host.isBlank()) {
            return false
        }
        return !host.isIpv4Literal() && !host.isIpv6Literal()
    }

    private fun String.isIpv4Literal(): Boolean {
        val parts = split('.')
        return parts.size == 4 &&
            parts.all { part ->
                part.isNotEmpty() &&
                    part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { value -> value in 0..255 } == true
            }
    }

    private fun String.isIpv6Literal(): Boolean =
        count { it == ':' } >= 2 &&
            all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' || char == ':' || char == '.' }

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
            remote["detour"]?.jsonPrimitive?.contentOrNull == null ||
                remote["detour"]?.jsonPrimitive?.contentOrNull == "proxy"
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
            action == "route" &&
                rule["outbound"]?.jsonPrimitive?.contentOrNull in setOf("proxy", "direct", "block") &&
                rule["package_name"] != null ->
                rule.keys.all { it in setOf("package_name", "action", "outbound") }
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
        val FOXHOLE_ROUTE_KEYS = setOf("rules", "rule_set", "final", "default_domain_resolver", "auto_detect_interface")
        const val DNS_LOCAL_TAG = "dns-local"
        const val DNS_DIRECT_TAG = "dns-direct"
        const val DNS_REMOTE_TAG = "dns-remote"
        const val DNS_ADGUARD_RULE_SET_TAG = "foxhole-adguard-dns-filter"
        const val WIREGUARD_DNS_TAG = "dns-wireguard"
        const val TOR_OVER_VPN_OUTBOUND_TAG = "tor-over-vpn"
        const val RUNTIME_LOOPBACK_PROXY_INBOUND_TAG = "foxhole-runtime-proxy-in"
        const val FOXHOLE_REMOTE_DNS_SERVER = "1.1.1.1"
        const val FOXHOLE_DOH_ADDRESS = "https://1.1.1.1/dns-query"
        const val ADGUARD_DNS_PRIMARY = "94.140.14.14"
        const val ADGUARD_DNS_SECONDARY = "94.140.15.15"
        const val MOBILE_TCP_KEEP_ALIVE = "30s"
        const val MOBILE_TCP_KEEP_ALIVE_INTERVAL = "15s"
        const val SITE_KEYWORD_PREFIX = "kw:"
        const val SITE_REGEX_PREFIX = "re:"
        val TCP_RELIABILITY_OUTBOUND_TYPES = setOf("vless", "trojan", "vmess", "shadowsocks", "http", "socks")
        val TCP_RELIABILITY_TRANSPORT_TYPES = setOf("tcp", "ws", "grpc", "http", "httpupgrade")
        val PORT_RANGE_REGEX = Regex("""\d{1,5}-\d{1,5}""")
    }
}

private data class NormalizedRoutePort(
    val ports: List<Int>,
    val portRanges: List<String>,
)

private fun RoutingRule.runtimeAction(siteRoutingAction: RoutingRuleAction): RoutingRuleAction =
    if (isManagedSelectedSiteRule()) {
        when (siteRoutingAction) {
            RoutingRuleAction.PROXY,
            RoutingRuleAction.DIRECT,
            -> siteRoutingAction
            RoutingRuleAction.BLOCK -> RoutingRuleAction.PROXY
        }
    } else {
        action
    }

private fun RoutingRule.isManagedSelectedSiteRule(): Boolean =
    name.startsWith(MANAGED_SELECTED_SITE_RULE_PREFIX)

private const val MANAGED_SELECTED_SITE_RULE_PREFIX = "Foxhole selected site:"

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()
