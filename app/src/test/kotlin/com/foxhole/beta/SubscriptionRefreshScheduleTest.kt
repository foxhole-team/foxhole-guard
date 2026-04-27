package com.foxhole.beta

import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRefreshScheduleTest {
    @Test
    fun `refresh interval hours stay mapped to selectable schedule values`() {
        assertEquals(6L, subscriptionRefreshIntervalHours(SubscriptionRefreshInterval.HOURS_6))
        assertEquals(12L, subscriptionRefreshIntervalHours(SubscriptionRefreshInterval.HOURS_12))
        assertEquals(24L, subscriptionRefreshIntervalHours(SubscriptionRefreshInterval.HOURS_24))
    }
}
