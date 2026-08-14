package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.withLane
import org.junit.Test

/**
 * Split, Tor and the firewall each emit their own package rules, so only their combination produces
 * a configuration the core cannot express — the user saw "the profile configuration is invalid" with
 * nothing connecting at all.
 *
 * The assertion runs the real [FoxCorePolicyTranslator] over the assembled config rather than
 * re-deriving its rules: an approximation of what the translator accepts is exactly what sent an
 * earlier attempt at this fix down the wrong path.
 */
internal class RuntimeConfigAssemblerPolicyConflictTest : RuntimeConfigAssemblerTestSupport() {
    private fun settings(torApps: List<String>): Settings {
        val expert =
            ExpertSettings(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                blockedPackagesEnabled = true,
            ).withLane(AppTunnelLane.VPN, listOf("com.example.vpnapp"))
                .withLane(AppTunnelLane.TOR, torApps)
                .withLane(AppTunnelLane.BLOCK, listOf("com.example.blockedapp"))
        return Settings(
            expert = expert,
            privacyRoute =
            PrivacyRouteSettings(
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                scope = PrivacyRouteScope.SELECTED_APPS,
                blockAppsWhenTorUnavailable = true,
            ),
        )
    }

    private fun translate(settings: Settings) {
        val assembled =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = settings,
                    activePreset = null,
                ),
            )
        FoxCorePolicyTranslator.translate(
            root = assembled,
            primaryIsPacketTunnel = false,
            overlays = setOf(FoxCoreOverlay.TOR),
            expectedRevision = null,
        )
    }

    @Test
    fun `split plus tor plus firewall is representable with one tor app`() {
        translate(settings(listOf("com.example.torapp")))
    }

    @Test
    fun `split plus tor plus firewall is representable with several tor apps`() {
        translate(
            settings(
                listOf("com.example.torapp", "com.example.torapp2", "com.example.torapp3"),
            ),
        )
    }
}
