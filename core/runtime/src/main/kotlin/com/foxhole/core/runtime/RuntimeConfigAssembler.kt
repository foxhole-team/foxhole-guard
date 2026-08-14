package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ClashApiSettings
import com.foxhole.core.model.DiagnosticsRetention
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import com.foxhole.core.model.V2RayApiSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Suppress("LargeClass", "TooManyFunctions")
class RuntimeConfigAssembler(
    private val json: Json,
    private val lanProxyAddressProvider: LanProxyAddressProvider = DisabledLanProxyAddressProvider,
    // The package the app actually runs as. It is normally outside local/standalone-Tor TUNs, but
    // web apps deliberately capture it because WebView has no per-socket protect seam.
    private val selfPackageName: String = BuildConfig.APPLICATION_ID,
) {
    @Serializable
    private data class RuntimeFingerprintSettings(
        val traffic: TrafficSettings,
        val dns: DnsSettings,
        val privacyRoute: PrivacyRouteSettings,
        val i2p: I2pSettings,
        val expert: ExpertSettings,
        val webAppsEnabled: Boolean,
    )

    fun assemble(
        baseConfigJson: String,
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
        torRuntimePaths: TorRuntimePaths? = null,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        vpnProtocolHint: ProtocolHint? = null,
        i2pSocksPort: Int? = null,
        protocolTestTrafficFreeze: Boolean = false,
    ): String {
        validate(settings.expert)
        val runtimeSettings = settings.verifiedRuleSetRuntimeSettings(dnsFilterRuntimePaths)
        val base = json.parseToJsonElement(baseConfigJson).jsonObject
        val resolvedPrivateDnsState = privateDnsState ?: privateDnsMode?.let(::PrivateDnsState)
        return when (runtimeSettings.traffic.mode) {
            TrafficMode.TUNNEL ->
                assembleTunnel(
                    base = base,
                    settings = runtimeSettings,
                    activePreset = activePreset,
                    privateDnsState = resolvedPrivateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    vpnProtocolHint = vpnProtocolHint,
                    protocolTestTrafficFreeze = protocolTestTrafficFreeze,
                ).withI2pRoutingApplied(i2pSocksPort, resolvedPrivateDnsState)
                    .let { json.encodeToString(JsonObject.serializer(), it) }
            // PROXY mode has no tun, so there is no `.i2p` traffic to divert.
            TrafficMode.PROXY -> assembleProxy(base, runtimeSettings, activePreset, dnsFilterRuntimePaths)
        }
    }

    // Diverts only `.i2p` names to the local i2pd proxy (see RuntimeI2pOutbounds), applied as a
    // final pass on the assembled JsonObject before the single encode — no re-encode round-trip.
    private fun JsonObject.withI2pRoutingApplied(
        i2pSocksPort: Int?,
        privateDnsState: PrivateDnsState?,
    ): JsonObject {
        i2pSocksPort ?: return this
        return withI2pRouting(i2pSocksPort, privateDnsState?.mode)
    }

    fun assembleLocalGuard(
        settings: Settings,
        mode: LocalGuardMode,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        // DNS guard under always-on-VPN lockdown: narrow DNS-only routes would let the SYSTEM
        // block everything else, so capture the full device and forward non-DNS traffic direct.
        dnsGuardFullCapture: Boolean = false,
        // "Allow connections outside the tunnel": the firewall tun diverts `.i2p` to the local
        // i2pd proxy exactly like the profile tunnel does, so i2p works without a VPN.
        i2pSocksPort: Int? = null,
    ): String {
        val runtimeSettings = settings.verifiedRuleSetRuntimeSettings(dnsFilterRuntimePaths)
        // A transparent firewall raised only by I2P must not touch traffic: no DNS hijack, no app
        // blocking. Only the `.i2p` diversion applied later carries anything.
        val transparentI2pGuard = runtimeSettings.i2pRaisesLocalGuard()
        val localDnsCaptureEnabled = !transparentI2pGuard && runtimeSettings.localGuardDnsCaptureEnabled(mode)
        // System-DNS-replacement mode resolves with the user's chosen provider as configured; the
        // firewall guard's incidental DNS capture still upgrades a plain resolver to DoH so ISP
        // interception cannot rewrite it behind the user's back.
        val dnsSettings =
            runtimeSettings.dns
                .localGuardVerifiedRuleSetSettings(dnsFilterRuntimePaths)
                .localGuardDnsSettings(
                    forcePublicDoH = localDnsCaptureEnabled && !runtimeSettings.dns.replaceSystemDns,
                )
        val dns =
            buildFoxholeDnsConfig(
                strategy = runtimeSettings.traffic.domainStrategy.configValue,
                dnsSettings = dnsSettings,
                privateDnsState = null,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                finalTag = DNS_REMOTE_TAG,
                includeRemote = true,
                remoteDetourTag = null,
            )
        val route =
            buildJsonObject {
                val rules =
                    buildJsonArray {
                        // App-block rules go FIRST (first match wins), like patchRoute: otherwise
                        // a blocked app's port-53 traffic matches hijack-dns first and keeps a
                        // working resolver — a DNS-exfiltration channel out of a no-network app.
                        // `transparentI2pGuard` belongs here as much as it does to the DNS gate
                        // above, and its absence was the second half of a promise kept only
                        // halfway: a guard raised by I2P alone announced itself as touching
                        // nothing and still emitted `package_name -> block`. The state is
                        // ordinary — pinning an app to BLOCK arms `blockAppsAlways`, turning the
                        // firewall off leaves it armed, and engaging I2P then captures the whole
                        // device — so "enable I2P" quietly re-armed a firewall the user had
                        // switched off.
                        if (
                            !transparentI2pGuard &&
                            mode != LocalGuardMode.DNS &&
                            runtimeSettings.expert.blockAppsAlways
                        ) {
                            buildAppRouteRules(runtimeSettings.expert).forEach(::add)
                        }
                        if (localDnsCaptureEnabled) {
                            add(blockPrivateDnsValidationRule())
                            hijackDnsRules().forEach(::add)
                        }
                    }
                val ruleSets =
                    mergedRouteRuleSets(
                        source = buildJsonObject { },
                        dnsSettings = dnsSettings,
                        dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    )
                put("rules", rules)
                ruleSets?.let { put("rule_set", it) }
                put("final", "direct")
                put("default_domain_resolver", DNS_REMOTE_TAG)
                put("auto_detect_interface", true)
                put("override_android_vpn", false)
            }
        val guardConfig =
            buildJsonObject {
                putJsonArray("inbounds") {
                    add(localGuardTunInbound(runtimeSettings, mode, selfPackageName, dnsGuardFullCapture))
                }
                putJsonArray("outbounds") {
                    add(
                        buildJsonObject {
                            put("type", "direct")
                            put("tag", "direct")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "block")
                            put("tag", "block")
                        }
                    )
                }
                put("dns", dns)
                put("route", route)
                putJsonObject("log") {
                    put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                    put("timestamp", true)
                }
            }.withI2pRoutingApplied(i2pSocksPort, null)
        return json.encodeToString(JsonObject.serializer(), guardConfig)
    }

    private fun Settings.localGuardDnsCaptureEnabled(mode: LocalGuardMode): Boolean =
        dns.replaceSystemDns ||
            (
                mode != LocalGuardMode.DNS &&
                    dns.interceptDnsRequests
                )

    fun assembleTorOnly(
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
        torRuntimePaths: TorRuntimePaths,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        i2pSocksPort: Int? = null,
    ): String {
        validate(settings.expert)
        val runtimeSettings = settings.verifiedRuleSetRuntimeSettings(dnsFilterRuntimePaths)
        require(runtimeSettings.privacyRoute.enabled) { "TOR route is disabled" }
        require(
            runtimeSettings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS ||
                runtimeSettings.expert.torLanePackages().isNotEmpty(),
        ) { "direct TOR route has no selected apps" }
        val dns =
            buildFoxholeDnsConfig(
                strategy = runtimeSettings.traffic.domainStrategy.configValue,
                dnsSettings = runtimeSettings.dns,
                privateDnsState = privateDnsState ?: privateDnsMode?.let(::PrivateDnsState),
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                // The tor-only "proxy" outbound is Tor SOCKS (no UDP): resolve names via DoH over Tor
                // so every app routed through the tunnel can resolve (else ERR_NAME_NOT_RESOLVED).
                torSocksDetour = true,
            )
        val route =
            patchTorOnlyRoute(
                dns = dns,
                activePreset = activePreset,
                settings = runtimeSettings,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
            )
        val assembled =
            buildJsonObject {
                putJsonArray("inbounds") {
                    add(torOnlyTunInbound(runtimeSettings, selfPackageName))
                    add(runtimeLoopbackProxyInbound(runtimeSettings.expert.localSurfaces))
                    // Same as the tunnel path: the engine takes one non-tun inbound, and the LAN
                    // listener is owned by the session-scoped JNI supervisor, not by this config.
                    buildLocalSurfaceInbounds(
                        runtimeSettings.expert.localSurfaces,
                        includeLocalProxy = false,
                        lanListenAddress = null,
                    ).forEach(::add)
                }
                putJsonArray("outbounds") {
                    add(torPrimaryFoxCoreOutbound(torRuntimePaths))
                    add(
                        buildJsonObject {
                            put("type", "direct")
                            put("tag", "direct")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "block")
                            put("tag", "block")
                        }
                    )
                }
                put("dns", dns)
                put("route", route)
                putJsonObject("log") {
                    put("level", FOXHOLE_RUNTIME_LOG_LEVEL)
                    put("timestamp", true)
                }
            }.withI2pRouting(
                i2pSocksPort,
                (privateDnsState ?: privateDnsMode?.let(::PrivateDnsState))?.mode,
            )
        return json.encodeToString(JsonObject.serializer(), assembled)
    }

    private fun assembleTunnel(
        base: JsonObject,
        settings: Settings,
        activePreset: RoutingPreset?,
        privateDnsState: PrivateDnsState?,
        torRuntimePaths: TorRuntimePaths?,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
        vpnProtocolHint: ProtocolHint?,
        protocolTestTrafficFreeze: Boolean,
    ): JsonObject {
        val tunInbound = base["inbounds"]?.jsonArray?.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.content == "tun"
        }?.jsonObject
            ?: error("base config must define a tun inbound")
        val privacyRouteActive = settings.isTorPrivacyRouteActive(vpnProtocolHint)
        val splitPlan = buildSplitPlan(settings, privacyRouteActive, selfPackageName)
        requireBuildableTorScope(privacyRouteActive, splitPlan)
        val runtimeBase =
            base
                .withTcpReliabilityOutbounds()
                .withDirectOutboundIfNeeded(splitPlan)
                .withTorPrivacyRouteOutbound(
                    paths = torRuntimePaths.takeIf { privacyRouteActive },
                    detourThroughVpn = !settings.privacyRoute.bypassVpnTunnel,
                )
        val patchedTun =
            patchTunInbound(
                tunInbound = tunInbound,
                dnsSettings = settings.dns,
                expert = settings.expert,
                mtu = effectiveTunMtu(runtimeBase, settings.traffic.mtu),
                stack = effectiveTunnelTunStack(settings.traffic.tunStack),
            )
        val inbounds =
            buildJsonArray {
                add(patchedTun)
                add(runtimeLoopbackProxyInbound(settings.expert.localSurfaces))
                // `lanListenAddress = null` deliberately: the LAN listener is NOT config any more.
                // It is raised on the live session through nativeStartLanProxy (see
                // LanProxyController) and torn down with it, which is also the only way its real
                // state can reach the UI. Emitting it here as well would put a second non-tun
                // inbound in the document, and FoxCoreTunTranslator allows exactly one — the user
                // saw "profile is invalid" on Wi-Fi and nowhere else.
                buildLocalSurfaceInbounds(
                    settings.expert.localSurfaces,
                    includeLocalProxy = false,
                    lanListenAddress = null,
                ).forEach(::add)
            }
        val patchedDns =
            patchDns(
                existing = runtimeBase["dns"]?.jsonObject,
                base = runtimeBase,
                traffic = settings.traffic,
                dnsSettings = settings.dns,
                privateDnsState = privateDnsState,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                privacyRouteActive = privacyRouteActive,
                vpnProtocolHint = vpnProtocolHint,
            )
        val patchedRoute =
            patchRoute(
                base = runtimeBase,
                existing = runtimeBase["route"]?.jsonObject,
                dns = patchedDns,
                activePreset = activePreset,
                settings = settings,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                privacyRouteActive = privacyRouteActive,
                splitPlan = splitPlan,
                vpnProtocolHint = vpnProtocolHint,
                protocolTestTrafficFreeze = protocolTestTrafficFreeze,
                selfPackageName = selfPackageName,
            )
        val patchedExperimental =
            patchExperimental(
                runtimeBase["experimental"]?.jsonObject,
                settings.expert.localSurfaces,
            )

        val tunnelConfig =
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
            }
        return tunnelConfig
    }

    private fun assembleProxy(
        base: JsonObject,
        settings: Settings,
        activePreset: RoutingPreset?,
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    ): String {
        val localSurfaces = settings.expert.localSurfaces
        val localSurfaceInbounds =
            buildLocalSurfaceInbounds(
                localSurfaces,
                includeLocalProxy = true,
                lanListenAddress = localSurfaces.currentLanListenAddressOrNull(),
            )
        val runtimeProxyInbound = runtimeLoopbackProxyInbound(localSurfaces)
        val collidingLocalProxyInbound =
            localSurfaceInbounds.firstOrNull { inbound -> inbound.sameListenEndpointAs(runtimeProxyInbound) }
        val includeRuntimeProxyInbound = collidingLocalProxyInbound == null
        val runtimeProxyRouteInboundTag =
            collidingLocalProxyInbound?.stringField("tag") ?: RUNTIME_LOOPBACK_PROXY_INBOUND_TAG
        val inbounds =
            buildJsonArray {
                if (includeRuntimeProxyInbound) {
                    add(runtimeProxyInbound)
                }
                localSurfaceInbounds.forEach(::add)
            }
        val runtimeBase = base.withTcpReliabilityOutbounds()
        val patchedDns =
            patchDns(runtimeBase["dns"]?.jsonObject, runtimeBase, settings.traffic, settings.dns, dnsFilterRuntimePaths)
        val patchedRoute =
            patchProxyRoute(
                existing = runtimeBase["route"]?.jsonObject,
                dns = patchedDns,
                activePreset = activePreset,
                settings = settings,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                runtimeProxyRouteInboundTag = runtimeProxyRouteInboundTag,
            )
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
        privateDnsState: PrivateDnsState? = null,
        protocolTestTrafficFreeze: Boolean = false,
    ): Int {
        val settingsFingerprint =
            RuntimeFingerprintSettings(
                traffic = settings.traffic,
                dns = settings.dns,
                privacyRoute = settings.privacyRoute.runtimeFingerprintSettings(),
                i2p = settings.i2p,
                expert = settings.expert.runtimeFingerprintSettings(),
                webAppsEnabled = settings.webApps.enabled,
            ).hashCode()
        val lanFingerprint =
            settings.expert.localSurfaces
                .takeIf { it.allowLanAccess }
                ?.let { lanProxyAddressProvider.currentWifiIpv4Address() }
                ?.hashCode()
                ?: 0
        val dnsFingerprint = (privateDnsState ?: privateDnsMode?.let(::PrivateDnsState))?.hashCode() ?: 0
        val runtimeFingerprint =
            (((settingsFingerprint * 31) + (activePreset?.hashCode() ?: 0)) * 31 + lanFingerprint) * 31 + dnsFingerprint
        return if (protocolTestTrafficFreeze) {
            runtimeFingerprint * 31 + true.hashCode()
        } else {
            runtimeFingerprint
        }
    }

    /**
     * Fingerprint for a local-guard session. The general [runtimeFingerprint] zeroes fields that
     * never change a profile-tunnel config (firewallEnabled, blockAppsAlways,
     * networkActivityLogging) — but the guard config DOES depend on them. Folding them back in,
     * plus the resolved [mode], makes toggling them on a live firewall actually restart it.
     */
    fun localGuardRuntimeFingerprint(
        settings: Settings,
        mode: LocalGuardMode,
        activePreset: RoutingPreset? = null,
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
    ): Int {
        val base = runtimeFingerprint(settings, activePreset, privateDnsMode, privateDnsState)
        val guardExtra =
            listOf(
                mode,
                settings.expert.firewallEnabled,
                settings.expert.blockAppsAlways,
                settings.expert.networkActivityLogging,
            ).hashCode()
        return base * 31 + guardExtra
    }

    fun redactedRuntimeShape(configJson: String): String =
        runCatching {
            val root = json.parseToJsonElement(configJson).jsonObject
            val outbound = root.primaryProxyOutbound()
            val runtimeInbound = root.runtimeProxyInbound()
            val tls = outbound?.get("tls")?.jsonObject
            val utls = tls?.get("utls")?.jsonObject
            val reality = tls?.get("reality")?.jsonObject
            val transport = outbound?.get("transport")?.jsonObject
            val route = root["route"]?.jsonObject
            val dnsServers = root["dns"]?.jsonObject?.get("servers")?.jsonArray.orEmpty()
            val lanInboundCount = root.localLanProxyInboundCount()
            listOf(
                "outbound_shape",
                "type=${outbound?.stringField("type") ?: "missing"}",
                "selector=${root.hasProxySelector()}",
                "transport=${transport?.stringField("type") ?: "tcp"}",
                "tls=${tls?.enabledField(default = true) == true}",
                "utls=${utls?.enabledField(default = true) == true}",
                "fp=${utls?.stringField("fingerprint")?.takeIf(String::isNotBlank) ?: "none"}",
                "reality=${reality?.enabledField(default = true) == true}",
                "flow=${outbound?.containsKey("flow") == true}",
                "packet=${outbound?.stringField("packet_encoding") ?: "default"}",
                "network=${outbound?.networkField() ?: "default"}",
                "bind_interface=${outbound?.stringField("bind_interface") ?: "none"}",
                "runtime_inbound=${runtimeInbound?.stringField("type") ?: "missing"}",
                "lan_inbounds=$lanInboundCount",
                "dns_remote=${dnsServers.any { it.jsonObject.stringField("tag") == DNS_REMOTE_TAG }}",
                "route_final=${route?.stringField("final") ?: "missing"}",
                "route_auto_detect=${route?.stringField("auto_detect_interface") ?: "missing"}",
                "route_default_interface=${route?.stringField("default_interface") ?: "none"}",
                "route_udp_block=${route?.hasUdpBlockRule() == true}",
                "route_udp_reject=${route?.hasUdpRejectRule() == true}",
                "tor=${root.torPlacementShape()}",
            ).joinToString(" ")
        }.getOrElse { error ->
            "outbound_shape unavailable error=${error.javaClass.simpleName}"
        }

    // Ground-truth Tor placement from the assembled config: `in_vpn` = Tor detours through the
    // tunnel (bridges dropped), `direct` = Tor dials from the device (bridges allowed). Lets a
    // stuck Tor-over-VPN bootstrap be told apart from a bypass placement in device logs.
    private fun JsonObject.torPlacementShape(): String {
        val tor =
            this["outbounds"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "tor" }
                ?: return "off"
        val useBridges = tor["torrc"]?.jsonObject?.get("UseBridges")?.jsonPrimitive?.contentOrNull ?: "default"
        return if (tor.containsKey("detour")) {
            "in_vpn(detour=${tor["detour"]?.jsonPrimitive?.contentOrNull},bridges=$useBridges)"
        } else {
            "direct(bridges=$useBridges)"
        }
    }

    private fun JsonObject.localLanProxyInboundCount(): Int =
        this["inbounds"]
            ?.jsonArray
            ?.count { inbound -> inbound.jsonObject.stringField("tag")?.endsWith("-in-lan") == true }
            ?: 0

    private fun JsonObject.hasProxySelector(): Boolean =
        this["outbounds"]?.jsonArray.orEmpty().any { outbound ->
            val value = outbound.jsonObject
            value.stringField("tag") == "proxy" && value.stringField("type") == "selector"
        }

    private fun JsonObject.runtimeProxyInbound(): JsonObject? =
        this["inbounds"]?.jsonArray.orEmpty().firstOrNull { inbound ->
            inbound.jsonObject.stringField("tag") == RUNTIME_LOOPBACK_PROXY_INBOUND_TAG
        }?.jsonObject

    private fun JsonObject.sameListenEndpointAs(other: JsonObject): Boolean =
        stringField("listen") == other.stringField("listen") &&
            stringField("listen_port") == other.stringField("listen_port")

    private fun JsonObject.hasUdpBlockRule(): Boolean =
        this["rules"]?.jsonArray.orEmpty().any { rule ->
            val value = rule.jsonObject
            value.stringField("network") == "udp" && value.stringField("outbound") == "block"
        }

    private fun JsonObject.hasUdpRejectRule(): Boolean =
        this["rules"]?.jsonArray.orEmpty().any { rule ->
            val value = rule.jsonObject
            value.stringField("network") == "udp" && value.stringField("action") == "reject"
        }

    fun validate(expert: ExpertSettings) {
        val enabledPorts =
            buildMap<String, String> {
                addPort(expert.localSurfaces.proxySurface(), "Local Proxy")
                addPort(expert.localSurfaces.clashApi, "Clash API")
                addPort(expert.localSurfaces.v2RayApi, "V2Ray API")
            }
        require(
            enabledPorts.size == enabledPorts.keys.distinct().size
        ) { "enabled local surfaces must not share the same endpoint" }
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

    // Bridge bookkeeping has no effect on the assembled config — stripping it keeps the 12h
    // bridge refresh from silently flipping the fingerprint and prompting a reload.
    // bridgesEnabled/bridgeTransport DO change the generated torrc, so they stay.
    private fun PrivacyRouteSettings.runtimeFingerprintSettings(): PrivacyRouteSettings =
        copy(
            bridgesAutoUpdate = false,
            bridgesUseFoxholeSource = false,
            bridgesUpdatedAt = null,
            bridgesCheckedAt = null,
            bridgesLastUpdateSuccess = null,
        )

    private fun ExpertSettings.runtimeFingerprintSettings(): ExpertSettings {
        // Lanes outside their effect are erased so irrelevant edits don't force a reload: the VPN
        // lane only shapes a non-FULL split, the block lane only while armed. TOR and EXCLUDE
        // always shape the config (tor route rules / tun bypass), so they always count.
        val runtimeAssignments =
            appAssignments.filterValues { lane ->
                when (lane) {
                    AppTunnelLane.TOR, AppTunnelLane.EXCLUDE -> true
                    AppTunnelLane.VPN -> perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL
                    AppTunnelLane.BLOCK -> blockedPackagesEnabled
                }
            }
        return copy(
            unlockedAt = null,
            warningAcknowledgedAt = null,
            blockScreenshots = false,
            firewallEnabled = false,
            networkActivityLogging = false,
            diagnosticsRetention = DiagnosticsRetention.HOURS_24,
            allowInsecureTls = true,
            appAssignments = runtimeAssignments,
            blockedPackagesEnabled = runtimeAssignments.containsValue(AppTunnelLane.BLOCK),
            blockAppsAlways = false,
        )
    }

    private fun patchDns(
        existing: JsonObject?,
        base: JsonObject,
        traffic: TrafficSettings,
        dnsSettings: DnsSettings = DnsSettings(),
        dnsFilterRuntimePaths: DnsFilterRuntimePaths? = null,
        privateDnsState: PrivateDnsState? = null,
        privacyRouteActive: Boolean = false,
        vpnProtocolHint: ProtocolHint? = null,
    ): JsonObject {
        val effectiveStrategy =
            when {
                traffic.preferIpv6 && traffic.domainStrategy == com.foxhole.core.model.DomainStrategy.PREFER_IPV4 ->
                    com.foxhole.core.model.DomainStrategy.PREFER_IPV6
                else -> traffic.domainStrategy
            }
        if (existing == null || !existing.hasDnsServers() || isFoxholeManagedDns(existing)) {
            val wireGuardDnsServers = wireGuardDnsServers(existing)
            val dnsRoute =
                managedDnsRoute(
                    base = base,
                    dnsSettings = dnsSettings,
                    privacyRouteActive = privacyRouteActive,
                    wireGuardDnsPresent = wireGuardDnsServers.isNotEmpty(),
                )
            // A plain UDP resolver forced through a TCP-only proxy outbound would silently fail; the
            // DNS builder upgrades it to DoH in that case so DNS keeps working inside the tunnel.
            val tunnelCarriesUdp = !shouldBlockUnsupportedUdp(base, vpnProtocolHint)
            return buildFoxholeDnsConfig(
                strategy = effectiveStrategy.configValue,
                dnsSettings = dnsSettings,
                privateDnsState = privateDnsState,
                dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                extraServers = wireGuardDnsServers,
                finalTag = dnsRoute.finalTag,
                includeRemote = dnsRoute.includeRemote,
                remoteDetourTag = if (privacyRouteActive) TOR_OVER_VPN_OUTBOUND_TAG else "proxy",
                tunnelCarriesUdp = tunnelCarriesUdp,
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

    private fun LocalSurfaceSettings.currentLanListenAddressOrNull(): String? =
        lanProxyAddressProvider.currentWifiIpv4Address()?.takeIf { allowLanAccess }

    private fun blockPrivateDnsValidationRule(): JsonObject =
        buildJsonObject {
            put("network", "tcp")
            put("port", 853)
            put("action", "route")
            put("outbound", "block")
        }
}
