package com.foxhole.beta.core.data

import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionCertificateTrustTest {
    @Test
    fun `recognizes trust anchor failures as tls trust failures`() {
        val error =
            SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
                .apply {
                    initCause(CertPathValidatorException("Trust anchor for certification path not found."))
                }

        assertTrue(isTlsTrustFailure(error))
    }

    @Test
    fun `recognizes self signed wording as tls trust failure`() {
        assertTrue(isTlsTrustFailure(IllegalStateException("SSL error: self signed certificate")))
        assertFalse(isTlsTrustFailure(IllegalStateException("refresh failed with http 403")))
    }
}
