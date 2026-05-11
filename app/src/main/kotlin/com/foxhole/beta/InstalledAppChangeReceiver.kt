package com.foxhole.beta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.foxhole.beta.core.model.InstalledAppChangeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InstalledAppChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return
        }
        val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
        val app = context.applicationContext as? FoxholeApplication ?: return
        val changeType =
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> InstalledAppChangeType.INSTALLED
                Intent.ACTION_PACKAGE_REMOVED -> InstalledAppChangeType.REMOVED
                else -> return
            }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val packageInfo = app.packageInventoryInfo(packageName, changeType)
                app.container.settingsRepository.recordInstalledAppChange(
                    packageName = packageName,
                    label = packageInfo.label,
                    isSystemApp = packageInfo.isSystemApp,
                    type = changeType,
                )
            }
            pendingResult.finish()
        }
    }
}

private data class PackageInventoryInfo(
    val label: String,
    val isSystemApp: Boolean,
)

private fun Context.packageInventoryInfo(
    packageName: String,
    changeType: InstalledAppChangeType,
): PackageInventoryInfo {
    val packageManager = packageManager
    val appInfo =
        if (changeType == InstalledAppChangeType.INSTALLED) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
            }.getOrNull()
        } else {
            null
        }
    val previous =
        (applicationContext as? FoxholeApplication)
            ?.container
            ?.settingsRepository
            ?.settings
            ?.value
            ?.installedAppInventoryAudit
            ?.packages
            ?.firstOrNull { app -> app.packageName == packageName }
    return PackageInventoryInfo(
        label = appInfo?.loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank)
            ?: previous?.label
            ?: packageName,
        isSystemApp = appInfo?.isSystemApp() ?: previous?.isSystemApp ?: false,
    )
}

private fun ApplicationInfo.isSystemApp(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
