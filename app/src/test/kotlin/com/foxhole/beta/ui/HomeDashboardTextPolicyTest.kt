package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardTextPolicyTest {
    @Test
    fun `keeps dashboard profile title unchanged when within limit`() {
        assertEquals("FoxHole vpn direct", dashboardProfileTitle("FoxHole vpn direct"))
    }

    @Test
    fun `truncates dashboard profile title after twenty five characters with two dots`() {
        assertEquals(
            "12345678901234567890123..",
            dashboardProfileTitle("123456789012345678901234567890"),
        )
    }
}
