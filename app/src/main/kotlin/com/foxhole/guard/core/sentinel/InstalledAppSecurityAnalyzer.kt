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
import android.content.pm.ComponentInfo
import com.foxhole.core.model.InstalledAppAssessment
import com.foxhole.core.model.InstalledAppAssessmentStatus
import com.foxhole.core.model.InstalledAppCapability
import com.foxhole.core.model.InstalledAppCapabilityExposure
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
    val assessment: InstalledAppAssessment = InstalledAppAssessment(),
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
                ).copy(
                    assessmentStatus = InstalledAppAssessmentStatus.UNAVAILABLE,
                    collectionLimitations = listOf("PACKAGE_COLLECTION_UNAVAILABLE"),
                    capabilityExposure = InstalledAppCapability.entries.map { InstalledAppCapabilityExposure(it) },
                )
            val assessment = scoreInstalledApp(facts, threatIntelProvider())
            InstalledAppSecuritySummary(
                packageName = packageName,
                label = fallbackLabel,
                isSystemApp = fallbackIsSystemApp,
                installerPackageName = null,
                riskLevel = assessment.riskLevel,
                riskSignals = assessment.riskSignals,
                assessment = assessment.assessment,
            )
        }

    /** Full analysis with an explicit failure channel for fail-closed quarantine callers. */
    fun analyzePackageResult(
        packageName: String,
        fallbackLabel: String = packageName,
    ): Result<InstalledAppSecuritySummary> =
        sentinelAttempt {
            val appInfo = getApplicationInfo(packageName)
            val packageInfo = getPackageInfo(packageName)
            val packageApplication = checkNotNull(packageInfo.applicationInfo) { "Package application identity unavailable" }
            check(appInfo.uid == packageApplication.uid && appInfo.sourceDir == packageApplication.sourceDir &&
                appInfo.splitSourceDirs.orEmpty().contentEquals(packageApplication.splitSourceDirs.orEmpty())
            ) { "Package changed before collection" }
            val label = appInfo.loadLabel(packageManager).toString().takeIf(String::isNotBlank) ?: fallbackLabel
            val isSystemApp = appInfo.isSystemApp()
            val installerPackageName = installerPackageName(packageName)
            val signing = signatures(packageInfo)
            val signingCertificateBytes = signing.current.map(Signature::toByteArray)
            val components = mapOf(
                InstalledAppCapability.ACCESSIBILITY to serviceComponents(packageName, packageInfo, AccessibilityService.SERVICE_INTERFACE, Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
                InstalledAppCapability.NOTIFICATION_LISTENER to serviceComponents(packageName, packageInfo, NotificationListenerService.SERVICE_INTERFACE, Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE),
                InstalledAppCapability.DEVICE_ADMIN to receiverComponents(packageName, packageInfo, DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED, Manifest.permission.BIND_DEVICE_ADMIN),
                InstalledAppCapability.VPN to serviceComponents(packageName, packageInfo, VpnService.SERVICE_INTERFACE, Manifest.permission.BIND_VPN_SERVICE),
                InstalledAppCapability.AUTOSTART to receiverComponents(packageName, packageInfo, Intent.ACTION_BOOT_COMPLETED, null),
            )
            val facts =
                collectFacts(
                    packageName = packageName,
                    label = label,
                    isSystemApp = isSystemApp,
                    installerPackageName = installerPackageName,
                    packageInfo = packageInfo,
                    ignoresBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
                    hasAccessibilityService = components.getValue(InstalledAppCapability.ACCESSIBILITY).isNotEmpty(),
                    hasNotificationListener = components.getValue(InstalledAppCapability.NOTIFICATION_LISTENER).isNotEmpty(),
                    hasDeviceAdmin = components.getValue(InstalledAppCapability.DEVICE_ADMIN).isNotEmpty(),
                    hasVpnService = components.getValue(InstalledAppCapability.VPN).isNotEmpty(),
                    hasBootReceiver = components.getValue(InstalledAppCapability.AUTOSTART).isNotEmpty(),
                    signingCertSha256 = signingCertDigests(signingCertificateBytes, "SHA-256"),
                    signingCertSha1 = signingCertDigests(signingCertificateBytes, "SHA-1"),
                ).copy(
                    historicalSigningCertSha256 = signingCertDigests(signing.historical.map(Signature::toByteArray), "SHA-256"),
                    historicalSigningCertSha1 = signingCertDigests(signing.historical.map(Signature::toByteArray), "SHA-1"),
                    observedAtMs = System.currentTimeMillis(),
                )
            val collected = InstalledAppCapabilityCollector(appContext).collect(appInfo, packageInfo, facts, components)
            val after = getPackageInfo(packageName)
            check(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo) ==
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(after) &&
                packageInfo.lastUpdateTime == after.lastUpdateTime &&
                packageInfo.firstInstallTime == after.firstInstallTime &&
                packageInfo.applicationInfo?.uid == after.applicationInfo?.uid &&
                packageInfo.applicationInfo?.sourceDir == after.applicationInfo?.sourceDir &&
                packageInfo.applicationInfo?.splitSourceDirs.orEmpty().contentEquals(after.applicationInfo?.splitSourceDirs.orEmpty())
            ) { "Package changed during collection" }
            val assessment = scoreInstalledApp(
                facts.copy(
                    capabilityExposure = collected.exposure,
                    assessmentStatus = collected.status,
                    collectionLimitations = collected.limitations,
                ),
                threatIntelProvider(),
            )
            InstalledAppSecuritySummary(
                packageName = packageName,
                label = label,
                isSystemApp = isSystemApp,
                installerPackageName = installerPackageName,
                riskLevel = assessment.riskLevel,
                riskSignals = assessment.riskSignals,
                assessment = assessment.assessment,
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
        signingCertificateBytes: List<ByteArray>,
        algorithm: String,
    ): Set<String> =
        signingCertificateBytes.map { bytes -> bytes.digestHex(algorithm) }.toSet()

    private data class SigningCertificates(val current: List<Signature>, val historical: List<Signature>)

    private fun signatures(info: PackageInfo): SigningCertificates =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signingInfo ->
                val current = signingInfo.apkContentsSigners?.toList().orEmpty()
                check(current.isNotEmpty()) { "Current signing certificate unavailable" }
                val history = if (signingInfo.hasMultipleSigners()) emptyList() else
                    signingInfo.signingCertificateHistory?.toList().orEmpty().filter { it !in current }
                SigningCertificates(current, history)
            } ?: error("Signing certificates unavailable")
        } else {
            @Suppress("DEPRECATION")
            val current = info.signatures?.toList().orEmpty()
            check(current.isNotEmpty()) { "Current signing certificate unavailable" }
            SigningCertificates(current, emptyList())
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
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(packageInfoFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, packageInfoFlags())
        }

    private fun installerPackageName(packageName: String): String? =
        sentinelAttempt {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo = packageManager.getInstallSourceInfo(packageName)
                sourceInfo.installingPackageName ?: sourceInfo.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()?.takeIf(String::isNotBlank)

    private fun serviceComponents(
        packageName: String,
        packageInfo: PackageInfo,
        action: String,
        requiredPermission: String?,
    ): List<ComponentInfo> = (
        queryIntentServices(packageName, action).mapNotNull { it.serviceInfo }
            .filter { requiredPermission == null || it.permission == requiredPermission } +
            packageInfo.services.orEmpty().filter { requiredPermission != null && it.permission == requiredPermission }
        ).distinctBy { it.name }

    private fun receiverComponents(
        packageName: String,
        packageInfo: PackageInfo,
        action: String,
        requiredPermission: String?,
    ): List<ComponentInfo> = (
        queryBroadcastReceivers(packageName, action).mapNotNull { it.activityInfo }
            .filter { requiredPermission == null || it.permission == requiredPermission } +
            packageInfo.receivers.orEmpty().filter { requiredPermission != null && it.permission == requiredPermission }
        ).distinctBy { it.name }

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

@Suppress("DEPRECATION")
private fun packageInfoFlags(): Int = PACKAGE_INFO_FLAGS or
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
