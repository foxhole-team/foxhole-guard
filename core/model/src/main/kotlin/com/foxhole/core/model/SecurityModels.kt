package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class InstalledAppChangeType {
    INSTALLED,
    REMOVED,
}

@Serializable
enum class InstalledAppRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
enum class InstalledAppRiskSignal {
    ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER,
    DEVICE_ADMIN,
    VPN_SERVICE,
    OVERLAY_PERMISSION,
    BATTERY_OPTIMIZATION_IGNORE,
    AUTOSTART,
    UNKNOWN_INSTALLER,
    SYSTEM_LIKE_NAME,

    /** The package name or signing certificate matched FoxHole Sentinel's threat-intel feed. */
    KNOWN_THREAT,
}

@Serializable
@Immutable
data class PendingQuarantineAppDetails(
    val packageName: String,
    val label: String,
    val firstInstallTime: Long? = null,
    val detectedAt: Long,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),

    val analysisComplete: Boolean = true,
)

@Serializable
@Immutable
data class InstalledAppInventoryEntry(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

@Serializable
@Immutable
data class InstalledAppInventoryChange(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val type: InstalledAppChangeType,
    val detectedAt: Long,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

@Serializable
@Immutable
data class InstalledAppInventoryAudit(
    val capturedAt: Long = 0L,
    val packages: List<InstalledAppInventoryEntry> = emptyList(),
    val recentChanges: List<InstalledAppInventoryChange> = emptyList(),

)

@Serializable
enum class AppLockMode {
    OFF,
    SYSTEM,
    PASSWORD,
}

@Serializable
enum class AppLockTimeout(
    val minutes: Int,
) {
    AFTER_REBOOT(-1),

    IMMEDIATE(0),
    MIN_15(15),
    MIN_30(30),
    HOUR_1(60),
}

@Serializable
enum class GuardHostingMode {
    ECONOMY,
    REINFORCED,
}

@Serializable
@Immutable
data class AppLockSettings(
    val mode: AppLockMode = AppLockMode.OFF,
    val lockTimeout: AppLockTimeout = AppLockTimeout.AFTER_REBOOT,
    val guardHosting: GuardHostingMode = GuardHostingMode.ECONOMY,
    val passwordSetAt: Long? = null,

    val biometricEnabled: Boolean = false,

    val authAttemptNoticeEnabled: Boolean = true,

    val builtInPinPadEnabled: Boolean = false,

    val scrambleKeypadDigits: Boolean = true,

    val confirmSensitiveActions: Boolean = false,

    val eventMonitoringEnabled: Boolean = true,
)
