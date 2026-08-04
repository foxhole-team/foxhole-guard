package com.foxhole.guard.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class ProfileRepositoryErrorMappingTest {
    @Test
    fun `maps trust anchor failures to concise subscription tls message`() {
        val error =
            SSLHandshakeException(
                "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."
            )
                .apply {
                    initCause(CertPathValidatorException("Trust anchor for certification path not found."))
                }

        val message =
            describeSubscriptionTransportFailure(
                sourceUrl = "https://connect.stealthsurf.app/to/04e9d47aa9699514cf844283173a8329",
                error = error,
            )

        assertEquals(
            "Subscription update failed for connect.stealthsurf.app: untrusted TLS certificate chain; self-signed subscription certificates are not supported",
            message,
        )
    }

    @Test
    fun `maps self signed subscription certificates to unsupported transport message`() {
        val message =
            describeSubscriptionTransportFailure(
                sourceUrl = "https://example.org/subscription",
                error = SSLHandshakeException("self signed certificate"),
            )

        assertEquals(
            "Subscription update failed for example.org: untrusted TLS certificate chain; self-signed subscription certificates are not supported",
            message,
        )
    }

    @Test
    fun `keeps non tls refresh failures unchanged`() {
        val error = IllegalStateException("refresh failed with http 403")

        val message =
            describeSubscriptionTransportFailure(
                sourceUrl = "https://example.org/subscription",
                error = error,
            )

        assertEquals("refresh failed with http 403", message)
    }

    @Test
    fun `maps ddos guard 503 into an explicit anti bot transport message`() {
        assertEquals(
            "subscription provider blocked automated fetch with anti-bot challenge",
            describeSubscriptionHttpFailure(
                code = 503,
                serverHeader = "ddos-guard",
                responseBody = "<!DOCTYPE html><title>DDoS-Guard</title>",
            ),
        )
    }
}
