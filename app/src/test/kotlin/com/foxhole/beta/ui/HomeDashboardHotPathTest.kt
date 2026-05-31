package com.foxhole.beta.ui

import com.foxhole.beta.core.model.DashboardCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardHotPathTest {
    @Test
    fun `dashboard card placement animation is disabled outside reorder`() {
        assertFalse(shouldAnimateDashboardCardPlacement(activeCard = null, card = DashboardCard.NETWORK))
    }

    @Test
    fun `dashboard card placement animation skips actively dragged card`() {
        assertFalse(
            shouldAnimateDashboardCardPlacement(
                activeCard = DashboardCard.NETWORK,
                card = DashboardCard.NETWORK,
            ),
        )
    }

    @Test
    fun `dashboard card placement animation runs only for other cards during reorder`() {
        assertTrue(
            shouldAnimateDashboardCardPlacement(
                activeCard = DashboardCard.NETWORK,
                card = DashboardCard.TRAFFIC,
            ),
        )
    }
}
