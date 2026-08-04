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
import org.junit.Test

/**
 * Split, the Tor lane and the firewall enabled together: reported from the device as a config the
 * core refuses. With the fail-closed Tor lane armed, the Tor packages are rejected outright, so no
 * split rule may name them — in EXCLUDE mode the selected apps ARE the excluded set, which put one
 * package under both a reject and a direct-route rule (policy_unrepresentable).
 */
internal class FoxCoreSplitTorFirewallTest : RuntimeConfigAssemblerTestSupport() {
    private val translator = FoxCoreConfigTranslator()

    private fun settingsWithAllThree(
        routingMode: PerAppRoutingMode,
        torEngaged: Boolean,
        failClosed: Boolean,
    ): Settings =
        Settings(
            expert =
            ExpertSettings(
                perAppRoutingMode = routingMode,
                blockedPackagesEnabled = true,
            )
                .withLane(AppTunnelLane.TOR, listOf("com.example.tor"))
                .withLane(AppTunnelLane.VPN, listOf("com.example.vpn"))
                .withLane(AppTunnelLane.BLOCK, listOf("com.example.blocked"))
                .withLane(AppTunnelLane.EXCLUDE, listOf("com.example.excluded")),
            privacyRoute =
            PrivacyRouteSettings(
                mode = if (torEngaged) PrivacyRouteMode.TOR_OVER_VPN else PrivacyRouteMode.OFF,
                scope = PrivacyRouteScope.SELECTED_APPS,
                blockAppsWhenTorUnavailable = failClosed,
            ),
        )

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

    private fun translate(settings: Settings) {
        val legacy = assembler.assemble(baseConfigJson = vlessBaseConfig(), settings = settings, activePreset = null)
        translator.translate(
            VpnSession(
                profileId = 1L,
                profileName = "split+tor+firewall",
                protocolHint = ProtocolHint.VLESS,
                configJson = legacy,
                correlationId = "split-tor-firewall",
            ),
        )
    }

    /** Tor disengaged with fail-closed on: the Tor lane is rejected while the split still names apps. */
    @Test
    fun `fail-closed tor lane beside include split translates`() {
        translate(settingsWithAllThree(PerAppRoutingMode.INCLUDE_SELECTED_APPS, torEngaged = false, failClosed = true))
    }

    @Test
    fun `fail-closed tor lane beside exclude split translates`() {
        translate(settingsWithAllThree(PerAppRoutingMode.EXCLUDE_SELECTED_APPS, torEngaged = false, failClosed = true))
    }
}
