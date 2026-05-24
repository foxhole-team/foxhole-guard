package com.foxhole.beta.ui

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NetworkActivityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NetworkActivityLogFormatterTest {
    @Test
    fun `safe network activity log keeps support metrics and replaces private identifiers`() {
        val message =
            sampleEvent().toNetworkActivityDiagnosticMessage(
                formatBytes = { bytes -> "$bytes B" },
                countryCodeForDestination = { destination -> if (destination == "1.1.1.1") "US" else null },
                sanitizePrivateData = true,
            )

        assertTrue(message.contains("packages=[pkg#"))
        assertTrue(message.contains("endpoint=[ip#"))
        assertTrue(message.contains("port=443"))
        assertTrue(message.contains("country=\uD83C\uDDFA\uD83C\uDDF8"))
        assertTrue(message.contains("(US)"))
        assertTrue(message.contains("rx=2048 B"))
        assertTrue(message.contains("tx=1024 B"))
        assertTrue(message.contains("total=3072 B"))
        assertTrue(message.contains("profile=[profile#"))
        assertTrue(message.contains("session=[session#"))
        assertFalse(message.contains("com.bank.app"))
        assertFalse(message.contains("1.1.1.1"))
        assertFalse(message.contains("session-a"))
    }

    @Test
    fun `raw network activity log preserves endpoint package and identifiers`() {
        val message =
            sampleEvent().toNetworkActivityDiagnosticMessage(
                formatBytes = { bytes -> "$bytes B" },
                countryCodeForDestination = { "US" },
                sanitizePrivateData = false,
            )

        assertTrue(message.contains("packages=com.bank.app"))
        assertTrue(message.contains("endpoint=1.1.1.1"))
        assertTrue(message.contains("port=443"))
        assertTrue(message.contains("profile=42"))
        assertTrue(message.contains("session=session-a"))
    }

    @Test
    fun `network activity log falls back to current ip info country`() {
        val message =
            sampleEvent(remoteHost = "203.0.113.10", countryCode = null)
                .toNetworkActivityDiagnosticMessage(
                    formatBytes = { bytes -> "$bytes B" },
                    countryCodeForDestination = { null },
                    ipInfo = IpInfo(
                        ip = "203.0.113.10",
                        countryCode = "NL",
                        countryName = "Netherlands",
                        city = "Amsterdam",
                        isp = "Example",
                        fetchedAt = 1L,
                    ),
                    sanitizePrivateData = true,
                )

        assertTrue(message.contains("country=\uD83C\uDDF3\uD83C\uDDF1"))
        assertTrue(message.contains("(NL)"))
    }

    @Test
    fun `plain diagnostics export cleanup removes stale cache files only`() {
        val dir = Files.createTempDirectory("foxhole-plain-diagnostics").toFile()
        val old = dir.resolve("old.log").apply { writeText("old") }
        val fresh = dir.resolve("fresh.log").apply { writeText("fresh") }
        val now = 10 * 60 * 1000L
        old.setLastModified(0L)
        fresh.setLastModified(now)

        cleanupExpiredPlainDiagnosticsExports(dir, nowMs = now)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
        dir.deleteRecursively()
    }

    private fun sampleEvent(
        remoteHost: String = "1.1.1.1",
        countryCode: String? = null,
    ): NetworkActivityEvent =
        NetworkActivityEvent(
            timestampMs = 1_000L,
            packageNames = listOf("com.bank.app"),
            protocol = "TCP",
            remoteHost = remoteHost,
            remotePort = 443,
            countryCode = countryCode,
            bytesRx = 2_048L,
            bytesTx = 1_024L,
            profileId = 42L,
            sessionId = "session-a",
        )
}
