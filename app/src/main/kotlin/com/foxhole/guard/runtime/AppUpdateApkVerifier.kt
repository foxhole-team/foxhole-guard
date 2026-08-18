package com.foxhole.guard.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

class AppUpdateApkVerifier(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun verify(
        apk: File,
        manifest: AppUpdateManifest,
    ): Result<Unit> =
        runCatching {
            val packageManager = appContext.packageManager
            val archive =
                packageManager.getPackageArchiveInfo(apk.absolutePath, SIGNING_FLAGS)
                    ?: throw AppUpdateVerificationException("downloaded file is not a readable package")
            if (archive.packageName != appContext.packageName) {
                throw AppUpdateVerificationException(
                    "package ${archive.packageName} is not ${appContext.packageName}",
                )
            }
            val archiveVersionCode = archive.longVersionCodeCompat()
            if (archiveVersionCode != manifest.versionCode) {
                throw AppUpdateVerificationException(
                    "version $archiveVersionCode does not match the announced ${manifest.versionCode}",
                )
            }
            val installed = packageManager.getPackageInfo(appContext.packageName, SIGNING_FLAGS)
            if (!appUpdateSignersMatch(installed.signerDigests(), archive.signerDigests())) {
                throw AppUpdateVerificationException("signing certificate differs from the installed app")
            }
        }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                signatures
            }
        return signatures.orEmpty().filterNotNull().mapTo(mutableSetOf()) { signature ->
            signature.toByteArray().sha256HexDigest()
        }
    }

    private companion object {
        val SIGNING_FLAGS: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
    }
}

class AppUpdateVerificationException(
    message: String,
) : Exception(message)

internal fun appUpdateSignersMatch(
    installed: Set<String>,
    candidate: Set<String>,
): Boolean = installed.isNotEmpty() && installed == candidate

internal fun ByteArray.sha256HexDigest(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }
