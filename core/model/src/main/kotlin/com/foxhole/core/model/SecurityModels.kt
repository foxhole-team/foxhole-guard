package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

// App lock and the installed-app inventory: the security surface's persisted vocabulary.

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

/**
 * Device-local facts shown while a newly installed application is waiting for an explicit
 * quarantine decision. This record lives beside the pending BLOCK rule so the confirmation sheet
 * remains useful after process death even when statistics collection is disabled.
 *
 * It is deliberately excluded from portable backups: installer identity and install time describe
 * this Android device, not the device receiving a restored backup.
 */
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
    // Older persisted records were created only after analysis, hence the compatibility default.
    // The receiver writes false during fail-closed preflight and replaces it after enrichment.
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
    // App-entry events (unlocks with failed attempts) shown interleaved with the
    // package changes when the audit journal is enabled.
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
    // Sentinel: no background-timeout relock at all — the lock is asked only when the
    // process starts cold (device reboot or the OS killing the app). The key material
    // cannot survive process death, so this is the weakest honest option.
    AFTER_REBOOT(-1),

    // Relock the moment the app leaves the foreground.
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

/**
 * App-entry protection. Intentionally free of secrets: the keybox file on disk is the
 * source of truth for "a custom password exists", these fields only steer the UI/lock
 * behavior and must stay readable before unlock (they render the unlock screen).
 */
@Serializable
@Immutable
data class AppLockSettings(
    val mode: AppLockMode = AppLockMode.OFF,
    val lockTimeout: AppLockTimeout = AppLockTimeout.AFTER_REBOOT,
    val guardHosting: GuardHostingMode = GuardHostingMode.ECONOMY,
    val passwordSetAt: Long? = null,
    // Biometrics are an independent toggle on top of either mode: with SYSTEM they add
    // BIOMETRIC_STRONG to the device-credential prompt, with PASSWORD they gate the
    // hardware-bound master-key copy (keybox.bio) that unlocks without the PIN.
    val biometricEnabled: Boolean = false,
    // Show an in-app banner after unlock when failed attempts happened since the last
    // successful entry.
    val authAttemptNoticeEnabled: Boolean = true,
    // The unlock screen's own keypad instead of the system keyboard (default: system
    // keyboard with masked dots).
    val builtInPinPadEnabled: Boolean = false,
    // Built-in keypad only: shuffle the digit layout on every entry.
    val scrambleKeypadDigits: Boolean = true,
    // Re-ask for the PIN / biometrics before actions that weaken protection or erase
    // evidence: journal clears, firewall off, app-audit off, monitoring change.
    val confirmSensitiveActions: Boolean = false,
    // The event-monitoring service (the sealed guard journal, the install watcher and
    // its heartbeat). Independent of the lock: the PIN can guard entry and action
    // confirmation with no monitoring at all.
    val eventMonitoringEnabled: Boolean = true,
)

/** One successful entry into the app preceded by [failedAttempts] rejected tries. */
