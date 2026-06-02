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

    @Test
    fun `startup composition defers only secondary traffic card`() {
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.PROFILES,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.ACTIONS,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.NETWORK,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
    }

    @Test
    fun `startup composition restores traffic card after first frame window`() {
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 5,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 0,
                activeReorderCard = DashboardCard.NETWORK,
            ),
        )
    }
}
