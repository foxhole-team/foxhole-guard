package com.foxhole.core.runtime

import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ClashApiSettings
import com.foxhole.core.model.DiagnosticsRetention
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeConfigAssemblerWireguardMtuTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `wireguard endpoint mtu keeps configured tun mtu when missing`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct")
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1500, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu handles uppercase type`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "WireGuard")
                                        put("tag", "wireguard-direct")
                                        put("mtu", 1280)
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1280, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu keeps configured tun mtu without endpoints`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1500, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard selected resolves through the provider wireguard dns`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithWireGuardDns(selectedDefault = "wireguard-direct"),
                    Settings(),
                    null,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val wireGuardDns =
            dns["servers"]!!
                .jsonArray
                .first { server -> server.jsonObject["tag"]!!.jsonPrimitive.content == "dns-wireguard" }
                .jsonObject

        // Provider DNS is the default: the WireGuard-advertised resolver is the final resolver and
        // rides the tunnel (detour=proxy) — queries never fall back to the local system resolver.
        assertEquals("dns-wireguard", dns["final"]!!.jsonPrimitive.content)
        assertEquals("udp", wireGuardDns["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", wireGuardDns["server"]!!.jsonPrimitive.content)
        assertEquals("53", wireGuardDns["server_port"]!!.jsonPrimitive.content)
        assertEquals("proxy", wireGuardDns["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wireguard selected without provider dns toggle uses tunnelled remote resolver`() {
        val settings = Settings(dns = DnsSettings(useVpnProviderDns = false))
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithWireGuardDns(selectedDefault = "wireguard-direct"),
                    settings,
                    null,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val remote =
            dns["servers"]!!
                .jsonArray
                .first { server -> server.jsonObject["tag"]!!.jsonPrimitive.content == "dns-remote" }
                .jsonObject

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non wireguard selected resolves through tunnelled remote with imported wireguard dns present`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithWireGuardDns(selectedDefault = "vless-direct"),
                    Settings(),
                    null,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }

        // A non-WireGuard endpoint advertises no provider resolver, so provider mode falls through
        // to the managed remote resolver — still inside the tunnel, never the local resolver.
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertTrue(servers.contains("dns-wireguard"))
    }

    @Test
    fun `tunnel mode preserves platform http proxy settings`() {
        val config = parse(assembler.assemble(baseConfigWithPlatformHttpProxy(), Settings(), null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val httpProxy = tunInbound["platform"]!!.jsonObject["http_proxy"]!!.jsonObject

        assertEquals("true", httpProxy["enabled"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", httpProxy["server"]!!.jsonPrimitive.content)
        assertEquals("10809", httpProxy["server_port"]!!.jsonPrimitive.content)
        assertFalse(httpProxy["server_port"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `local proxy surfaces require auth and clash api uses shared secret`() {
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.SOCKS5,
                        socks = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10808),
                        clashApi = ClashApiSettings(enabled = true, host = "127.0.0.1", port = 9090),
                        auth =
                        LocalAuthSettings(
                            username = "foxhole-user",
                            password = "foxhole-pass",
                            apiSecret = "foxhole-secret",
                        ),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val socksInbound =
            config["inbounds"]!!
                .jsonArray
                .map { inbound -> inbound.jsonObject }
                .single { inbound -> inbound["tag"]!!.jsonPrimitive.content == "socks-in" }
        val socksUser = socksInbound["users"]!!.jsonArray.first().jsonObject
        val clashApi = config["experimental"]!!.jsonObject["clash_api"]!!.jsonObject

        assertEquals("foxhole-user", socksUser["username"]!!.jsonPrimitive.content)
        assertEquals("foxhole-pass", socksUser["password"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:9090", clashApi["external_controller"]!!.jsonPrimitive.content)
        assertEquals("foxhole-secret", clashApi["secret"]!!.jsonPrimitive.content)
    }

    @Test
    fun `proxy mode omits tun inbound and keeps local proxy surfaces only`() {
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        auth =
                        LocalAuthSettings(
                            username = "foxhole-user",
                            password = "foxhole-pass",
                            apiSecret = "foxhole-secret",
                        ),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray

        assertEquals(1, inbounds.size)
        assertEquals("http", inbounds.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(inbounds.first().jsonObject.containsKey("stack"))
    }

    @Test
    fun `proxy mode does not inject hijack dns rule`() {
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertTrue(rules.none { it.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `proxy auth toggle off omits inbound users`() {
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        auth =
                        LocalAuthSettings(
                            enabled = false,
                            username = "foxhole-user",
                            password = "foxhole-pass",
                            apiSecret = "foxhole-secret",
                        ),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val httpInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertFalse(httpInbound.containsKey("users"))
    }

    @Test
    fun `proxy mode binds lan proxy to wifi only when lan access is enabled`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        allowLanAccess = true,
                        lanProxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        // LAN auth is mandatory, so the LAN inbound is only published with a password.
                        lanAuth = LocalAuthSettings(username = "lan-user", password = "lan-pass"),
                    ),
                ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray
        val listens = inbounds.map { inbound -> inbound.jsonObject["listen"]!!.jsonPrimitive.content }

        assertEquals(2, inbounds.size)
        assertTrue("192.168.1.23" in listens)
    }

    @Test
    fun `lan proxy uses independent auth credentials`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        lanProxyMode = ProxySurfaceMode.HTTP,
                        allowLanAccess = true,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        auth =
                        LocalAuthSettings(
                            username = "local-user",
                            password = "local-pass",
                        ),
                        lanAuth =
                        LocalAuthSettings(
                            username = "lan-user",
                            password = "lan-pass",
                        ),
                    ),
                ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }
        val localUser = inbounds.getValue("http-in")["users"]!!.jsonArray.first().jsonObject
        val lanUser = inbounds.getValue("http-in-lan")["users"]!!.jsonArray.first().jsonObject

        assertEquals("local-user", localUser["username"]!!.jsonPrimitive.content)
        assertEquals("local-pass", localUser["password"]!!.jsonPrimitive.content)
        assertEquals("lan-user", lanUser["username"]!!.jsonPrimitive.content)
        assertEquals("lan-pass", lanUser["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lan proxy forces auth even when the stored lan auth flag is off`() {
        // LAN auth is mandatory (stage2 §8): the LAN leg binds the phone's Wi-Fi address, so an
        // anonymous inbound would relay the owner's VPN/Tor to the whole network. A stale
        // enabled = false payload is ignored — the surface is still raised WITH users. The loopback
        // (http-in) leg keeps honouring its own flag; only the LAN leg is forced.
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        lanProxyMode = ProxySurfaceMode.HTTP,
                        allowLanAccess = true,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        auth =
                        LocalAuthSettings(
                            username = "local-user",
                            password = "local-pass",
                        ),
                        lanAuth =
                        LocalAuthSettings(
                            enabled = false,
                            username = "lan-user",
                            password = "lan-pass",
                        ),
                    ),
                ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }
        val lanUser = inbounds.getValue("http-in-lan")["users"]!!.jsonArray.first().jsonObject

        assertTrue(inbounds.getValue("http-in").containsKey("users"))
        assertTrue(inbounds.getValue("http-in-lan").containsKey("users"))
        assertEquals("lan-user", lanUser["username"]!!.jsonPrimitive.content)
        assertEquals("lan-pass", lanUser["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lan proxy stays down when the lan password is blank`() {
        // Fail-closed: no password means no LAN surface at all — never an anonymous one.
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        lanProxyMode = ProxySurfaceMode.HTTP,
                        allowLanAccess = true,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        lanAuth = LocalAuthSettings(username = "lan-user", password = "   "),
                    ),
                ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tags = config["inbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }

        assertFalse("http-in-lan" in tags)
    }

    @Test
    fun `all proxy surface mode emits mixed inbound`() {
        val settings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.ALL,
                        mixed = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10810),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
        val inbound = inbounds.single { it["tag"]!!.jsonPrimitive.content == "mixed-in" }

        assertTrue(inbounds.any { it["tag"]!!.jsonPrimitive.content == "foxhole-runtime-proxy-in" })
        assertEquals("mixed", inbound["type"]!!.jsonPrimitive.content)
        assertEquals("mixed-in", inbound["tag"]!!.jsonPrimitive.content)
        assertEquals("10810", inbound["listen_port"]!!.jsonPrimitive.content)
        assertFalse(inbound["listen_port"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `runtime fingerprint changes when wifi lan address changes`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    localSurfaces = LocalSurfaceSettings(allowLanAccess = true),
                ),
            )

        val firstFingerprint = lanAwareAssembler.runtimeFingerprint(settings, null)
        lanAddressProvider.address = "192.168.1.44"
        val secondFingerprint = lanAwareAssembler.runtimeFingerprint(settings, null)

        assertNotEquals(firstFingerprint, secondFingerprint)
    }

    @Test
    fun `runtime fingerprint ignores non runtime settings fields`() {
        val runtimeSettings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        auth =
                        LocalAuthSettings(
                            username = "foxhole-user",
                            password = "foxhole-pass",
                            apiSecret = "foxhole-secret",
                        ),
                    ),
                ),
            )
        val uiOnlyChanges =
            runtimeSettings.copy(
                ui = runtimeSettings.ui.copy(locale = AppLocale.RU, showExpertSettings = false),
                connection =
                runtimeSettings.connection.copy(
                    autoReconnect = false,
                    autoStartOnBoot = true,
                    ipInfoEndpoint = "https://ifconfig.co/json",
                ),
                profileTrafficTotals =
                listOf(
                    ProfileTrafficTotal(
                        profileId = 7L,
                        profileName = "Gaming",
                        protocolHint = ProtocolHint.VLESS,
                        rxTotalBytes = 1024L,
                        txTotalBytes = 2048L,
                        updatedAt = 12345L,
                    ),
                ),
                usageTrackingStartedAt = 12345L,
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(uiOnlyChanges, null),
        )
    }

    @Test
    fun `runtime fingerprint ignores non runtime expert metadata`() {
        val runtimeSettings =
            Settings(
                expert =
                ExpertSettings(
                    unlockedAt = 1L,
                    warningAcknowledgedAt = 2L,
                    blockScreenshots = true,
                    networkActivityLogging = false,
                    diagnosticsRetention = DiagnosticsRetention.HOURS_6,
                    sniff = true,
                ),
            )
        val metadataOnlyChanges =
            runtimeSettings.copy(
                expert =
                runtimeSettings.expert.copy(
                    unlockedAt = 99L,
                    warningAcknowledgedAt = 100L,
                    blockScreenshots = false,
                    networkActivityLogging = true,
                    diagnosticsRetention = DiagnosticsRetention.DAYS_14,
                ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(metadataOnlyChanges, null),
        )
    }

    @Test
    fun `runtime fingerprint ignores selected packages when split tunnel is off`() {
        val runtimeSettings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                    appAssignments = emptyMap(),
                ),
            )
        val selectedAppsPrepared =
            runtimeSettings.copy(
                expert =
                runtimeSettings.expert.copy(
                    appAssignments = mapOf("com.example.browser" to AppTunnelLane.VPN),
                ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(selectedAppsPrepared, null),
        )
    }

    @Test
    fun `runtime fingerprint changes for selected packages when split tunnel is on`() {
        val runtimeSettings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments = mapOf("com.example.browser" to AppTunnelLane.VPN),
                ),
            )
        val selectedAppsChanged =
            runtimeSettings.copy(
                expert =
                runtimeSettings.expert.copy(
                    appAssignments =
                    mapOf(
                        "com.example.browser" to AppTunnelLane.VPN,
                        "com.example.chat" to AppTunnelLane.VPN,
                    ),
                ),
            )

        assertNotEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(selectedAppsChanged, null),
        )
    }

    @Test
    fun `runtime fingerprint ignores blocked packages when blocking is off`() {
        val runtimeSettings =
            Settings(
                expert =
                ExpertSettings(
                    appAssignments = emptyMap(),
                    blockedPackagesEnabled = false,
                ),
            )
        val blockedAppsPrepared =
            runtimeSettings.copy(
                expert =
                runtimeSettings.expert.copy(
                    appAssignments = mapOf("com.example.chat" to AppTunnelLane.BLOCK),
                    blockedPackagesEnabled = false,
                ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(blockedAppsPrepared, null),
        )
    }

    @Test
    fun `local guard fingerprint changes when persistent blocking toggles`() {
        val base =
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    appAssignments = mapOf("com.example.chat" to AppTunnelLane.BLOCK),
                    blockedPackagesEnabled = true,
                    blockAppsAlways = false,
                ),
            )
        val persistentOn = base.copy(expert = base.expert.copy(blockAppsAlways = true))

        // The general fingerprint (used by the profile tunnel) deliberately ignores blockAppsAlways…
        assertEquals(
            assembler.runtimeFingerprint(base, null),
            assembler.runtimeFingerprint(persistentOn, null),
        )
        // …but the guard fingerprint must react, or the toggle no-ops on a live firewall.
        assertNotEquals(
            assembler.localGuardRuntimeFingerprint(base, LocalGuardMode.FIREWALL),
            assembler.localGuardRuntimeFingerprint(persistentOn, LocalGuardMode.FIREWALL),
        )
    }

    @Test
    fun `local guard fingerprint changes when activity logging toggles`() {
        val base = Settings(expert = ExpertSettings(firewallEnabled = true, networkActivityLogging = false))
        val loggingOn = base.copy(expert = base.expert.copy(networkActivityLogging = true))

        assertNotEquals(
            assembler.localGuardRuntimeFingerprint(base, LocalGuardMode.FIREWALL),
            assembler.localGuardRuntimeFingerprint(loggingOn, LocalGuardMode.FIREWALL),
        )
    }

    @Test
    fun `local guard fingerprint changes with the guard mode`() {
        val settings =
            Settings(
                expert = ExpertSettings(firewallEnabled = true),
                dns = DnsSettings(replaceSystemDns = true),
            )

        assertNotEquals(
            assembler.localGuardRuntimeFingerprint(settings, LocalGuardMode.FIREWALL),
            assembler.localGuardRuntimeFingerprint(settings, LocalGuardMode.DNS),
        )
    }
}
