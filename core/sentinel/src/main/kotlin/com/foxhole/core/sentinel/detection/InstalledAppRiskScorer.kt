package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.CapabilityState
import com.foxhole.core.model.InstalledAppAssessment
import com.foxhole.core.model.InstalledAppAssessmentStatus
import com.foxhole.core.model.InstalledAppCapability
import com.foxhole.core.model.InstalledAppCapabilityExposure
import com.foxhole.core.model.InstalledAppEvidenceKind
import com.foxhole.core.model.InstalledAppMatchedEvidence
import com.foxhole.core.model.InstalledAppThreatConfidence
import com.foxhole.core.model.ThreatIntelDocument

data class InstalledAppFacts(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val installerPackageName: String?,
    val hasAccessibilityService: Boolean = false,
    val hasNotificationListener: Boolean = false,
    val hasDeviceAdmin: Boolean = false,
    val hasVpnService: Boolean = false,
    val requestsOverlay: Boolean = false,
    val ignoresBatteryOptimizations: Boolean = false,
    val canAutostart: Boolean = false,
    /** Lower-cased hex SHA-256 of each signing certificate, for threat-intel matching. */
    val signingCertSha256: Set<String> = emptySet(),
    /** Lower-cased hex SHA-1 of the same certificates — the digest public feeds publish. */
    val signingCertSha1: Set<String> = emptySet(),
    val historicalSigningCertSha256: Set<String> = emptySet(),
    val historicalSigningCertSha1: Set<String> = emptySet(),
    val capabilityExposure: List<InstalledAppCapabilityExposure> = emptyList(),
    val assessmentStatus: InstalledAppAssessmentStatus = InstalledAppAssessmentStatus.PARTIAL,
    val collectionLimitations: List<String> = emptyList(),
    val observedAtMs: Long? = null,
)

data class InstalledThreatIntelSource(val sourceId: String? = null, val feedVersion: String? = null)

data class InstalledAppThreatIntel(
    val maliciousPackages: Set<String> = emptySet(),
    val maliciousCertSha256: Set<String> = emptySet(),
    val maliciousCertSha1: Set<String> = emptySet(),
    val available: Boolean = true,
    val indicatorSources: Map<String, List<InstalledThreatIntelSource>> = emptyMap(),
) {
    private val normalizedPackages = maliciousPackages.mapTo(HashSet()) { it.lowercase().trim() }
    private val normalizedCerts = maliciousCertSha256.mapTo(HashSet()) { it.lowercase().trim() }
    private val normalizedCertsSha1 = maliciousCertSha1.mapTo(HashSet()) { it.lowercase().trim() }

    fun matches(facts: InstalledAppFacts): Boolean = matchedEvidence(facts).isNotEmpty()

    fun matchedEvidence(facts: InstalledAppFacts): List<InstalledAppMatchedEvidence> = buildList {
        fun addMatch(kind: InstalledAppEvidenceKind, value: String, type: String, indicators: Set<String>) {
            val normalized = value.lowercase().trim()
            if (normalized !in indicators) return
            val sources = indicatorSources["$type:$normalized"].orEmpty().ifEmpty { listOf(InstalledThreatIntelSource()) }
            for (source in sources.distinct()) add(
                InstalledAppMatchedEvidence(
                    kind, normalized, source.sourceId, source.feedVersion, facts.observedAtMs,
                    buildList {
                        add("INDICATOR_MATCH_DOES_NOT_PROVE_EXECUTION")
                        if (source.sourceId == null) add("INDICATOR_SOURCE_UNKNOWN")
                        if (source.feedVersion == null) add("FEED_VERSION_UNKNOWN")
                        if (kind == InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA256 ||
                            kind == InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA1
                        ) add("HISTORICAL_SIGNER_NOT_CURRENT_SIGNER")
                        if (kind == InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA1 ||
                            kind == InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA1
                        ) add("LEGACY_SHA1_INDICATOR")
                    },
                ),
            )
        }
        addMatch(InstalledAppEvidenceKind.PACKAGE_NAME, facts.packageName, "package", normalizedPackages)
        facts.signingCertSha256.forEach { addMatch(InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA256, it, "sha256", normalizedCerts) }
        facts.historicalSigningCertSha256.forEach { addMatch(InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA256, it, "sha256", normalizedCerts) }
        facts.signingCertSha1.forEach { addMatch(InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA1, it, "sha1", normalizedCertsSha1) }
        facts.historicalSigningCertSha1.forEach { addMatch(InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA1, it, "sha1", normalizedCertsSha1) }
    }.distinct()

    val isEmpty: Boolean
        get() = normalizedPackages.isEmpty() && normalizedCerts.isEmpty() && normalizedCertsSha1.isEmpty()

    fun mergedWith(other: InstalledAppThreatIntel): InstalledAppThreatIntel =
        InstalledAppThreatIntel(
            maliciousPackages = maliciousPackages + other.maliciousPackages,
            maliciousCertSha256 = maliciousCertSha256 + other.maliciousCertSha256,
            maliciousCertSha1 = maliciousCertSha1 + other.maliciousCertSha1,
            available = available || other.available,
            indicatorSources = (indicatorKeys() + other.indicatorKeys()).associateWith { key ->
                (sourcesFor(key) + other.sourcesFor(key)).distinct()
            },
        )

    private fun indicatorKeys(): Set<String> = buildSet {
        normalizedPackages.forEach { add("package:$it") }
        normalizedCerts.forEach { add("sha256:$it") }
        normalizedCertsSha1.forEach { add("sha1:$it") }
    }

    private fun sourcesFor(key: String): List<InstalledThreatIntelSource> =
        if (when {
                key.startsWith("package:") -> key.removePrefix("package:") in normalizedPackages
                key.startsWith("sha256:") -> key.removePrefix("sha256:") in normalizedCerts
                key.startsWith("sha1:") -> key.removePrefix("sha1:") in normalizedCertsSha1
                else -> false
            }) indicatorSources[key].orEmpty().ifEmpty { listOf(InstalledThreatIntelSource()) }
        else emptyList()

    companion object {
        val EMPTY = InstalledAppThreatIntel(available = false)
    }
}

fun ThreatIntelDocument.toThreatIntel(source: InstalledThreatIntelSource? = null): InstalledAppThreatIntel =
    InstalledAppThreatIntel(
        maliciousPackages = packages.filter(String::isNotBlank).toSet(),
        maliciousCertSha256 = certs.filter(String::isNotBlank).toSet(),
        maliciousCertSha1 = certsSha1.filter(String::isNotBlank).toSet(),
        indicatorSources = if (source == null) emptyMap() else buildMap {
            packages.filter(String::isNotBlank).forEach { put("package:${it.lowercase().trim()}", listOf(source)) }
            certs.filter(String::isNotBlank).forEach { put("sha256:${it.lowercase().trim()}", listOf(source)) }
            certsSha1.filter(String::isNotBlank).forEach { put("sha1:${it.lowercase().trim()}", listOf(source)) }
        },
    )

data class InstalledAppRiskAssessment(
    val riskSignals: List<InstalledAppRiskSignal>,
    val riskLevel: InstalledAppRiskLevel,
    val assessment: InstalledAppAssessment = InstalledAppAssessment(),
)

fun scoreInstalledApp(
    facts: InstalledAppFacts,
    threatIntel: InstalledAppThreatIntel = InstalledAppThreatIntel.EMPTY,
): InstalledAppRiskAssessment {
    val evidence = threatIntel.matchedEvidence(facts)
    val signals = installedAppRiskSignals(facts, threatIntel, evidence.isNotEmpty())
    return InstalledAppRiskAssessment(
        riskSignals = signals,
        riskLevel = installedAppRiskLevel(signals),
        assessment = InstalledAppAssessment(
            status = facts.assessmentStatus,
            threatConfidence = when {
                evidence.any { it.kind == InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA256 } ->
                    InstalledAppThreatConfidence.STRONG_INDICATOR_MATCH
                evidence.isNotEmpty() -> InstalledAppThreatConfidence.LIMITED_INDICATOR_MATCH
                !threatIntel.available || facts.assessmentStatus == InstalledAppAssessmentStatus.UNAVAILABLE ->
                    InstalledAppThreatConfidence.UNKNOWN
                else -> InstalledAppThreatConfidence.NO_CORROBORATING_EVIDENCE
            },
            capabilityExposure = facts.capabilityExposure.ifEmpty { legacyCapabilityExposure(facts) },
            matchedEvidence = evidence,
            limitations = (facts.collectionLimitations + if (threatIntel.available) emptyList() else listOf("THREAT_INTEL_UNAVAILABLE")).distinct(),
        ),
    )
}

internal fun installedAppRiskSignals(
    facts: InstalledAppFacts,
    threatIntel: InstalledAppThreatIntel = InstalledAppThreatIntel.EMPTY,
    knownThreat: Boolean = threatIntel.matches(facts),
): List<InstalledAppRiskSignal> =
    buildList {
        if (knownThreat) add(InstalledAppRiskSignal.KNOWN_THREAT)
        if (facts.hasAccessibilityService) add(InstalledAppRiskSignal.ACCESSIBILITY_SERVICE)
        if (facts.hasNotificationListener) add(InstalledAppRiskSignal.NOTIFICATION_LISTENER)
        if (facts.hasDeviceAdmin) add(InstalledAppRiskSignal.DEVICE_ADMIN)
        if (facts.hasVpnService) add(InstalledAppRiskSignal.VPN_SERVICE)
        if (facts.requestsOverlay) add(InstalledAppRiskSignal.OVERLAY_PERMISSION)
        if (facts.ignoresBatteryOptimizations) add(InstalledAppRiskSignal.BATTERY_OPTIMIZATION_IGNORE)
        if (facts.canAutostart) add(InstalledAppRiskSignal.AUTOSTART)
        if (!facts.isSystemApp && !facts.installerPackageName.isTrustedInstaller()) {
            add(InstalledAppRiskSignal.UNKNOWN_INSTALLER)
        }
        if (!facts.isSystemApp && looksLikeSystemAppName(facts.packageName, facts.label)) {
            add(InstalledAppRiskSignal.SYSTEM_LIKE_NAME)
        }
    }.distinct()

internal fun installedAppRiskLevel(signals: List<InstalledAppRiskSignal>): InstalledAppRiskLevel =
    when {
        signals.any {
            it == InstalledAppRiskSignal.KNOWN_THREAT ||
                it == InstalledAppRiskSignal.ACCESSIBILITY_SERVICE ||
                it == InstalledAppRiskSignal.DEVICE_ADMIN ||
                it == InstalledAppRiskSignal.VPN_SERVICE ||
                it == InstalledAppRiskSignal.OVERLAY_PERMISSION
        } -> InstalledAppRiskLevel.HIGH
        signals.size >= 3 -> InstalledAppRiskLevel.HIGH
        signals.isNotEmpty() -> InstalledAppRiskLevel.MEDIUM
        else -> InstalledAppRiskLevel.LOW
    }

private fun legacyCapabilityExposure(facts: InstalledAppFacts): List<InstalledAppCapabilityExposure> =
    listOf(
        InstalledAppCapability.ACCESSIBILITY to facts.hasAccessibilityService,
        InstalledAppCapability.NOTIFICATION_LISTENER to facts.hasNotificationListener,
        InstalledAppCapability.DEVICE_ADMIN to facts.hasDeviceAdmin,
        InstalledAppCapability.VPN to facts.hasVpnService,
        InstalledAppCapability.OVERLAY to facts.requestsOverlay,
        InstalledAppCapability.BATTERY_OPTIMIZATION_EXEMPTION to facts.ignoresBatteryOptimizations,
        InstalledAppCapability.AUTOSTART to facts.canAutostart,
    ).map { (capability, declared) ->
        InstalledAppCapabilityExposure(
            capability,
            declared = if (declared) CapabilityState.YES else CapabilityState.UNKNOWN,
        )
    }

private fun String?.isTrustedInstaller(): Boolean {
    val normalized = this?.lowercase()?.trim().orEmpty()
    return normalized in TrustedInstallers
}

private fun looksLikeSystemAppName(
    packageName: String,
    label: String,
): Boolean {
    val normalizedLabel = label.lowercase().trim()
    val normalizedPackage = packageName.lowercase().trim()
    return SystemLikeLabels.any { pattern -> pattern in normalizedLabel } ||
        normalizedPackage.startsWith("com.android.") ||
        normalizedPackage.startsWith("com.google.android.")
}

private val TrustedInstallers =
    setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.apps.nbu.files",
        "com.google.android.feedback",
        "com.android.vending",
        "com.google.android.finsky",
        "org.fdroid.fdroid",
        "com.aurora.store",
    )

private val SystemLikeLabels =
    listOf(
        "system update",
        "security update",
        "android system",
        "google service",
        "google services",
        "play service",
        "documents",
        "package installer",
        "настройки",
        "обновление системы",
        "системное обновление",
        "сервис google",
        "документы",
    )
