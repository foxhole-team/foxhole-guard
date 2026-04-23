package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.TrustedSubscriptionCertificate
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionCertificateTrustTest {
    @Test
    fun `trusted certificate lookup matches host and fingerprint case insensitively`() {
        val trusted =
            listOf(
                TrustedSubscriptionCertificate(
                    host = "connect.stealthsurf.app",
                    sha256Fingerprint = "AA:BB:CC",
                ),
            )

        assertTrue(
            trusted.containsCertificate(
                host = "CONNECT.STEALTHSURF.APP",
                sha256Fingerprint = "aa:bb:cc",
            ),
        )
        assertFalse(
            trusted.containsCertificate(
                host = "connect.stealthsurf.app",
                sha256Fingerprint = "00:11:22",
            ),
        )
    }

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

    @Test
    fun `trusted certificate keeps captured metadata when persisted`() {
        val trusted =
            SubscriptionCertificateInfo(
                host = "connect.stealthsurf.app",
                sha256Fingerprint = "AA:BB:CC",
                subject = "O=ddos-guard",
                issuer = "O=ddos-guard",
            ).toTrustedCertificate(acceptedAt = 42L)

        assertEquals("connect.stealthsurf.app", trusted.host)
        assertEquals("AA:BB:CC", trusted.sha256Fingerprint)
        assertEquals("O=ddos-guard", trusted.subject)
        assertEquals("O=ddos-guard", trusted.issuer)
        assertEquals(42L, trusted.acceptedAt)
    }
}
