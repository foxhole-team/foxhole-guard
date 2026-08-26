package com.foxhole.guard.core.sentinel

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.core.content.getSystemService
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.sentinel.detection.InstalledAppFacts
import com.foxhole.core.sentinel.detection.InstalledAppThreatIntel
import com.foxhole.core.sentinel.detection.scoreInstalledApp
import java.security.MessageDigest

@Immutable
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
    private val threatIntelProvider: () -> InstalledAppThreatIntel = { InstalledAppThreatIntel.EMPTY },

    private val onAnalysisFallback: (packageName: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val powerManager by lazy { appContext.getSystemService<PowerManager>() }

    fun analyzePackage(
        packageName: String,
        fallbackLabel: String = packageName,
        fallbackIsSystemApp: Boolean = false,
    ): InstalledAppSecuritySummary =
        analyzePackageResult(packageName, fallbackLabel).getOrElse { error ->
            onAnalysisFallback(packageName, error)
            val facts =
                collectFacts(
                    packageName = packageName,
                    label = fallbackLabel,
                    isSystemApp = fallbackIsSystemApp,
                    installerPackageName = null,
                    packageInfo = null,
                    ignoresBatteryOptimizations = false,
                    hasAccessibilityService = false,
                    hasNotificationListener = false,
                    hasDeviceAdmin = false,
                    hasVpnService = false,
                    hasBootReceiver = false,
                    signingCertSha256 = emptySet(),
                    signingCertSha1 = emptySet(),
                )
            val assessment = scoreInstalledApp(facts, threatIntelProvider())
            InstalledAppSecuritySummary(
                packageName = packageName,
                label = fallbackLabel,
                isSystemApp = fallbackIsSystemApp,
                installerPackageName = null,
                riskLevel = assessment.riskLevel,
                riskSignals = assessment.riskSignals,
            )
        }

    /** Full analysis with an explicit failure channel for fail-closed quarantine callers. */
    fun analyzePackageResult(
        packageName: String,
        fallbackLabel: String = packageName,
    ): Result<InstalledAppSecuritySummary> =
        runCatching {
            val appInfo = getApplicationInfo(packageName)
            val packageInfo = getPackageInfo(packageName)
            val label = appInfo.loadLabel(packageManager).toString().takeIf(String::isNotBlank) ?: fallbackLabel
            val isSystemApp = appInfo.isSystemApp()
            val installerPackageName = installerPackageName(packageName)
            val facts =
                collectFacts(
                    packageName = packageName,
                    label = label,
                    isSystemApp = isSystemApp,
                    installerPackageName = installerPackageName,
                    packageInfo = packageInfo,
                    ignoresBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
                    hasAccessibilityService = hasServiceCapability(
                        packageName,
                        packageInfo,
                        AccessibilityService.SERVICE_INTERFACE,
                        Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
                    ),
                    hasNotificationListener = hasServiceCapability(
                        packageName,
                        packageInfo,
                        NotificationListenerService.SERVICE_INTERFACE,
                        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
                    ),
                    hasDeviceAdmin = hasReceiverCapability(
                        packageName,
                        packageInfo,
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED,
                        Manifest.permission.BIND_DEVICE_ADMIN,
                    ),
                    hasVpnService = hasServiceCapability(
                        packageName,
                        packageInfo,
                        VpnService.SERVICE_INTERFACE,
                        Manifest.permission.BIND_VPN_SERVICE,
                    ),
                    hasBootReceiver = hasReceiverCapability(
                        packageName,
                        packageInfo,
                        Intent.ACTION_BOOT_COMPLETED,
                        null,
                    ),
                    signingCertSha256 = signingCertDigests(packageName, "SHA-256"),
                    signingCertSha1 = signingCertDigests(packageName, "SHA-1"),
                )
            val assessment = scoreInstalledApp(facts, threatIntelProvider())
            InstalledAppSecuritySummary(
                packageName = packageName,
                label = label,
                isSystemApp = isSystemApp,
                installerPackageName = installerPackageName,
                riskLevel = assessment.riskLevel,
                riskSignals = assessment.riskSignals,
            )
        }

    private fun collectFacts(
        packageName: String,
        label: String,
        isSystemApp: Boolean,
        installerPackageName: String?,
        packageInfo: PackageInfo?,
        ignoresBatteryOptimizations: Boolean,
        hasAccessibilityService: Boolean,
        hasNotificationListener: Boolean,
        hasDeviceAdmin: Boolean,
        hasVpnService: Boolean,
        hasBootReceiver: Boolean,
        signingCertSha256: Set<String>,
        signingCertSha1: Set<String>,
    ): InstalledAppFacts {
        val requestedPermissions = packageInfo?.requestedPermissions.orEmpty().toSet()
        return InstalledAppFacts(
            packageName = packageName,
            label = label,
            isSystemApp = isSystemApp,
            installerPackageName = installerPackageName,
            hasAccessibilityService = hasAccessibilityService,
            hasNotificationListener = hasNotificationListener,
            hasDeviceAdmin = hasDeviceAdmin,
            hasVpnService = hasVpnService,
            requestsOverlay = Manifest.permission.SYSTEM_ALERT_WINDOW in requestedPermissions,
            ignoresBatteryOptimizations = ignoresBatteryOptimizations ||
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in requestedPermissions,
            canAutostart = hasBootReceiver ||
                Manifest.permission.RECEIVE_BOOT_COMPLETED in requestedPermissions,
            signingCertSha256 = signingCertSha256,
            signingCertSha1 = signingCertSha1,
        )
    }

    private fun signingCertDigests(
        packageName: String,
        algorithm: String,
    ): Set<String> =
        runCatching {
            signatures(packageName)
                .map { signature -> signature.toByteArray().digestHex(algorithm) }
                .toSet()
        }.getOrElse { emptySet() }

    private fun signatures(packageName: String): List<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            info.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            }?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
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
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PACKAGE_INFO_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PACKAGE_INFO_FLAGS)
        }

    private fun installerPackageName(packageName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo = packageManager.getInstallSourceInfo(packageName)
                sourceInfo.installingPackageName ?: sourceInfo.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()?.takeIf(String::isNotBlank)

    private fun hasServiceCapability(
        packageName: String,
        packageInfo: PackageInfo,
        action: String,
        requiredPermission: String?,
    ): Boolean {
        val byAction =
            queryIntentServices(packageName, action)
                .any { service -> requiredPermission == null || service.serviceInfo.permission == requiredPermission }
        if (byAction) {
            return true
        }

        return packageInfo.services.orEmpty()
            .any { service -> requiredPermission != null && service.permission == requiredPermission }
    }

    private fun hasReceiverCapability(
        packageName: String,
        packageInfo: PackageInfo,
        action: String,
        requiredPermission: String?,
    ): Boolean {
        val byAction =
            queryBroadcastReceivers(packageName, action)
                .any { receiver -> requiredPermission == null || receiver.activityInfo.permission == requiredPermission }
        if (byAction) {
            return true
        }
        return packageInfo.receivers.orEmpty()
            .any { receiver -> requiredPermission != null && receiver.permission == requiredPermission }
    }

    private fun queryIntentServices(
        packageName: String,
        action: String,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentServices(
            Intent(action).setPackage(packageName),
            PackageManager.ResolveInfoFlags.of(COMPONENT_QUERY_FLAGS.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentServices(Intent(action).setPackage(packageName), COMPONENT_QUERY_FLAGS)
    }

    private fun queryBroadcastReceivers(
        packageName: String,
        action: String,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryBroadcastReceivers(
            Intent(action).setPackage(packageName),
            PackageManager.ResolveInfoFlags.of(COMPONENT_QUERY_FLAGS.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryBroadcastReceivers(Intent(action).setPackage(packageName), COMPONENT_QUERY_FLAGS)
    }
}

private fun ByteArray.digestHex(algorithm: String): String =
    MessageDigest.getInstance(algorithm)
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun ApplicationInfo.isSystemApp(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

private const val PACKAGE_INFO_FLAGS =
    PackageManager.GET_PERMISSIONS or
        PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or
        PackageManager.MATCH_DISABLED_COMPONENTS

private const val COMPONENT_QUERY_FLAGS =
    PackageManager.MATCH_DISABLED_COMPONENTS or
        PackageManager.MATCH_DIRECT_BOOT_AWARE or
        PackageManager.MATCH_DIRECT_BOOT_UNAWARE
