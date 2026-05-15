package com.foxhole.beta.core.security

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import androidx.core.content.getSystemService
import com.foxhole.beta.core.model.InstalledAppRiskLevel
import com.foxhole.beta.core.model.InstalledAppRiskSignal

data class InstalledAppSecuritySummary(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val installerPackageName: String?,
    val riskLevel: InstalledAppRiskLevel,
    val riskSignals: List<InstalledAppRiskSignal>,
)

class InstalledAppSecurityAnalyzer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val powerManager by lazy { appContext.getSystemService<PowerManager>() }

    fun analyzePackage(
        packageName: String,
        fallbackLabel: String = packageName,
        fallbackIsSystemApp: Boolean = false,
    ): InstalledAppSecuritySummary =
        runCatching {
            val appInfo = getApplicationInfo(packageName)
            val packageInfo = getPackageInfo(packageName)
            val label = appInfo.loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank) ?: fallbackLabel
            val isSystemApp = appInfo.isSystemApp()
            val installerPackageName = installerPackageName(packageName)
            val signals =
                installedAppRiskSignals(
                    packageName = packageName,
                    label = label,
                    isSystemApp = isSystemApp,
                    packageInfo = packageInfo,
                    installerPackageName = installerPackageName,
                    ignoresBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
                    hasAccessibilityService = hasServiceCapability(packageName, AccessibilityService.SERVICE_INTERFACE, Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
                    hasNotificationListener = hasServiceCapability(packageName, NotificationListenerService.SERVICE_INTERFACE, Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE),
                    hasDeviceAdmin = hasReceiverCapability(packageName, DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED, Manifest.permission.BIND_DEVICE_ADMIN),
                    hasVpnService = hasServiceCapability(packageName, VpnService.SERVICE_INTERFACE, Manifest.permission.BIND_VPN_SERVICE),
                    hasBootReceiver = hasReceiverCapability(packageName, Intent.ACTION_BOOT_COMPLETED, null),
                )
            InstalledAppSecuritySummary(
                packageName = packageName,
                label = label,
                isSystemApp = isSystemApp,
                installerPackageName = installerPackageName,
                riskLevel = installedAppRiskLevel(signals),
                riskSignals = signals,
            )
        }.getOrElse {
            val signals =
                installedAppRiskSignals(
                    packageName = packageName,
                    label = fallbackLabel,
                    isSystemApp = fallbackIsSystemApp,
                    packageInfo = null,
                    installerPackageName = null,
                    ignoresBatteryOptimizations = false,
                    hasAccessibilityService = false,
                    hasNotificationListener = false,
                    hasDeviceAdmin = false,
                    hasVpnService = false,
                    hasBootReceiver = false,
                )
            InstalledAppSecuritySummary(
                packageName = packageName,
                label = fallbackLabel,
                isSystemApp = fallbackIsSystemApp,
                installerPackageName = null,
                riskLevel = installedAppRiskLevel(signals),
                riskSignals = signals,
            )
        }

    private fun getApplicationInfo(packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }

    private fun getPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageInfoFlags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageInfoFlags.toInt())
        }

    private fun installerPackageName(packageName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
                    ?: packageManager.getInstallSourceInfo(packageName).initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()?.takeIf(String::isNotBlank)

    private fun hasServiceCapability(
        packageName: String,
        action: String,
        requiredPermission: String?,
    ): Boolean {
        val byAction =
            queryIntentServices(packageName, action)
                .any { service -> requiredPermission == null || service.serviceInfo.permission == requiredPermission }
        if (byAction) {
            return true
        }
        val services = runCatching { getPackageInfo(packageName).services.orEmpty() }.getOrElse { emptyArray() }
        return services.any { service -> requiredPermission != null && service.permission == requiredPermission }
    }

    private fun hasReceiverCapability(
        packageName: String,
        action: String,
        requiredPermission: String?,
    ): Boolean {
        val byAction =
            queryBroadcastReceivers(packageName, action)
                .any { receiver -> requiredPermission == null || receiver.activityInfo.permission == requiredPermission }
        if (byAction) {
            return true
        }
        val receivers = runCatching { getPackageInfo(packageName).receivers.orEmpty() }.getOrElse { emptyArray() }
        return receivers.any { receiver -> requiredPermission != null && receiver.permission == requiredPermission }
    }

    private fun queryIntentServices(
        packageName: String,
        action: String,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentServices(
            Intent(action).setPackage(packageName),
            PackageManager.ResolveInfoFlags.of(ComponentQueryFlags),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentServices(Intent(action).setPackage(packageName), ComponentQueryFlags.toInt())
    }

    private fun queryBroadcastReceivers(
        packageName: String,
        action: String,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryBroadcastReceivers(
            Intent(action).setPackage(packageName),
            PackageManager.ResolveInfoFlags.of(ComponentQueryFlags),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryBroadcastReceivers(Intent(action).setPackage(packageName), ComponentQueryFlags.toInt())
    }
}

internal fun installedAppRiskSignals(
    packageName: String,
    label: String,
    isSystemApp: Boolean,
    packageInfo: PackageInfo?,
    installerPackageName: String?,
    ignoresBatteryOptimizations: Boolean,
    hasAccessibilityService: Boolean,
    hasNotificationListener: Boolean,
    hasDeviceAdmin: Boolean,
    hasVpnService: Boolean,
    hasBootReceiver: Boolean,
): List<InstalledAppRiskSignal> =
    buildList {
        val requestedPermissions = packageInfo?.requestedPermissions.orEmpty().toSet()
        if (hasAccessibilityService) add(InstalledAppRiskSignal.ACCESSIBILITY_SERVICE)
        if (hasNotificationListener) add(InstalledAppRiskSignal.NOTIFICATION_LISTENER)
        if (hasDeviceAdmin) add(InstalledAppRiskSignal.DEVICE_ADMIN)
        if (hasVpnService) add(InstalledAppRiskSignal.VPN_SERVICE)
        if (Manifest.permission.SYSTEM_ALERT_WINDOW in requestedPermissions) add(InstalledAppRiskSignal.OVERLAY_PERMISSION)
        if (
            ignoresBatteryOptimizations ||
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in requestedPermissions
        ) {
            add(InstalledAppRiskSignal.BATTERY_OPTIMIZATION_IGNORE)
        }
        if (hasBootReceiver || Manifest.permission.RECEIVE_BOOT_COMPLETED in requestedPermissions) {
            add(InstalledAppRiskSignal.AUTOSTART)
        }
        if (!isSystemApp && !installerPackageName.isTrustedInstaller()) {
            add(InstalledAppRiskSignal.UNKNOWN_INSTALLER)
        }
        if (!isSystemApp && looksLikeSystemAppName(packageName, label)) {
            add(InstalledAppRiskSignal.SYSTEM_LIKE_NAME)
        }
    }.distinct()

internal fun installedAppRiskLevel(signals: List<InstalledAppRiskSignal>): InstalledAppRiskLevel =
    when {
        signals.any {
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

private fun ApplicationInfo.isSystemApp(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

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

private const val PackageInfoFlags: Long =
    (PackageManager.GET_PERMISSIONS or
        PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or
        PackageManager.MATCH_DISABLED_COMPONENTS).toLong()

private const val ComponentQueryFlags: Long =
    (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong()
