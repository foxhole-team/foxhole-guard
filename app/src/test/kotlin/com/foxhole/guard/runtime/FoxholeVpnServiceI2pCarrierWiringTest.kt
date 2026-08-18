package com.foxhole.guard.runtime

import com.foxhole.core.model.I2pSettings
import com.foxhole.core.runtime.I2pAttachment
import com.foxhole.core.runtime.I2pCarrierState
import com.foxhole.core.runtime.I2pCarrierStep
import com.foxhole.core.runtime.I2pTunnelTransition
import com.foxhole.core.runtime.i2pCarrierPlan
import com.foxhole.core.runtime.userEngaged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoxholeVpnServiceI2pCarrierWiringTest {

    @Test
    fun `the service asks the plan at every tunnel transition`() {
        val connect = source("main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceConnectSupport.kt")
        val teardown = source("main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTeardownSupport.kt")

        assertTrue(connect.contains("applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_STARTING"))
        assertTrue(connect.contains("applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_UP)"))
        assertTrue(teardown.contains("applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_STOPPED)"))
    }

    @Test
    fun `the starting plan runs before the tunnel handover`() {
        val connect = source("main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceConnectSupport.kt")
        val planAt = connect.indexOf("applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_STARTING")
        val handoverAt = connect.indexOf("retireActiveLocalGuardForTunnelHandover()")

        assertTrue(planAt > 0)
        assertTrue(handoverAt > 0)
        assertTrue(planAt < handoverAt)
    }

    @Test
    fun `the fail-closed teardown asks only the shutdown transition`() {
        val teardown = source("main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTeardownSupport.kt")
        val helpers = teardown.substringAfter("internal suspend fun FoxholeVpnService.stopRuntimeHelpersIfNeeded")

        assertTrue(helpers.contains("applyI2pCarrierPlan(I2pTunnelTransition.RUNTIME_STOPPING)"))
        assertFalse(helpers.contains("I2pTunnelTransition.TUNNEL_STOPPED"))
        assertTrue(helpers.contains("stopI2pdProcessIfNeeded(reason)"))
    }

    @Test
    fun `teardown starts no carrier under any settings`() {
        teardownCases().forEach { case ->
            val state = I2pCarrierState(
                userEngaged = case.settings.userEngaged(),
                attachment = case.attachment,
            )
            val stopping = i2pCarrierPlan(
                settings = case.settings,
                state = state,
                transition = I2pTunnelTransition.RUNTIME_STOPPING,
            )

            assertFalse(
                "$case started a carrier during teardown",
                stopping.contains(I2pCarrierStep.START_DIRECT) ||
                    stopping.contains(I2pCarrierStep.START_TUNNELLED),
            )
            val expected =
                if (case.attachment == I2pAttachment.NONE) emptyList() else listOf(I2pCarrierStep.STOP)
            assertEquals("$case", expected, stopping)
        }
    }

    @Test
    fun `the same inputs under a tunnel stop do start a carrier, which is why teardown is its own case`() {
        val following = I2pSettings(
            enabled = true,
            engaged = true,
            allowOutsideTunnel = true,
            autoReconnectAfterVpnDisconnect = true,
        )
        val state = I2pCarrierState(userEngaged = true, attachment = I2pAttachment.TUNNELLED)

        assertEquals(
            listOf(I2pCarrierStep.STOP, I2pCarrierStep.START_DIRECT),
            i2pCarrierPlan(following, state, I2pTunnelTransition.TUNNEL_STOPPED),
        )
        assertEquals(
            listOf(I2pCarrierStep.STOP),
            i2pCarrierPlan(following, state, I2pTunnelTransition.RUNTIME_STOPPING),
        )
    }

    private data class TeardownCase(
        val settings: I2pSettings,
        val attachment: I2pAttachment,
    )

    private fun teardownCases(): List<TeardownCase> =
        allI2pSettings().flatMap { settings ->
            I2pAttachment.entries.map { attachment -> TeardownCase(settings, attachment) }
        }

    private fun allI2pSettings(): List<I2pSettings> =
        (0 until SETTINGS_COMBINATIONS).map { bits ->
            I2pSettings(
                enabled = bits and 1 != 0,
                engaged = bits and 2 != 0,
                allowOutsideTunnel = bits and 4 != 0,
                autoReconnectAfterVpnDisconnect = bits and 8 != 0,
            )
        }

    @Test
    fun `the carrier support writes no i2p setting`() {
        val support = source("main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceI2pCarrierSupport.kt")

        assertFalse(support.contains("updateI2pEnabled"))
        assertFalse(support.contains("updateI2pEngaged"))
        assertFalse(support.contains("applyI2pEngagement"))
        assertTrue(support.contains("i2pCarrierPlan("))
    }

    @Test
    fun `a disengaged module is never started by any transition the service raises`() {
        disengagedCases().forEach { case ->
            val steps = i2pCarrierPlan(
                settings = case.settings,
                state = I2pCarrierState(
                    userEngaged = case.settings.userEngaged(),
                    attachment = case.attachment,
                ),
                transition = case.transition,
            )

            assertFalse(
                "$case started a disengaged router",
                steps.contains(I2pCarrierStep.START_DIRECT) ||
                    steps.contains(I2pCarrierStep.START_TUNNELLED),
            )
            val expected =
                if (case.attachment == I2pAttachment.NONE) emptyList() else listOf(I2pCarrierStep.STOP)
            assertEquals("$case", expected, steps)
        }
    }

    private data class DisengagedCase(
        val settings: I2pSettings,
        val transition: I2pTunnelTransition,
        val attachment: I2pAttachment,
    )

    private fun disengagedCases(): List<DisengagedCase> =
        listOf(
            I2pSettings(enabled = false, engaged = false),
            I2pSettings(enabled = false, engaged = true),
            I2pSettings(enabled = true, engaged = false),
        ).flatMap { settings ->
            I2pTunnelTransition.entries.flatMap { transition ->
                I2pAttachment.entries.map { attachment ->
                    DisengagedCase(settings, transition, attachment)
                }
            }
        }

    private fun source(relative: String): String =
        listOf(
            File("src", relative),
            File("app/src", relative),
            File("../app/src", relative),
        ).first(File::isFile).readText()

    private companion object {
        const val SETTINGS_COMBINATIONS = 16
    }
}
