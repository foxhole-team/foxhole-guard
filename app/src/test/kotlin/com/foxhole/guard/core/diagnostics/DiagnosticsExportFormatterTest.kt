package com.foxhole.guard.core.diagnostics
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DiagnosticsExportFormatterTest {
    private val formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC)

    @Test
    fun `export includes metadata header and sanitizes retained entries`() {
        val payload =
            formatDiagnosticsExport(
                metadata =
                DiagnosticsExportMetadata(
                    generatedAt = 1_700_000_000_000L,
                    appVersion = "1.0.0-beta1",
                    versionCode = 1,
                    coreVersion = "1.13.6",
                    androidRelease = "16",
                    sdkInt = 36,
                    supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                    themeMode = "dark",
                    locale = "ru",
                    trafficMode = "tunnel",
                    tunStack = "system",
                    diagnosticsRetention = RetentionPolicy(RetentionPreset.WEEK),
                    networkActivityLoggingEnabled = false,
                ),
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_500L,
                        tag = "profile",
                        message = "server=example.com ip=79.120.30.76 token=secret",
                    ),
                ),
                formatter = formatter,
            )

        assertTrue(payload.contains("Foxhole Diagnostics Export"))
        assertTrue(payload.contains("App Version: 1.0.0-beta1 (1)"))
        assertTrue(payload.contains("CPU ABI: arm64-v8a, armeabi-v7a"))
        assertTrue(payload.contains("Diagnostics Retention: 7 days"))
        assertTrue(payload.contains("App Network Activity Logging: disabled"))
        assertTrue(payload.contains("[profile] server=[redacted] ip=[redacted] token=[redacted]"))
        assertFalse(payload.contains("example.com"))
        assertFalse(payload.contains("79.120.30.76"))
        assertFalse(payload.contains("secret"))
    }

    @Test
    fun `export has no raw mode`() {
        val payload =
            formatDiagnosticsExport(
                metadata =
                DiagnosticsExportMetadata(
                    generatedAt = 1_700_000_000_000L,
                    appVersion = "1.0.0-beta1",
                    versionCode = 1,
                    coreVersion = "1.13.6",
                    androidRelease = "16",
                    sdkInt = 36,
                    supportedAbis = listOf("arm64-v8a"),
                    themeMode = "dark",
                    locale = "ru",
                    trafficMode = "tunnel",
                    tunStack = "system",
                    diagnosticsRetention = RetentionPolicy(RetentionPreset.DAY),
                    networkActivityLoggingEnabled = true,
                ),
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_500L,
                        tag = "profile",
                        message = "server=example.com ip=79.120.30.76 token=secret",
                    ),
                ),
                formatter = formatter,
            )

        assertTrue(payload.contains("[profile] server=[redacted] ip=[redacted] token=[redacted]"))
        assertFalse(payload.contains("example.com"))
        assertFalse(payload.contains("79.120.30.76"))
        assertFalse(payload.contains("secret"))
    }

    @Test
    fun `export shows explicit message when no entries are retained`() {
        val payload =
            formatDiagnosticsExport(
                metadata =
                DiagnosticsExportMetadata(
                    generatedAt = 1_700_000_000_000L,
                    appVersion = "1.0.0-beta1",
                    versionCode = 1,
                    coreVersion = "1.13.6",
                    androidRelease = "16",
                    sdkInt = 36,
                    supportedAbis = emptyList(),
                    themeMode = "system",
                    locale = "system",
                    trafficMode = "tunnel",
                    tunStack = "system",
                    diagnosticsRetention = RetentionPolicy(RetentionPreset.DAY),
                    networkActivityLoggingEnabled = true,
                ),
                entries = emptyList(),
                formatter = formatter,
            )

        assertTrue(payload.contains("No retained diagnostics entries."))
    }
}
