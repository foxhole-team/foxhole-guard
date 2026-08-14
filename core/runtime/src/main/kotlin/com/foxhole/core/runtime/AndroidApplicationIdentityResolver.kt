package com.foxhole.core.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.foxhole.core.model.KnownApplicationIdentity
import java.security.MessageDigest

/**
 * Produces the immutable Android package baseline consumed by FoxCore's native quarantine.
 *
 * A shared UID cannot be attributed to one signer without guessing which package opened the
 * socket. Such packages are therefore admitted only when every package reported for that UID is
 * present in the known set; their per-package digest is intentionally omitted. Unique UIDs are
 * always certificate-pinned, and a missing certificate makes the snapshot fail closed.
 */
class AndroidApplicationIdentityResolver(
    context: Context,
) {
    private val packageManager = context.applicationContext.packageManager

    fun snapshotKnownApplications(): Result<List<KnownApplicationIdentity>> =
        runCatching {
            val packages = installedPackages()
                .filter { info -> info.packageName.isNotBlank() }
                .distinctBy(PackageInfo::packageName)
            val uidCounts = packages.groupingBy { info -> requireNotNull(info.applicationInfo).uid }.eachCount()
            packages
                .map { info ->
                    val uid = requireNotNull(info.applicationInfo).uid
                    val digest =
                        if (uidCounts.getValue(uid) == 1) {
                            requireNotNull(signingCertificateSha256(info)) {
                                "installed package has no signing certificate"
                            }
                        } else {
                            null
                        }
                    KnownApplicationIdentity(
                        packageName = info.packageName,
                        signingCertificateSha256 = digest,
                        firstSeenAtMs = info.firstInstallTime.takeIf { value -> value > 0L },
                    )
                }
                .sortedBy(KnownApplicationIdentity::packageName)
        }

    /** JNI-visible host callback; native code resolves it fresh for every attributed flow. */
    fun signingCertificateSha256(packageName: String): String? =
        runCatching {
            packageInfo(packageName)?.let(::signingCertificateSha256)
        }.getOrNull()

    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(signingFlags().toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(signingFlags())
        }

    private fun packageInfo(packageName: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(signingFlags().toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, signingFlags())
        }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signingCertificateSha256(info: PackageInfo): String? {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        return signatures
            ?.map { signature -> signature.toByteArray() }
            ?.minWithOrNull(::compareByteArrays)
            ?.let { certificate ->
                MessageDigest.getInstance("SHA-256")
                    .digest(certificate)
                    .joinToString(separator = "") { byte -> "%02x".format(byte) }
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
}
