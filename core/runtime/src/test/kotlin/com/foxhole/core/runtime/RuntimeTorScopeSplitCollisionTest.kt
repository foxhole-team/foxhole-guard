package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.withLane
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeTorScopeSplitCollisionTest : RuntimeConfigAssemblerTestSupport() {
    private val translator = FoxCoreConfigTranslator()

    private val torPaths =
        TorRuntimePaths(
            dataDirectory = "/data/user/0/com.foxhole.guard/files/tor",
            bridges = listOf("obfs4 192.0.2.83:80 0bac39417268b96b9f514ef763fa6fba1a788956 cert=contract"),
            pluggableTransports =
            listOf(
                TorPluggableTransport(
                    protocols = listOf("obfs4"),
                    executablePath = "/data/app/liblyrebird.so",
                    arguments = listOf("-enableLogging=false"),
                ),
            ),
        )

    private fun settings(
        routingMode: PerAppRoutingMode,
        scope: PrivacyRouteScope,
    ): Settings =
        Settings(
            expert =
            ExpertSettings(perAppRoutingMode = routingMode)
                .withLane(AppTunnelLane.TOR, listOf("com.example.t1", "com.example.t2"))
                .withLane(AppTunnelLane.VPN, listOf("com.example.v1", "com.example.v2", "com.example.v3")),
            privacyRoute =
            PrivacyRouteSettings(
                permitted = true,
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                scope = scope,
            ),
        )

    private fun assemble(settings: Settings): String =
        assembler.assemble(
            baseConfigJson = vlessBaseConfig(),
            settings = settings,
            activePreset = null,
            torRuntimePaths = torPaths,
            vpnProtocolHint = ProtocolHint.VLESS,
        )

    private fun translate(settings: Settings) {
        translator.translate(
            VpnSession(
                profileId = 1L,
                profileName = "owner",
                protocolHint = ProtocolHint.VLESS,
                configJson = assemble(settings),
                correlationId = "tor-scope-split",
                torActive = true,
            ),
        )
    }

    @Test
    fun `whole-device tor over an include split is refused before a config exists`() {
        val failure =
            runCatching { assemble(settings(PerAppRoutingMode.INCLUDE_SELECTED_APPS, PrivacyRouteScope.ALL_APPS)) }
                .exceptionOrNull()

        assertTrue(
            "expected a named refusal, got $failure",
            failure is RuntimeConfigUnsupportedException,
        )
        assertEquals(
            TOR_ALL_APPS_NEEDS_FULL_TUNNEL_MARKER,
            (failure as RuntimeConfigUnsupportedException).marker,
        )
    }

    @Test
    fun `the settings predicate names the same pair the assembler refuses`() {
        assertTrue(
            settings(PerAppRoutingMode.INCLUDE_SELECTED_APPS, PrivacyRouteScope.ALL_APPS)
                .torAllAppsCollidesWithVpnIncludeSplit(),
        )

        assertFalse(
            Settings(
                expert = ExpertSettings(perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS),
                privacyRoute =
                PrivacyRouteSettings(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.ALL_APPS,
                ),
            ).torAllAppsCollidesWithVpnIncludeSplit(),
        )
        assertFalse(
            settings(PerAppRoutingMode.EXCLUDE_SELECTED_APPS, PrivacyRouteScope.ALL_APPS)
                .torAllAppsCollidesWithVpnIncludeSplit(),
        )
        assertFalse(
            settings(PerAppRoutingMode.INCLUDE_SELECTED_APPS, PrivacyRouteScope.SELECTED_APPS)
                .torAllAppsCollidesWithVpnIncludeSplit(),
        )
    }

    @Test
    fun `every other tor scope and split pairing still reaches the core`() {
        listOf(
            PerAppRoutingMode.FULL_TUNNEL to PrivacyRouteScope.ALL_APPS,
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS to PrivacyRouteScope.ALL_APPS,
            PerAppRoutingMode.FULL_TUNNEL to PrivacyRouteScope.SELECTED_APPS,
            PerAppRoutingMode.INCLUDE_SELECTED_APPS to PrivacyRouteScope.SELECTED_APPS,
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS to PrivacyRouteScope.SELECTED_APPS,
        ).forEach { (routingMode, scope) ->
            translate(settings(routingMode, scope))
        }
    }

    private fun vlessBaseConfig(): String =
        baseConfigWithOutbounds(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "vless")
                        put("tag", "node")
                        put("server", "203.0.113.10")
                        put("server_port", 443)
                        put("uuid", "11111111-1111-1111-1111-111111111111")
                        put(
                            "tls",
                            buildJsonObject {
                                put("enabled", true)
                                put("server_name", "edge.example")
                            },
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "selector")
                        put("tag", "proxy")
                        put("default", "node")
                        put("outbounds", buildJsonArray { add(JsonPrimitive("node")) })
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "block")
                        put("tag", "block")
                    },
                )
            },
        )
}
