package com.foxhole.guard.guardian

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.foxhole.guard.core.sentinel.sentinelAttempt
import java.security.MessageDigest

internal class GuardPackageInspector(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    private val metadataCache = GuardPackageMetadataCache()

    fun invalidate(packageName: String) = metadataCache.invalidate(packageName)

    fun invalidateEpoch() = metadataCache.invalidateEpoch()

    fun snapshotInstalledApps(): Result<GuardPackageVisibleInventory> =
        sentinelAttempt {
            val installed = installedPackages()
            check(installed.isNotEmpty()) { "package manager returned no packages" }
            val facts = installed.associate { info -> info.packageName to sentinelAttempt { metadataOf(info) } }
            val apps = facts.values.mapNotNull { it.getOrNull() }
            val unresolved = facts.filterValues { it.isFailure }.keys
            metadataCache.retainVisiblePackages(facts.keys)
            GuardPackageVisibleInventory(apps, unresolved, hasFullInventoryVisibility() && unresolved.isEmpty())
        }

    private fun hasFullInventoryVisibility(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            appContext.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED

    fun describe(packageName: String): GuardInventoryApp =
        metadataOf(requireNotNull(packageInfo(packageName, 0)))

    private fun installationOf(info: PackageInfo): GuardPackageInstallation = GuardPackageInstallation(
        packageName = info.packageName,
        uid = info.applicationInfo?.uid,
        versionCode = versionCodeOf(info),
        firstInstallTime = info.firstInstallTime,
        lastUpdateTime = info.lastUpdateTime,
    )

    private fun metadataOf(info: PackageInfo): GuardInventoryApp {
        val installation = installationOf(info)
        val provenance = "schema1:api${Build.VERSION.SDK_INT}:current-signers"
        val facts = metadataCache.resolve(installation, provenance) {
            val signed = requireNotNull(packageInfo(installation.packageName, signingFlags()))
            GuardPackageMetadata(installationOf(signed), requireNotNull(signerSha256(signed)), provenance)
        }
        return GuardInventoryApp(
            packageName = installation.packageName,
            versionCode = installation.versionCode,
            uid = installation.uid,
            signerSha256 = facts.signerSha256,
            firstInstallTime = installation.firstInstallTime,
            lastUpdateTime = installation.lastUpdateTime,
        )
    }

    fun installerOf(packageName: String): String? =
        sentinelAttempt {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()

    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else legacyVersionCode(info)

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

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
            ?.takeIf { it.isNotEmpty() && it.all(ByteArray::isNotEmpty) }
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

internal data class GuardPackageVisibleInventory(
    val apps: List<GuardInventoryApp>,
    val unresolvedPackages: Set<String>,
    val authoritative: Boolean,
)
