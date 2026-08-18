package com.foxhole.core.runtime

import com.foxhole.core.model.I2pSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class I2pCarrierPlanTest {
    private fun settings(
        follow: Boolean,
        outside: Boolean,
    ) = I2pSettings(
        enabled = true,
        engaged = true,
        allowOutsideTunnel = outside,
        autoReconnectAfterVpnDisconnect = follow,
    )

    private fun plan(
        follow: Boolean,
        outside: Boolean,
        attachment: I2pAttachment,
        transition: I2pTunnelTransition,
        userEngaged: Boolean = true,
    ) = i2pCarrierPlan(
        settings = settings(follow, outside),
        state = I2pCarrierState(userEngaged = userEngaged, attachment = attachment),
        transition = transition,
    )

    private fun allCases(): List<Case> =
        I2pTunnelTransition.entries.flatMap { transition ->
            I2pAttachment.entries.flatMap { attachment ->
                listOf(true, false).flatMap { follow ->
                    listOf(true, false).map { outside ->
                        Case(transition, attachment, follow, outside)
                    }
                }
            }
        }

    private data class Case(
        val transition: I2pTunnelTransition,
        val attachment: I2pAttachment,
        val follow: Boolean,
        val outside: Boolean,
    )

    @Test
    fun `a module the user switched off is never started by any transition`() {
        allCases().forEach { case ->
            val steps =
                plan(
                    follow = case.follow,
                    outside = case.outside,
                    attachment = case.attachment,
                    transition = case.transition,
                    userEngaged = false,
                )
            assertFalse(
                "disengaged I2P must never be started ($case)",
                steps.any { step -> step != I2pCarrierStep.STOP },
            )
        }
    }

    @Test
    fun `switching the module off tears down whatever carrier it was on`() {
        assertEquals(
            listOf(I2pCarrierStep.STOP),
            plan(
                follow = true,
                outside = true,
                attachment = I2pAttachment.DIRECT,
                transition = I2pTunnelTransition.TUNNEL_STOPPED,
                userEngaged = false,
            ),
        )
        assertEquals(
            emptyList<I2pCarrierStep>(),
            plan(
                follow = true,
                outside = true,
                attachment = I2pAttachment.NONE,
                transition = I2pTunnelTransition.TUNNEL_STOPPED,
                userEngaged = false,
            ),
        )
    }

    @Test
    fun `following I2P moves into a tunnel that comes up`() {
        assertEquals(
            listOf(I2pCarrierStep.START_TUNNELLED),
            plan(true, true, I2pAttachment.NONE, I2pTunnelTransition.TUNNEL_UP),
        )
    }

    @Test
    fun `following I2P returns to a direct carrier the moment the tunnel goes away`() {
        assertEquals(
            listOf(I2pCarrierStep.STOP, I2pCarrierStep.START_DIRECT),
            plan(true, true, I2pAttachment.TUNNELLED, I2pTunnelTransition.TUNNEL_STOPPED),
        )
    }

    @Test
    fun `following I2P already on the right carrier is left alone`() {
        assertTrue(plan(true, true, I2pAttachment.TUNNELLED, I2pTunnelTransition.TUNNEL_UP).isEmpty())
        assertTrue(plan(true, true, I2pAttachment.DIRECT, I2pTunnelTransition.TUNNEL_STOPPED).isEmpty())
    }

    @Test
    fun `a stationary I2P stops when a tunnel becomes active and is not restarted`() {
        assertEquals(
            listOf(I2pCarrierStep.STOP),
            plan(false, true, I2pAttachment.DIRECT, I2pTunnelTransition.TUNNEL_UP),
        )
        assertTrue(
            "a tunnel going away must not start a router the user did not start",
            plan(false, true, I2pAttachment.NONE, I2pTunnelTransition.TUNNEL_STOPPED).isEmpty(),
        )
    }

    @Test
    fun `a stationary I2P is never moved into a tunnel`() {
        for (attachment in I2pAttachment.entries) {
            val steps = plan(false, true, attachment, I2pTunnelTransition.TUNNEL_UP)
            assertFalse(
                "stationary I2P must not be tunnelled ($attachment)",
                steps.contains(I2pCarrierStep.START_TUNNELLED),
            )
        }
    }

    @Test
    fun `a direct I2P is stopped before the tunnel is raised and started again inside it`() {
        assertEquals(
            listOf(I2pCarrierStep.STOP),
            plan(true, false, I2pAttachment.DIRECT, I2pTunnelTransition.TUNNEL_STARTING),
        )
        assertEquals(
            listOf(I2pCarrierStep.START_TUNNELLED),
            plan(true, false, I2pAttachment.NONE, I2pTunnelTransition.TUNNEL_UP),
        )
    }

    @Test
    fun `nothing is left running outside a tunnel that is coming up, either switch position`() {
        for (outside in listOf(true, false)) {
            for (follow in listOf(true, false)) {
                assertEquals(
                    "direct I2P must not survive into a starting tunnel (outside=$outside follow=$follow)",
                    listOf(I2pCarrierStep.STOP),
                    plan(follow, outside, I2pAttachment.DIRECT, I2pTunnelTransition.TUNNEL_STARTING),
                )
            }
        }
    }

    @Test
    fun `with outside-tunnel off a lost tunnel stops I2P instead of going direct`() {
        assertEquals(
            listOf(I2pCarrierStep.STOP),
            plan(true, false, I2pAttachment.TUNNELLED, I2pTunnelTransition.TUNNEL_STOPPED),
        )
        assertTrue(
            plan(true, false, I2pAttachment.NONE, I2pTunnelTransition.TUNNEL_STOPPED).isEmpty(),
        )
    }

    @Test
    fun `the runtime going down never starts anything, whatever the switches say`() {
        allCases()
            .filter { case -> case.transition == I2pTunnelTransition.RUNTIME_STOPPING }
            .forEach { case ->
                val steps =
                    plan(
                        follow = case.follow,
                        outside = case.outside,
                        attachment = case.attachment,
                        transition = case.transition,
                    )
                assertFalse(
                    "teardown must not resurrect i2pd ($case)",
                    steps.any { step -> step != I2pCarrierStep.STOP },
                )
            }
    }

    @Test
    fun `teardown is distinct from a tunnel merely going away`() {
        assertEquals(
            listOf(I2pCarrierStep.STOP, I2pCarrierStep.START_DIRECT),
            plan(true, true, I2pAttachment.TUNNELLED, I2pTunnelTransition.TUNNEL_STOPPED),
        )
        assertEquals(
            listOf(I2pCarrierStep.STOP),
            plan(true, true, I2pAttachment.TUNNELLED, I2pTunnelTransition.RUNTIME_STOPPING),
        )
        assertTrue(
            plan(true, true, I2pAttachment.NONE, I2pTunnelTransition.RUNTIME_STOPPING).isEmpty(),
        )
    }

    @Test
    fun `userEngaged folds permission and pause`() {
        assertTrue(I2pSettings(enabled = true, engaged = true).userEngaged())
        assertFalse(I2pSettings(enabled = true, engaged = false).userEngaged())
        assertFalse(I2pSettings(enabled = false, engaged = true).userEngaged())
    }
}
