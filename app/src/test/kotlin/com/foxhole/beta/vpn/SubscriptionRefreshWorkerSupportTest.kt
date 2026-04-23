package com.foxhole.beta.vpn

import com.foxhole.beta.core.data.SubscriptionCertificateInfo
import com.foxhole.beta.core.data.SubscriptionTlsTrustRequiredException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `does not retry trust-required subscription failures`() {
        val trustFailure =
            SubscriptionTlsTrustRequiredException(
                sourceUrl = "https://example.org/subscription",
                certificate =
                    SubscriptionCertificateInfo(
                        host = "example.org",
                        sha256Fingerprint = "ABCD",
                        subject = "CN=example.org",
                        issuer = "CN=example.org",
                    ),
            )

        assertFalse(isRetryableScheduledRefreshFailure(trustFailure))
    }
}
