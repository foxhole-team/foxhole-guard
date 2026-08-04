package com.foxhole.core.runtime

import com.foxhole.guard.runtime.SubscriptionRefreshWorkDecision
import com.foxhole.guard.runtime.decideScheduledRefreshOutcome
import com.foxhole.guard.runtime.isRetryableScheduledRefreshFailure
import com.foxhole.guard.runtime.shouldSkipScheduledRefreshForNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class SubscriptionRefreshWorkerSupportTest {
    @Test
    fun `requests retry when every scheduled refresh fails with retryable transport error`() {
        assertEquals(
            SubscriptionRefreshWorkDecision.RETRY,
            decideScheduledRefreshOutcome(
                targetedProfiles = 3,
                successfulProfiles = 0,
                retryableFailures = 2,
            ),
        )
    }

    @Test
    fun `keeps success when at least one profile refresh succeeded`() {
        assertEquals(
            SubscriptionRefreshWorkDecision.SUCCESS,
            decideScheduledRefreshOutcome(
                targetedProfiles = 3,
                successfulProfiles = 1,
                retryableFailures = 2,
            ),
        )
    }

    @Test
    fun `treats wrapped io failures and http 503 as retryable`() {
        assertTrue(
            isRetryableScheduledRefreshFailure(
                IllegalStateException("transport failed", IOException("timeout")),
            ),
        )
        assertTrue(
            isRetryableScheduledRefreshFailure(
                IllegalStateException("refresh failed with http 503"),
            ),
        )
    }

    @Test
    fun `does not retry tls trust failures`() {
        val trustFailure =
            IllegalStateException(
                "Subscription update failed for example.org: untrusted TLS certificate chain",
                SSLHandshakeException("self signed certificate"),
            )

        assertFalse(isRetryableScheduledRefreshFailure(trustFailure))
    }

    @Test
    fun `skips scheduled refresh on cellular or metered networks when enabled`() {
        assertTrue(
            shouldSkipScheduledRefreshForNetwork(
                skipOnCellular = true,
                transport = "cellular",
                isMetered = false,
            ),
        )
        assertTrue(
            shouldSkipScheduledRefreshForNetwork(
                skipOnCellular = true,
                transport = "wifi",
                isMetered = true,
            ),
        )
        assertFalse(
            shouldSkipScheduledRefreshForNetwork(
                skipOnCellular = false,
                transport = "cellular",
                isMetered = true,
            ),
        )
        assertFalse(
            shouldSkipScheduledRefreshForNetwork(
                skipOnCellular = true,
                transport = "wifi",
                isMetered = false,
            ),
        )
    }
}
