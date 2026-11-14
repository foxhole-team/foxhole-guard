package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.CapabilityState
import com.foxhole.core.model.InstalledAppAssessmentStatus
import com.foxhole.core.model.InstalledAppCapability
import com.foxhole.core.model.InstalledAppCapabilityExposure
import com.foxhole.core.model.InstalledAppEvidenceKind
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppThreatConfidence
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class InstalledAppAssessmentTest {
    @Test
    fun `single intended capability is exposure rather than malware evidence`() {
        for (capability in listOf(InstalledAppCapability.VPN, InstalledAppCapability.ACCESSIBILITY)) {
            val assessment = scoreInstalledApp(
                facts(
                    capabilities = listOf(
                        InstalledAppCapabilityExposure(capability, CapabilityState.YES, CapabilityState.NO),
                    ),
                ),
                InstalledAppThreatIntel(),
            ).assessment
            assertEquals(InstalledAppThreatConfidence.NO_CORROBORATING_EVIDENCE, assessment.threatConfidence)
            assertEquals(CapabilityState.NO, assessment.capabilityExposure.single().enabled)
            assertEquals(CapabilityState.UNKNOWN, assessment.capabilityExposure.single().granted)
        }
    }

    @Test
    fun `package current signer and historical signer remain distinct evidence`() {
        val assessment = scoreInstalledApp(
            facts().copy(
                signingCertSha256 = setOf("current"),
                historicalSigningCertSha256 = setOf("historical"),
            ),
            InstalledAppThreatIntel(
                maliciousPackages = setOf("org.example.app"),
                maliciousCertSha256 = setOf("current", "historical"),
            ),
        ).assessment
        assertEquals(
            setOf(
                InstalledAppEvidenceKind.PACKAGE_NAME,
                InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA256,
                InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA256,
            ),
            assessment.matchedEvidence.map { it.kind }.toSet(),
        )
        assertEquals(InstalledAppThreatConfidence.STRONG_INDICATOR_MATCH, assessment.threatConfidence)
        assertTrue(assessment.matchedEvidence.all { it.sourceId == null && it.feedVersion == null })
    }

    @Test
    fun `package only and historical signer do not claim current signer confidence`() {
        val intel = InstalledAppThreatIntel(maliciousPackages = setOf("org.example.app"))
        assertEquals(
            InstalledAppThreatConfidence.LIMITED_INDICATOR_MATCH,
            scoreInstalledApp(facts(), intel).assessment.threatConfidence,
        )
        val historical = facts().copy(historicalSigningCertSha256 = setOf("old"))
        assertEquals(
            InstalledAppThreatConfidence.LIMITED_INDICATOR_MATCH,
            scoreInstalledApp(historical, InstalledAppThreatIntel(maliciousCertSha256 = setOf("old")))
                .assessment.threatConfidence,
        )
    }

    @Test
    fun `unavailable collection or feed is never a completed clean assessment`() {
        val unavailable = scoreInstalledApp(
            facts().copy(assessmentStatus = InstalledAppAssessmentStatus.UNAVAILABLE),
            InstalledAppThreatIntel(),
        ).assessment
        assertEquals(InstalledAppAssessmentStatus.UNAVAILABLE, unavailable.status)
        assertEquals(InstalledAppThreatConfidence.UNKNOWN, unavailable.threatConfidence)
        assertEquals(
            InstalledAppThreatConfidence.UNKNOWN,
            scoreInstalledApp(facts(), InstalledAppThreatIntel.EMPTY).assessment.threatConfidence,
        )
    }

    @Test
    fun `presentation dimensions preserve the existing enforcement risk contract`() {
        val legacyVpn = facts().copy(hasVpnService = true)
        assertEquals(InstalledAppRiskLevel.HIGH, scoreInstalledApp(legacyVpn).riskLevel)
    }

    @Test
    fun `merging known provenance does not erase the unknown source of another match`() {
        val unknown = InstalledAppThreatIntel(maliciousPackages = setOf("org.example.app"))
        val known = InstalledAppThreatIntel(
            maliciousPackages = setOf("org.example.app"),
            indicatorSources = mapOf("package:org.example.app" to listOf(InstalledThreatIntelSource("bundled", "epoch-1"))),
        )
        val evidence = unknown.mergedWith(known).matchedEvidence(facts())
        assertEquals(setOf(null, "bundled"), evidence.map { it.sourceId }.toSet())
        assertTrue(evidence.first { it.sourceId == null }.limitations.contains("INDICATOR_SOURCE_UNKNOWN"))
    }

    private fun facts(capabilities: List<InstalledAppCapabilityExposure> = emptyList()) = InstalledAppFacts(
        packageName = "org.example.app",
        label = "Example",
        isSystemApp = false,
        installerPackageName = "org.fdroid.fdroid",
        capabilityExposure = capabilities,
        assessmentStatus = InstalledAppAssessmentStatus.COMPLETE,
    )
}
