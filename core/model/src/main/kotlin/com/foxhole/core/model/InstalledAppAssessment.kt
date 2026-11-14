package com.foxhole.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class InstalledAppAssessmentStatus { COMPLETE, PARTIAL, UNAVAILABLE }

@Serializable
enum class InstalledAppThreatConfidence {
    UNKNOWN,
    NO_CORROBORATING_EVIDENCE,
    LIMITED_INDICATOR_MATCH,
    STRONG_INDICATOR_MATCH,
}

@Serializable
enum class InstalledAppCapability {
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    DEVICE_ADMIN,
    VPN,
    OVERLAY,
    BATTERY_OPTIMIZATION_EXEMPTION,
    AUTOSTART,
}

@Serializable
enum class CapabilityState { UNKNOWN, NO, YES }

@Serializable
data class InstalledAppCapabilityExposure(
    val capability: InstalledAppCapability,
    val declared: CapabilityState = CapabilityState.UNKNOWN,
    val enabled: CapabilityState = CapabilityState.UNKNOWN,
    val granted: CapabilityState = CapabilityState.UNKNOWN,
)

@Serializable
enum class InstalledAppEvidenceKind {
    PACKAGE_NAME,
    CURRENT_CERTIFICATE_SHA256,
    HISTORICAL_CERTIFICATE_SHA256,
    CURRENT_CERTIFICATE_SHA1,
    HISTORICAL_CERTIFICATE_SHA1,
}

@Serializable
data class InstalledAppMatchedEvidence(
    val kind: InstalledAppEvidenceKind,
    val observedValue: String,
    val sourceId: String? = null,
    val feedVersion: String? = null,
    val observedAtMs: Long? = null,
    val limitations: List<String> = emptyList(),
)

@Serializable
data class InstalledAppAssessment(
    val status: InstalledAppAssessmentStatus = InstalledAppAssessmentStatus.UNAVAILABLE,
    val threatConfidence: InstalledAppThreatConfidence = InstalledAppThreatConfidence.UNKNOWN,
    val capabilityExposure: List<InstalledAppCapabilityExposure> = emptyList(),
    val matchedEvidence: List<InstalledAppMatchedEvidence> = emptyList(),
    val limitations: List<String> = emptyList(),
)
