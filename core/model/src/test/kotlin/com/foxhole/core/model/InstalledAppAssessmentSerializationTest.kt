package com.foxhole.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppAssessmentSerializationTest {
    @Test fun legacyInventoryDoesNotAcquireACleanAssessment() {
        val entry = Json.decodeFromString<InstalledAppInventoryEntry>("""{"packageName":"org.example.app","label":"Example","riskLevel":"LOW"}""")
        assertEquals(InstalledAppAssessmentStatus.UNAVAILABLE, entry.assessment.status)
        assertEquals(InstalledAppThreatConfidence.UNKNOWN, entry.assessment.threatConfidence)
        assertTrue(entry.assessment.capabilityExposure.isEmpty())
    }

    @Test fun additiveAssessmentRoundTripsWithoutChangingLegacyRiskOrIdentity() {
        val entry = InstalledAppInventoryEntry(
            packageName = "org.example.app", label = "Example", riskLevel = InstalledAppRiskLevel.HIGH,
            assessment = InstalledAppAssessment(
                status = InstalledAppAssessmentStatus.PARTIAL,
                threatConfidence = InstalledAppThreatConfidence.NO_CORROBORATING_EVIDENCE,
                capabilityExposure = listOf(InstalledAppCapabilityExposure(InstalledAppCapability.VPN, CapabilityState.YES)),
            ),
        )
        val encoded = Json.encodeToString(InstalledAppInventoryEntry.serializer(), entry)
        assertEquals(entry, Json.decodeFromString<InstalledAppInventoryEntry>(encoded))
    }
}
