package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FoxholeNavigationPresentationTest {
    @Test
    fun `detail transition uses subtle material-style offset instead of full page slide`() {
        assertEquals(194, detailTransitionOffsetPx(1080))
        assertEquals(1, detailTransitionOffsetPx(1))
    }
}
