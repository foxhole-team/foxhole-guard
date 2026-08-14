package com.foxhole.guard.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileImportIdentityTest {
    @Test
    fun `subscription identity normalizes only scheme host and default port`() {
        assertEquals(
            normalizedSubscriptionSourceIdentity("https://example.org/Token?Key=Value"),
            normalizedSubscriptionSourceIdentity("HTTPS://EXAMPLE.ORG:443/Token?Key=Value#ignored"),
        )
    }

    @Test
    fun `subscription identity preserves credential path and query case`() {
        assertNotEquals(
            normalizedSubscriptionSourceIdentity("https://example.org/Token?Key=Value"),
            normalizedSubscriptionSourceIdentity("https://example.org/token?Key=Value"),
        )
        assertNotEquals(
            normalizedSubscriptionSourceIdentity("https://example.org/Token?Key=Value"),
            normalizedSubscriptionSourceIdentity("https://example.org/Token?Key=value"),
        )
    }

    @Test
    fun `raw json identity ignores object key order and whitespace`() {
        assertEquals(
            stableRawImportFingerprint("""{"b":2,"a":{"y":1,"x":0}}"""),
            stableRawImportFingerprint(
                """
                {
                  "a": { "x": 0, "y": 1 },
                  "b": 2
                }
                """.trimIndent(),
            ),
        )
    }
}
