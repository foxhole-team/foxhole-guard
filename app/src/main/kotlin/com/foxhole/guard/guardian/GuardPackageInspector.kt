package com.foxhole.guard.guardian

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Lightweight package facts for the guard journal / inventory (no sentinel scoring). */
internal class GuardPackageInspector(
    context: Context,
) {
    private val packageManager = context.applicationContext.packageManager

    fun snapshotInstalledApps(): List<GuardInventoryApp> =
        installedPackages().mapNotNull { info ->
            val packageName = info.packageName.takeIf(String::isNotBlank) ?: return@mapNotNull null
            GuardInventoryApp(
                packageName = packageName,
                versionCode = versionCodeOf(info),
                uid = info.applicationInfo?.uid,
            )
        }

    fun describe(packageName: String): GuardInventoryApp =
        GuardInventoryApp(
            packageName = packageName,
            versionCode = versionCodeOf(packageName),
            uid = runCatching { applicationInfo(packageName)?.uid }.getOrNull(),
            signerSha256 = signerSha256(packageName),
        )

    fun installerOf(packageName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()

    /** One PackageManager Binder query per reconciliation, not one query per installed app. */
    private fun installedPackages(): List<PackageInfo> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

    private fun applicationInfo(packageName: String): ApplicationInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()

    private fun versionCodeOf(packageName: String): Long =
        runCatching {
            packageInfo(packageName, 0)?.let(::versionCodeOf) ?: 0L
        }.getOrDefault(0L)

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else legacyVersionCode(info)

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    fun signerSha256(packageName: String): String? =
        runCatching {
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
            val info = packageInfo(packageName, flags) ?: return null
            val signatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures
                }
            signatures?.firstOrNull()?.toByteArray()?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()

    private fun packageInfo(
        packageName: String,
        flags: Int,
    ): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
}
