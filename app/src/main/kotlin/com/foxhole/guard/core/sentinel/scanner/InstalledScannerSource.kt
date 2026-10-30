package com.foxhole.guard.core.sentinel.scanner

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

internal data class InstalledScannerSource(
    val packageName: String,
    val uid: Int,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val currentSignerSha256: Set<String>,
    val artifacts: List<InstalledScannerArtifact>,
) {
    init {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        require(uid >= 0 && versionCode >= 0 && firstInstallTime >= 0 && lastUpdateTime >= 0)
        require(currentSignerSha256.size in 1..32 && currentSignerSha256.all { it.matches(Regex("[0-9a-f]{64}")) })
        validateScannerDescriptorClaims(artifacts.map { ScannerDescriptorClaim(it.label, it.kind, it.bytes) })
    }

    val installationId: String get() = scannerIdentityDigest(this)
}

internal data class InstalledScannerArtifact(
    val label: String,
    val kind: ScannerArtifactKind,
    val path: String,
    val bytes: Long,
    val modified: Long,
    val device: Long = 0,
    val inode: Long = 0,
    val changed: Long = 0,
)

internal class InstalledScannerSourceProvider(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    suspend fun capture(packageName: String): InstalledScannerSource = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        currentCoroutineContext().ensureActive()
        val before = packageInfo(packageName, 0)
        val signed = packageInfo(packageName, signingFlags())
        val after = packageInfo(packageName, 0)
        require(sameInstallation(before, signed) && sameInstallation(signed, after))
        val app = requireNotNull(after.applicationInfo)
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            signed.signingInfo?.apkContentsSigners?.toList()
        } else {
            legacySignatures(
                signed
            )
        }
        require(!signatures.isNullOrEmpty())
        val signers = signatures.map { certificate ->
            val bytes = certificate.toByteArray()
            require(bytes.isNotEmpty())
            digest(bytes)
        }.toSet()
        val base = artifact("base", ScannerArtifactKind.BASE, requireNotNull(app.sourceDir))
        val splitNames = app.splitSourceDirs?.toList().orEmpty()
        require(splitNames.size <= 31 && splitNames.toSet().size == splitNames.size)
        val splits = splitNames.sorted().mapIndexed { index, path ->
            artifact(
                "split_${index + 1}",
                ScannerArtifactKind.SPLIT,
                path
            )
        }
        val source =
            InstalledScannerSource(
                packageName,
                app.uid,
                version(after),
                after.firstInstallTime,
                after.lastUpdateTime,
                signers,
                listOf(base) + splits
            )
        validateScannerDescriptorClaims(source.artifacts.map { ScannerDescriptorClaim(it.label, it.kind, it.bytes) })
        currentCoroutineContext().ensureActive()
        source
    }

    suspend fun isCurrent(captured: InstalledScannerSource): Boolean = capture(captured.packageName) == captured

    private fun artifact(label: String, kind: ScannerArtifactKind, path: String): InstalledScannerArtifact {
        val file = File(path)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
        require(file.length() in 1..67108864L)
        val stat = Os.stat(file.absolutePath)
        require(
            OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..67108864L && !Files.isSymbolicLink(file.toPath())
        )
        return InstalledScannerArtifact(
            label,
            kind,
            file.canonicalPath,
            stat.st_size,
            stat.st_mtime,
            stat.st_dev,
            stat.st_ino,
            stat.st_ctime
        )
    }

    private fun sameInstallation(first: PackageInfo, second: PackageInfo): Boolean = installationFacts(first) == installationFacts(second)

    private fun installationFacts(info: PackageInfo): BasicScannerInstallation = BasicScannerInstallation(
        info.packageName,
        info.applicationInfo?.uid,
        version(info),
        info.firstInstallTime,
        info.lastUpdateTime,
        info.applicationInfo?.sourceDir,
        info.applicationInfo?.splitSourceDirs?.toList(),
    )

    private fun packageInfo(name: String, flags: Int): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else { legacyPackageInfo(name, flags) }

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(name: String, flags: Int): PackageInfo = packageManager.getPackageInfo(name, flags)

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else legacySigningFlags()

    @Suppress("DEPRECATION")
    private fun legacySigningFlags(): Int = PackageManager.GET_SIGNATURES
    private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode
    } else {
        legacyVersion(
            info
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyVersion(info: PackageInfo): Long = info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun legacySignatures(info: PackageInfo) = info.signatures?.toList()
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance(
    "SHA-256"
).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

private fun scannerIdentityDigest(source: InstalledScannerSource): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.identityText("installed-scanner-source-v1")
        out.identityText(source.packageName)
        listOf(
            source.uid.toLong(),
            source.versionCode,
            source.firstInstallTime,
            source.lastUpdateTime
        ).forEach(out::writeLong)
        out.writeInt(source.currentSignerSha256.size)
        source.currentSignerSha256.sorted().forEach(out::identityText)
        out.writeInt(source.artifacts.size)
        source.artifacts.forEach { artifact ->
            out.identityText(artifact.label)
            out.writeInt(artifact.kind.ordinal)
            out.identityText(artifact.path)
            out.writeLong(artifact.bytes)
            out.writeLong(artifact.modified)
            out.writeLong(artifact.device)
            out.writeLong(artifact.inode)
            out.writeLong(artifact.changed)
        }
    }
    return digest(bytes.toByteArray())
}

private fun DataOutputStream.identityText(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 16384)
    writeInt(bytes.size)
    write(bytes)
}

private data class BasicScannerInstallation(
    val packageName: String,
    val uid: Int?,
    val version: Long,
    val firstInstall: Long,
    val lastUpdate: Long,
    val base: String?,
    val splits: List<String>?,
)
