package com.foxhole.guard.ui

import com.foxhole.core.model.NetworkActivityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetworkActivityDiagnosticsFormatTest {
    @Test
    fun `missing remote city does not become a runtime error row`() {
        val message = safeEvent(countryCode = null).toNetworkActivityDiagnosticMessage(
            formatBytes = { bytes -> "$bytes B" },
            countryCodeForDestination = { "CA" },
            ipInfo = null,
        )

        assertTrue(message.contains("packages=com.example.safe"))
        assertTrue(message.contains("protocol=TCP"))
        assertTrue(message.contains("endpoint=198.51.100.10"))
        assertTrue(message.contains("country="))
        assertTrue(message.contains("(CA)"))
        assertTrue(message.contains("session=SAFE_SESSION"))
        assertFalse(message.contains("runtime error", ignoreCase = true))
        assertFalse(message.contains(" •  • "))
    }

    @Test
    fun `unresolved country uses a neutral structural placeholder`() {
        val message = safeEvent(countryCode = null).toNetworkActivityDiagnosticMessage(
            formatBytes = { bytes -> "$bytes B" },
        )

        assertTrue(message.contains("country=?"))
        assertFalse(message.contains("runtime error", ignoreCase = true))
    }

    @Test
    fun `network formatter no longer maps missing location to the generic error resource`() {
        val source = File(
            "src/main/kotlin/com/foxhole/guard/ui/NetworkActivityDiagnosticsFormat.kt",
        ).readText()

        assertFalse(source.contains("cli_common_unknown"))
        assertFalse(source.contains("unknownLabel"))
    }

    private fun safeEvent(countryCode: String?): NetworkActivityEvent =
        NetworkActivityEvent(
            timestampMs = 1L,
            packageNames = listOf("com.example.safe"),
            protocol = "TCP",
            remoteHost = "198.51.100.10",
            remotePort = 443,
            countryCode = countryCode,
            bytesRx = 10L,
            bytesTx = 20L,
            profileId = 7L,
            sessionId = "SAFE_SESSION",
        )
}
