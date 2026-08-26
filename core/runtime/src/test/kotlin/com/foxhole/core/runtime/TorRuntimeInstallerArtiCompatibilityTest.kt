package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRuntimeInstallerArtiCompatibilityTest {
    @Test
    fun `Arti inventory keeps bridges with a standalone RSA identity`() {
        assertTrue(
            isArtiCompatibleBridgeLine(
                "obfs4 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=value iat-mode=0",
            ),
        )
        assertTrue(
            isArtiCompatibleBridgeLine(
                "snowflake 192.0.2.3:80 \$0123456789ABCDEF0123456789ABCDEF01234567 fingerprint=value",
            ),
        )
    }

    @Test
    fun `Arti inventory drops identityless legacy meek lines before native start`() {
        assertFalse(
            isArtiCompatibleBridgeLine(
                "meek_lite 192.0.2.20:80 url=https://cdn.example front=example.org",
            ),
        )
    }

    @Test
    fun `identical managed transport commands share one helper process`() {
        val transports =
            listOf(
                TorPluggableTransport(
                    protocols = listOf("obfs4", "webtunnel"),
                    executablePath = "/native/liblyrebird.so",
                ),
                TorPluggableTransport(
                    protocols = listOf("snowflake", "obfs4"),
                    executablePath = "/native/liblyrebird.so",
                ),
            ).coalesceByProcess()

        assertEquals(1, transports.size)
        assertEquals(listOf("obfs4", "webtunnel", "snowflake"), transports.single().protocols)
    }

    @Test
    fun `different managed transport arguments keep separate helper processes`() {
        val transports =
            listOf(
                TorPluggableTransport(
                    protocols = listOf("obfs4"),
                    executablePath = "/native/liblyrebird.so",
                ),
                TorPluggableTransport(
                    protocols = listOf("snowflake"),
                    executablePath = "/native/liblyrebird.so",
                    arguments = listOf("-logLevel", "WARN"),
                ),
            ).coalesceByProcess()

        assertEquals(2, transports.size)
    }

    @Test
    fun `only required protocols survive before helpers are coalesced`() {
        val transports =
            listOf(
                TorPluggableTransport(
                    protocols = listOf("meek_lite", "obfs4", "webtunnel"),
                    executablePath = "/native/liblyrebird.so",
                ),
                TorPluggableTransport(
                    protocols = listOf("snowflake", "obfs4"),
                    executablePath = "/native/liblyrebird.so",
                ),
                TorPluggableTransport(
                    protocols = listOf("conjure"),
                    executablePath = "/native/libconjure_client.so",
                ),
            ).restrictToRequiredProtocols(setOf("snowflake"))

        assertEquals(1, transports.size)
        assertEquals(listOf("snowflake"), transports.single().protocols)
        assertEquals("/native/liblyrebird.so", transports.single().executablePath)
    }
}
