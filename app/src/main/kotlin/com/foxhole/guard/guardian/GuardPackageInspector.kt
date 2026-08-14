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

    fun snapshotInstalledApps(): Result<List<GuardInventoryApp>> =
        runCatching {
            val apps =
                installedPackages().mapNotNull { info ->
                    val packageName = info.packageName.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    GuardInventoryApp(
                        packageName = packageName,
                        versionCode = versionCodeOf(info),
                        uid = info.applicationInfo?.uid,
                        signerSha256 = signerSha256(info),
                        firstInstallTime = info.firstInstallTime,
                    )
                }
            // A PackageManager query never legitimately comes back empty: this app is itself
            // installed. An empty answer means the Binder call was throttled, the user profile is
            // locked or package visibility denied everything - i.e. the inventory is UNKNOWN. Fail
            // the query instead of handing back a list that diffs into "every app was uninstalled".
            check(apps.isNotEmpty()) { "package manager returned no packages" }
            apps
        }

    fun describe(packageName: String): GuardInventoryApp =
        GuardInventoryApp(
            packageName = packageName,
            versionCode = versionCodeOf(packageName),
            uid = runCatching { applicationInfo(packageName)?.uid }.getOrNull(),
            signerSha256 = signerSha256(packageName),
            firstInstallTime = runCatching { packageInfo(packageName, signingFlags())?.firstInstallTime ?: 0L }.getOrDefault(
                0L
            ),
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(signingFlags())
        }

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
            packageInfo(packageName, signingFlags())?.let(::signerSha256)
        }.getOrNull()

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signerSha256(info: PackageInfo): String? {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        return signatures
            ?.map { signature -> signature.toByteArray() }
            ?.minWithOrNull { left, right -> compareByteArrays(left, right) }
            ?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
    }

    private fun compareByteArrays(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val comparison = left[index].toUByte().compareTo(right[index].toUByte())
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

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
