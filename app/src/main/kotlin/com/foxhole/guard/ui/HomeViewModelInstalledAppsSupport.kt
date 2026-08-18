package com.foxhole.guard.ui
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.core.settings.recordInstalledAppInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun HomeViewModel.loadInstalledApps(force: Boolean = false) {
    val activeLoadJob = installedAppsLoadJob
    val hasLoadedApps = installedAppsLoadedMutable.value
    val loadInProgress = installedAppsLoadingMutable.value || activeLoadJob?.isActive == true
    if (!force && (hasLoadedApps || loadInProgress)) {
        return
    }
    if (force) {
        activeLoadJob?.cancel()
    }
    installedAppsLoadingMutable.value = true
    val loadJob =
        viewModelScope.launch {
            try {
                val installed =
                    withContext(Dispatchers.IO) {
                        val application = getApplication<Application>()
                        val packageManager = application.packageManager
                        val installedApplications =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                packageManager.getInstalledApplications(
                                    PackageManager.ApplicationInfoFlags.of(0),
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                packageManager.getInstalledApplications(0)
                            }
                        installedApplications
                            .asSequence()
                            .filter { applicationInfo ->
                                packageManager.getLaunchIntentForPackage(applicationInfo.packageName) != null ||
                                    applicationInfo.enabled
                            }
                            .associateBy(ApplicationInfo::packageName)
                            .values
                            .filterNot { it.packageName == application.packageName }
                            .map { applicationInfo ->
                                val packageName = applicationInfo.packageName
                                val packageInfo = packageManager.packageInfoOrNull(packageName)
                                val flags = applicationInfo.flags
                                val isSystemApp =
                                    flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                                        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                                InstalledAppOption(
                                    packageName = packageName,
                                    label = applicationInfo.loadLabel(
                                        packageManager
                                    ).toString().ifBlank { packageName },
                                    isSystemApp = isSystemApp,
                                    versionCode = packageInfo.versionCodeOrNull(),
                                    firstInstallTime = packageInfo?.firstInstallTime?.takeIf { value -> value > 0L },
                                    lastUpdateTime = packageInfo?.lastUpdateTime,
                                    installerPackageName = packageManager.installerPackageNameOrNull(packageName),
                                )
                            }.sortedWith(
                                compareBy<InstalledAppOption>(
                                    { it.isSystemApp },
                                    { it.label.lowercase() },
                                    { it.packageName.lowercase() },
                                ),
                            )
                    }
                installedAppsMutable.value = installed
                installedAppsLoadedMutable.value = true
                container.settingsRepository.recordInstalledAppInventory(installed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                container.diagnosticsLogger.recordFailure(
                    "apps",
                    "installed app visibility query failed: ${error.message.orEmpty()}",
                )
                installedAppsMutable.value = emptyList()
                installedAppsLoadedMutable.value = false
            } finally {
                val currentJob = coroutineContext[Job]
                if (installedAppsLoadJob == currentJob) {
                    installedAppsLoadingMutable.value = false
                    installedAppsLoadJob = null
                }
            }
        }
    installedAppsLoadJob = loadJob
}

internal fun HomeViewModel.ensureInstalledAppsLoaded() {
    loadInstalledApps()
}

internal fun HomeViewModel.onTrafficUiVisibilityChangedInternal(visible: Boolean) {
    FoxholeVpnRuntimeBridge.setHighFrequencyTrafficUpdates(visible)
    if (visible) {
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        val connectionState = container.connectionController.snapshot.value.state
        if (connectionState == ConnectionState.CONNECTED && !autoConnectUiStateMutable.value.running) {
            scheduleForegroundDashboardRefreshIfStale()
        }
    } else {
        clearProfileLatencyRefresh()
    }
}

internal fun HomeViewModel.syncHighFrequencyTrafficVisibility() {
    onTrafficUiVisibilityChangedInternal(trafficUiVisible || statisticsVisible)
}

private fun PackageManager.packageInfoOrNull(packageName: String): PackageInfo? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, 0)
        }
    }.getOrNull()

private fun PackageManager.installerPackageNameOrNull(packageName: String): String? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getInstallSourceInfo(packageName).installingPackageName
                ?: getInstallSourceInfo(packageName).initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            getInstallerPackageName(packageName)
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

private fun PackageInfo?.versionCodeOrNull(): Long? =
    this?.let { packageInfo ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
