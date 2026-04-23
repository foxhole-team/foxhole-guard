package com.foxhole.beta.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataParserTest {
    @Test
    fun `reads expiration from raw query`() {
        assertEquals(
            1_700_000_000_000L,
            SubscriptionMetadataParser.expirationFromRawQuery("foo=bar&expires=1700000000"),
        )
    }

    @Test
    fun `reads expiration from subscription userinfo header`() {
        assertEquals(
            1_779_390_865_000L,
            SubscriptionMetadataParser.expirationFromSubscriptionUserinfo("upload=0; download=0; expire=1779390865"),
        )
    }

    @Test
    fun `parses iso expiration value`() {
        assertEquals(
            1_717_200_000_000L,
            SubscriptionMetadataParser.parseExpirationValue("2024-06-01T00:00:00Z"),
        )
    }

    @Test
    fun `returns null when no expiration metadata is present`() {
        assertNull(SubscriptionMetadataParser.expirationFromSubscriptionUrl("https://example.org/subscription"))
    }
}
