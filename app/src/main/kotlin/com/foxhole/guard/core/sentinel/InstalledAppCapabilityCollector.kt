package com.foxhole.guard.core.sentinel

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.foxhole.core.model.CapabilityState
import com.foxhole.core.model.InstalledAppAssessmentStatus
import com.foxhole.core.model.InstalledAppCapability
import com.foxhole.core.model.InstalledAppCapabilityExposure
import com.foxhole.core.sentinel.detection.InstalledAppFacts
import java.util.concurrent.CancellationException

internal data class InstalledAppCapabilityCollection(
    val exposure: List<InstalledAppCapabilityExposure>,
    val status: InstalledAppAssessmentStatus,
    val limitations: List<String>,
)

internal class InstalledAppCapabilityCollector(private val context: Context) {
    private val manager = context.packageManager

    fun collect(
        application: ApplicationInfo,
        info: PackageInfo,
        facts: InstalledAppFacts,
        components: Map<InstalledAppCapability, List<ComponentInfo>>,
    ): InstalledAppCapabilityCollection {
        val limitations = linkedSetOf<String>()
        fun observed(code: String, block: () -> Boolean?): CapabilityState = try {
            block().toCapabilityState().also { if (it == CapabilityState.UNKNOWN) limitations += code }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            limitations += code
            CapabilityState.UNKNOWN
        }
        fun componentEnabled(capability: InstalledAppCapability): CapabilityState = observed(
            "COMPONENT_ENABLED_STATE_UNAVAILABLE:${capability.name}",
        ) {
            val declared = components[capability].orEmpty()
            if (declared.isEmpty()) null else {
                val states = declared.map { component ->
                    resolveComponentEnabled(application.enabled, component.enabled,
                        manager.getComponentEnabledSetting(ComponentName(component.packageName, component.name)))
                }
                when {
                    CapabilityState.YES in states -> true
                    CapabilityState.UNKNOWN in states -> null
                    else -> false
                }
            }
        }
        fun grant(capability: InstalledAppCapability): CapabilityState = when (capability) {
            InstalledAppCapability.ACCESSIBILITY -> observed("ACCESSIBILITY_GRANT_UNAVAILABLE") {
                context.getSystemService<AccessibilityManager>()?.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                )?.any { it.resolveInfo?.serviceInfo?.packageName == facts.packageName }
            }
            InstalledAppCapability.NOTIFICATION_LISTENER -> observed("NOTIFICATION_LISTENER_GRANT_UNAVAILABLE") {
                facts.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            }
            InstalledAppCapability.DEVICE_ADMIN -> observed("DEVICE_ADMIN_GRANT_UNAVAILABLE") {
                context.getSystemService<DevicePolicyManager>()?.let { policy ->
                    policy.activeAdmins.orEmpty().any { it.packageName == facts.packageName }
                }
            }
            InstalledAppCapability.BATTERY_OPTIMIZATION_EXEMPTION -> observed("BATTERY_EXEMPTION_UNAVAILABLE") {
                context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(facts.packageName)
            }
            else -> CapabilityState.UNKNOWN.also { limitations += "GRANT_NOT_VISIBLE:${capability.name}" }
        }
        val requested = info.requestedPermissions.orEmpty().toSet()
        val declarations = mapOf(
            InstalledAppCapability.ACCESSIBILITY to facts.hasAccessibilityService,
            InstalledAppCapability.NOTIFICATION_LISTENER to facts.hasNotificationListener,
            InstalledAppCapability.DEVICE_ADMIN to facts.hasDeviceAdmin,
            InstalledAppCapability.VPN to facts.hasVpnService,
            InstalledAppCapability.OVERLAY to (Manifest.permission.SYSTEM_ALERT_WINDOW in requested),
            InstalledAppCapability.BATTERY_OPTIMIZATION_EXEMPTION to
                (Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in requested || facts.ignoresBatteryOptimizations),
            InstalledAppCapability.AUTOSTART to facts.canAutostart,
        )
        val exposure = InstalledAppCapability.entries.map { capability ->
            val declared = declarations.getValue(capability)
            InstalledAppCapabilityExposure(
                capability,
                declared.toCapabilityState(),
                when {
                    !declared -> CapabilityState.NO
                    capability in components -> componentEnabled(capability)
                    else -> CapabilityState.UNKNOWN
                },
                if (declared) grant(capability) else CapabilityState.NO,
            )
        }
        return InstalledAppCapabilityCollection(
            exposure,
            if (exposure.any { it.declared == CapabilityState.UNKNOWN ||
                    it.declared == CapabilityState.YES && (it.capability in components && it.enabled == CapabilityState.UNKNOWN || it.granted == CapabilityState.UNKNOWN)
                }) InstalledAppAssessmentStatus.PARTIAL else InstalledAppAssessmentStatus.COMPLETE,
            limitations.toList(),
        )
    }
}

private fun Boolean?.toCapabilityState(): CapabilityState = when (this) {
    true -> CapabilityState.YES
    false -> CapabilityState.NO
    null -> CapabilityState.UNKNOWN
}

internal fun resolveComponentEnabled(applicationEnabled: Boolean, declaredEnabled: Boolean, overrideState: Int): CapabilityState =
    if (!applicationEnabled) CapabilityState.NO else when (overrideState) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> CapabilityState.YES
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> if (declaredEnabled) CapabilityState.YES else CapabilityState.NO
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> CapabilityState.NO
        else -> CapabilityState.UNKNOWN
    }
