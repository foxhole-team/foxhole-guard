package com.foxhole.guard.core.settings

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.i2pRuntimeActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartLeavesDisabledComponentsOffTest {

    @Test
    fun `a disabled component stays disabled across a start`() {
        val off = Settings(
            i2p = I2pSettings(enabled = false, engaged = false),
            expert = ExpertSettings(firewallEnabled = false),
        )

        RoutingModePreset.entries.forEach { preset ->
            val started = applyRoutingModePresetTo(
                current = off,
                preset = preset,
                scope = PrivacyRouteScope.ALL_APPS,
            )

            assertFalse("$preset enabled the I2P module", started.i2p.enabled)
            assertFalse("$preset engaged the I2P router", started.i2p.engaged)
            assertFalse("$preset raised the firewall", started.expert.firewallEnabled)
            assertFalse("$preset made I2P live", started.i2pRuntimeActive())
        }
    }

    @Test
    fun `a permitted but disengaged router is not re-engaged by a start`() {
        val paused = Settings(i2p = I2pSettings(enabled = true, engaged = false))

        RoutingModePreset.entries.forEach { preset ->
            val started = applyRoutingModePresetTo(
                current = paused,
                preset = preset,
                scope = PrivacyRouteScope.ALL_APPS,
            )

            assertFalse("$preset re-engaged a paused router", started.i2p.engaged)
            assertFalse("$preset made a paused router live", started.i2pRuntimeActive())
        }
    }

    @Test
    fun `components the user left on survive a start unchanged`() {
        val on = Settings(
            i2p = I2pSettings(enabled = true, engaged = true),
            expert = ExpertSettings(firewallEnabled = true),
        )

        RoutingModePreset.entries.forEach { preset ->
            val started = applyRoutingModePresetTo(
                current = on,
                preset = preset,
                scope = PrivacyRouteScope.ALL_APPS,
            )

            assertEquals("$preset changed the I2P block", on.i2p, started.i2p)
            assertTrue("$preset dropped the firewall", started.expert.firewallEnabled)
            assertTrue("$preset stopped I2P", started.i2pRuntimeActive())
        }
    }

    @Test
    fun `the home start button writes no module engagement`() {
        val buttons = source("main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt")
        val press = buttons.substringAfter("val onPrimaryPressed").substringBefore("CliButton(")

        assertFalse(press.contains("applyI2pEngagement"))
        assertFalse(buttons.contains("applyI2pEngagement"))
        assertFalse(buttons.contains("i2pWouldRaiseTransparentGuard"))
        assertFalse(buttons.contains("rememberI2pStartGate"))
        assertTrue(press.contains("performPrimaryAction("))
    }

    private fun source(relative: String): String =
        listOf(
            File("src", relative),
            File("app/src", relative),
            File("../app/src", relative),
        ).first(File::isFile).readText()
}
