package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.ThreatIntelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppRiskScorerTest {
    @Test
    fun `sensitive Android capabilities are high risk`() {
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(listOf(InstalledAppRiskSignal.ACCESSIBILITY_SERVICE)),
        )
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(listOf(InstalledAppRiskSignal.VPN_SERVICE)),
        )
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(listOf(InstalledAppRiskSignal.OVERLAY_PERMISSION)),
        )
    }

    @Test
    fun `multiple softer signals escalate to high risk`() {
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(
                listOf(
                    InstalledAppRiskSignal.UNKNOWN_INSTALLER,
                    InstalledAppRiskSignal.AUTOSTART,
                    InstalledAppRiskSignal.SYSTEM_LIKE_NAME,
                ),
            ),
        )
    }

    @Test
    fun `single softer signal is medium risk and empty signals are low risk`() {
        assertEquals(
            InstalledAppRiskLevel.MEDIUM,
            installedAppRiskLevel(listOf(InstalledAppRiskSignal.UNKNOWN_INSTALLER)),
        )
        assertEquals(InstalledAppRiskLevel.LOW, installedAppRiskLevel(emptyList()))
    }

    @Test
    fun `accessibility capability scores high with the matching signal`() {
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.example.spy",
                    label = "Helper",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                    hasAccessibilityService = true,
                ),
            )
        assertEquals(InstalledAppRiskLevel.HIGH, assessment.riskLevel)
        assertTrue(InstalledAppRiskSignal.ACCESSIBILITY_SERVICE in assessment.riskSignals)
    }

    @Test
    fun `sideloaded app from an unknown installer is flagged`() {
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.example.sideload",
                    label = "Sideloaded",
                    isSystemApp = false,
                    installerPackageName = "com.unknown.dropper",
                ),
            )
        assertTrue(InstalledAppRiskSignal.UNKNOWN_INSTALLER in assessment.riskSignals)
        assertEquals(InstalledAppRiskLevel.MEDIUM, assessment.riskLevel)
    }

    @Test
    fun `trusted-store system-ish app has no signals`() {
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.android.settings",
                    label = "Settings",
                    isSystemApp = true,
                    installerPackageName = null,
                ),
            )
        assertTrue(assessment.riskSignals.isEmpty())
        assertEquals(InstalledAppRiskLevel.LOW, assessment.riskLevel)
    }

    @Test
    fun `non-system app masquerading as a system name is flagged`() {
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.evil.app",
                    label = "System Update",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                ),
            )
        assertTrue(InstalledAppRiskSignal.SYSTEM_LIKE_NAME in assessment.riskSignals)
    }

    @Test
    fun `threat-intel package-name match forces a known-threat high`() {
        val intel = InstalledAppThreatIntel(maliciousPackages = setOf("com.evil.dropper"))
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.evil.dropper",
                    label = "Free Game",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                ),
                intel,
            )
        assertTrue(InstalledAppRiskSignal.KNOWN_THREAT in assessment.riskSignals)
        assertEquals(InstalledAppRiskLevel.HIGH, assessment.riskLevel)
    }

    @Test
    fun `threat-intel certificate match is case-insensitive`() {
        val intel = InstalledAppThreatIntel(maliciousCertSha256 = setOf("AABBCC"))
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.repackaged.app",
                    label = "App",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                    signingCertSha256 = setOf("aabbcc"),
                ),
                intel,
            )
        assertTrue(InstalledAppRiskSignal.KNOWN_THREAT in assessment.riskSignals)
    }

    @Test
    fun `a sha-1 certificate match raises the app to a known threat`() {
        val intel = InstalledAppThreatIntel(maliciousCertSha1 = setOf("31A6ECECD97CF39BC4126B8745CD94A7C30BF81C"))
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.renamed.by.the.author",
                    label = "App",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                    signingCertSha1 = setOf("31a6ececd97cf39bc4126b8745cd94a7c30bf81c"),
                ),
                intel,
            )
        assertTrue(InstalledAppRiskSignal.KNOWN_THREAT in assessment.riskSignals)
    }

    @Test
    fun `a sha-256 feed does not match a sha-1 certificate and the other way round`() {
        val intel = InstalledAppThreatIntel(maliciousCertSha256 = setOf("aabbcc"))
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.clean.app",
                    label = "App",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                    signingCertSha1 = setOf("aabbcc"),
                ),
                intel,
            )
        assertFalse(InstalledAppRiskSignal.KNOWN_THREAT in assessment.riskSignals)
    }

    @Test
    fun `document converts to intel and merges as a union`() {
        val bundled = ThreatIntelDocument(packages = listOf("com.bundled.bad", "")).toThreatIntel()
        val remote = ThreatIntelDocument(certs = listOf("deadbeef")).toThreatIntel()
        val merged = bundled.mergedWith(remote)

        assertTrue(
            merged.matches(
                InstalledAppFacts(
                    packageName = "com.bundled.bad",
                    label = "Bundled",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                ),
            ),
        )
        assertTrue(
            merged.matches(
                InstalledAppFacts(
                    packageName = "com.clean.app",
                    label = "Clean",
                    isSystemApp = false,
                    installerPackageName = "com.android.vending",
                    signingCertSha256 = setOf("DEADBEEF"),
                ),
            ),
        )

        assertTrue(ThreatIntelDocument(packages = listOf(""), certs = listOf("")).toThreatIntel().isEmpty)
    }

    @Test
    fun `empty threat-intel does not flag clean apps`() {
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.android.vending",
                    label = "Play Store",
                    isSystemApp = true,
                    installerPackageName = null,
                ),
                InstalledAppThreatIntel.EMPTY,
            )
        assertTrue(InstalledAppRiskSignal.KNOWN_THREAT !in assessment.riskSignals)
        assertEquals(InstalledAppRiskLevel.LOW, assessment.riskLevel)
    }

    @Test
    fun `permission-derived booleans map to their signals`() {
        val assessment =
            scoreInstalledApp(
                InstalledAppFacts(
                    packageName = "com.example.persistent",
                    label = "Persistent",
                    isSystemApp = true,
                    installerPackageName = null,
                    requestsOverlay = true,
                    ignoresBatteryOptimizations = true,
                    canAutostart = true,
                ),
            )
        assertTrue(InstalledAppRiskSignal.OVERLAY_PERMISSION in assessment.riskSignals)
        assertTrue(InstalledAppRiskSignal.BATTERY_OPTIMIZATION_IGNORE in assessment.riskSignals)
        assertTrue(InstalledAppRiskSignal.AUTOSTART in assessment.riskSignals)

        assertEquals(InstalledAppRiskLevel.HIGH, assessment.riskLevel)
    }
}
