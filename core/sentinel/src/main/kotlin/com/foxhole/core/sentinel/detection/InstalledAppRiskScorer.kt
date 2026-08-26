package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
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
)

data class InstalledAppThreatIntel(
    val maliciousPackages: Set<String> = emptySet(),
    val maliciousCertSha256: Set<String> = emptySet(),
    val maliciousCertSha1: Set<String> = emptySet(),
) {
    private val normalizedPackages = maliciousPackages.mapTo(HashSet()) { it.lowercase().trim() }
    private val normalizedCerts = maliciousCertSha256.mapTo(HashSet()) { it.lowercase().trim() }
    private val normalizedCertsSha1 = maliciousCertSha1.mapTo(HashSet()) { it.lowercase().trim() }

    fun matches(facts: InstalledAppFacts): Boolean =
        facts.packageName.lowercase().trim() in normalizedPackages ||
            facts.signingCertSha256.any { it.lowercase().trim() in normalizedCerts } ||
            facts.signingCertSha1.any { it.lowercase().trim() in normalizedCertsSha1 }

    val isEmpty: Boolean
        get() = normalizedPackages.isEmpty() && normalizedCerts.isEmpty() && normalizedCertsSha1.isEmpty()

    fun mergedWith(other: InstalledAppThreatIntel): InstalledAppThreatIntel =
        InstalledAppThreatIntel(
            maliciousPackages = maliciousPackages + other.maliciousPackages,
            maliciousCertSha256 = maliciousCertSha256 + other.maliciousCertSha256,
            maliciousCertSha1 = maliciousCertSha1 + other.maliciousCertSha1,
        )

    companion object {
        val EMPTY = InstalledAppThreatIntel()
    }
}

fun ThreatIntelDocument.toThreatIntel(): InstalledAppThreatIntel =
    InstalledAppThreatIntel(
        maliciousPackages = packages.filter(String::isNotBlank).toSet(),
        maliciousCertSha256 = certs.filter(String::isNotBlank).toSet(),
        maliciousCertSha1 = certsSha1.filter(String::isNotBlank).toSet(),
    )

data class InstalledAppRiskAssessment(
    val riskSignals: List<InstalledAppRiskSignal>,
    val riskLevel: InstalledAppRiskLevel,
)

fun scoreInstalledApp(
    facts: InstalledAppFacts,
    threatIntel: InstalledAppThreatIntel = InstalledAppThreatIntel.EMPTY,
): InstalledAppRiskAssessment {
    val signals = installedAppRiskSignals(facts, threatIntel)
    return InstalledAppRiskAssessment(
        riskSignals = signals,
        riskLevel = installedAppRiskLevel(signals),
    )
}

internal fun installedAppRiskSignals(
    facts: InstalledAppFacts,
    threatIntel: InstalledAppThreatIntel = InstalledAppThreatIntel.EMPTY,
): List<InstalledAppRiskSignal> =
    buildList {
        if (threatIntel.matches(facts)) add(InstalledAppRiskSignal.KNOWN_THREAT)
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
