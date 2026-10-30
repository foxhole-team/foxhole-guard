package com.foxhole.guard.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.foxhole.guard.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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
            val installedPaths = listOf(appContext.applicationInfo.sourceDir) + appContext.applicationInfo.splitSourceDirs.orEmpty()
            val installedAbis = installedPaths.flatMap { appUpdateApkNativeLibraries(File(it)).keys }.toSet()
            val supportedAbis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            val runningAbi = appUpdateRunningAbi(installedAbis, supportedAbis.toList())
            if (!appUpdateNativeProfileCompatible(
                    runningAbi,
                    appUpdateApkNativeLibraries(apk),
                    BuildConfig.UPDATE_MANIFEST_ASSET == AppUpdateClient.ARMV7_MANIFEST_ASSET_NAME,
                )
            ) {
                throw AppUpdateVerificationException("native ABI profile differs from the installed build")
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

internal val appUpdateRequiredNativeLibraries = setOf(
    "libfoxhole_native.so", "libfoxhole_sentinel_yara.so", "libi2pd.so", "liblyrebird.so", "libconjure_client.so",
)

internal fun appUpdateRunningAbi(installedAbis: Set<String>, supportedAbis: List<String>): String? =
    supportedAbis.firstOrNull(installedAbis::contains)

internal fun appUpdateNativeProfileCompatible(
    runningAbi: String?,
    candidate: Map<String, Set<String>>,
    armv7: Boolean,
): Boolean {
    val allowed = if (armv7) setOf("armeabi-v7a") else setOf("arm64-v8a", "x86_64")
    return runningAbi != null && runningAbi in allowed && runningAbi in candidate && candidate.keys.all(allowed::contains) &&
        candidate.values.all { it.containsAll(appUpdateRequiredNativeLibraries) }
}

internal fun appUpdateApkNativeLibraries(apk: File): Map<String, Set<String>> = ZipFile(apk).use { zip ->
    if (zip.size() > 50_000) throw AppUpdateVerificationException("APK inventory is oversized")
    val result = linkedMapOf<String, MutableSet<String>>()
    var nativeEntries = 0
    zip.entries().asSequence().forEach { entry ->
        if (entry.name.length > 512) throw AppUpdateVerificationException("APK entry name is oversized")
        if (!entry.name.startsWith("lib/") || !entry.name.removeSuffix("/").endsWith(".so")) return@forEach
        nativeEntries++
        if (nativeEntries > 256 || entry.isDirectory || entry.size < 20) {
            throw AppUpdateVerificationException("invalid native library entry")
        }
        val parts = entry.name.split('/')
        if (parts.size != 3 || parts.any { it.isEmpty() || it == "." || it == ".." } ||
            entry.name.any { it == '\\' || it == ':' || it.isISOControl() }
        ) throw AppUpdateVerificationException("invalid native library path")
        val expected = when (parts[1]) {
            "armeabi-v7a" -> 1 to 40
            "arm64-v8a" -> 2 to 183
            "x86" -> 1 to 3
            "x86_64" -> 2 to 62
            else -> throw AppUpdateVerificationException("unknown native ABI")
        }
        val header = ByteArray(20)
        var bytesRead = 0
        zip.getInputStream(entry).use { stream ->
            while (bytesRead < header.size) {
                val count = stream.read(header, bytesRead, header.size - bytesRead)
                if (count <= 0) break
                bytesRead += count
            }
        }
        val machine = if (bytesRead == 20) (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8) else -1
        if (bytesRead != 20 || header.take(4) != listOf(127.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) ||
            header[4].toInt() != expected.first || header[5].toInt() != 1 || header[6].toInt() != 1 || machine != expected.second
        ) throw AppUpdateVerificationException("native ELF differs from its advertised ABI")
        if (!result.getOrPut(parts[1]) { linkedSetOf() }.add(parts[2])) {
            throw AppUpdateVerificationException("duplicate native library")
        }
    }
    result
}

internal fun ByteArray.sha256HexDigest(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }
