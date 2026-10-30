package com.foxhole.core.importer

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class OutlineImportTest {
    private val parser = ProfileImportParser(
        Json { ignoreUnknownKeys = false }
    ) { listOf(InetAddress.getByName("1.1.1.1")) }
    private val body = """{"server":"1.1.1.1","server_port":8388,"password":"00123","method":"aes-256-gcm"}"""

    @Test
    fun `dynamic key retains original refresh and share source`() {
        val source = "ssconf://example.com/opaque%2Fkey?token=value#My%20VPN"
        val result = parser.parseUserInput(source)
        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, result.sourceType)
        assertEquals(source, result.sourceUrl)
        assertEquals("My VPN", result.displayName)
    }

    @Test
    fun `standard Outline JSON payload is usable locally and after refresh`() {
        assertEquals(ProtocolHint.OUTLINE, parser.parseUserInput(body).protocolHint)
        assertEquals(
            ProtocolHint.OUTLINE,
            parser.parseSubscriptionProfiles(body, "Outline").profiles.single().protocolHint
        )
    }

    @Test
    fun `unsupported dynamic transports and private endpoints are refused`() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { parser.parseUserInput(body.replace("1.1.1.1", "127.0.0.1")) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { parser.parseUserInput(body.dropLast(1) + ",\"transport\":{\"type\":\"unknown\"}}") }
    }
}
