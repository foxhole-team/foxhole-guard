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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class InstalledAppChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as? FoxholeApplication ?: return
        val packageChange = intent.packageInventoryChangeOrNull() ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            finishPendingBroadcast(
                timeoutMs = PACKAGE_CHANGE_TIMEOUT_MS,
                finish = pendingResult::finish,
                onTimeout = {
                    app.container.diagnosticsLogger.record(
                        "app-inventory",
                        "package change receiver timed out package=${packageChange.packageName}",
                    )
                },
            ) {
                runCatching {
                    val packageInfo = app.packageInventoryInfo(packageChange.packageName, packageChange.type)
                    app.container.settingsRepository.recordInstalledAppChange(
                        packageName = packageChange.packageName,
                        label = packageInfo.label,
                        isSystemApp = packageInfo.isSystemApp,
                        type = packageChange.type,
                    )
                }.onFailure { error ->
                    app.container.diagnosticsLogger.record(
                        "app-inventory",
                        "package change record failed: ${error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private companion object {
        const val PACKAGE_CHANGE_TIMEOUT_MS = 8_000L
    }
}

private data class PackageInventoryChange(
    val packageName: String,
    val type: InstalledAppChangeType,
)

private fun Intent.packageInventoryChangeOrNull(): PackageInventoryChange? {
    val packageName = data?.schemeSpecificPart?.takeIf(String::isNotBlank)
    val changeType =
        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> InstalledAppChangeType.INSTALLED
            Intent.ACTION_PACKAGE_REMOVED -> InstalledAppChangeType.REMOVED
            else -> null
        }
    return changeType
        ?.takeUnless { getBooleanExtra(Intent.EXTRA_REPLACING, false) }
        ?.let { type -> packageName?.let { name -> PackageInventoryChange(name, type) } }
}

internal suspend fun finishPendingBroadcast(
    timeoutMs: Long,
    finish: () -> Unit,
    onTimeout: () -> Unit = {},
    block: suspend () -> Unit,
) {
    try {
        withTimeout(timeoutMs) {
            block()
        }
    } catch (_: TimeoutCancellationException) {
        onTimeout()
    } finally {
        finish()
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
