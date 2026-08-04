package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpRefreshCoordinatorTest {
    @Test
    fun `foreground refresh coalesces into active post connect refresh`() {
        val coordinator = IpRefreshCoordinator()
        val start =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.POST_CONNECT,
                generation = 10L,
            ) as IpRefreshDecision.Start

        val decision =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.FOREGROUND,
                generation = 10L,
            )

        val coalesced = decision as IpRefreshDecision.Coalesced
        assertEquals(start.token, coalesced.activeToken)
        assertTrue(coordinator.isCurrent(start.token))
    }

    @Test
    fun `post connect refresh supersedes lower priority foreground refresh`() {
        val coordinator = IpRefreshCoordinator()
        val foreground =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.FOREGROUND,
                generation = 10L,
            ) as IpRefreshDecision.Start

        val decision =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.POST_CONNECT,
                generation = 10L,
            )

        val start = decision as IpRefreshDecision.Start
        assertTrue(start.supersededActive)
        assertFalse(coordinator.isCurrent(foreground.token))
        assertTrue(coordinator.isCurrent(start.token))
    }

    @Test
    fun `network generation change starts a new refresh`() {
        val coordinator = IpRefreshCoordinator()
        val first =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.POST_CONNECT,
                generation = 10L,
            ) as IpRefreshDecision.Start

        val second =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.FOREGROUND,
                generation = 11L,
            ) as IpRefreshDecision.Start

        assertTrue(second.supersededActive)
        assertFalse(coordinator.isCurrent(first.token))
        assertTrue(coordinator.isCurrent(second.token))
    }

    @Test
    fun `manual refresh starts even while another manual refresh is active`() {
        val coordinator = IpRefreshCoordinator()
        val first =
            coordinator.request(
                target = IpInfoRefreshTarget.UPSTREAM,
                reason = IpInfoRefreshReason.MANUAL,
                generation = 1L,
            ) as IpRefreshDecision.Start

        val second =
            coordinator.request(
                target = IpInfoRefreshTarget.UPSTREAM,
                reason = IpInfoRefreshReason.MANUAL,
                generation = 1L,
            ) as IpRefreshDecision.Start

        assertTrue(second.supersededActive)
        assertFalse(coordinator.isCurrent(first.token))
        assertTrue(coordinator.isCurrent(second.token))
    }

    @Test
    fun `tor route refresh supersedes ordinary refresh on same route`() {
        val coordinator = IpRefreshCoordinator()
        val ordinary =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.POST_UPDATE,
                generation = 20L,
            ) as IpRefreshDecision.Start

        val tor =
            coordinator.request(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.TOR_ROUTE,
                generation = 20L,
            ) as IpRefreshDecision.Start

        assertTrue(tor.supersededActive)
        assertFalse(coordinator.isCurrent(ordinary.token))
        assertTrue(coordinator.isCurrent(tor.token))
    }
}
