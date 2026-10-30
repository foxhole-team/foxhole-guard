package com.foxhole.guard.core.sentinel.scanner

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.foxhole.guard.core.sentinel.apk.ApkInspection
import com.foxhole.guard.core.sentinel.apk.ApkScanInput
import com.foxhole.guard.core.sentinel.apk.ApkSourceKind
import com.foxhole.guard.core.sentinel.apk.BoundedApkInspector
import com.foxhole.guard.core.sentinel.apk.ScanBudget
import com.foxhole.guard.core.sentinel.apk.ScanCancellation
import java.io.IOException

internal fun inspectIsolatedApkDescriptors(
    claims: List<ScannerDescriptorClaim>,
    descriptors: List<ParcelFileDescriptor>,
    cancellation: ScanCancellation,
    onVerifiedDex: ((ByteArray) -> Unit)? = null,
    maxArtifactBytes: Int = 16 * 1024 * 1024,
): ApkInspection {
    validateScannerDescriptorClaims(claims)
    requireScannerArtifactBudget(maxArtifactBytes)
    require(claims.size == descriptors.size)
    val before = descriptors.map { Os.fstat(it.fileDescriptor) }
    val inputs = claims.mapIndexed { index, claim ->
        val descriptor = descriptors[index]
        val stat = before[index]
        require(OsConstants.S_ISREG(stat.st_mode))
        require(stat.st_size == claim.bytes)
        requireScannerReadonlyRegularFd(descriptor)
        ApkScanInput(
            claim.label,
            if (claim.kind == ScannerArtifactKind.BASE) ApkSourceKind.BASE else ApkSourceKind.SPLIT
        ) {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
        }
    }
    val inspection = BoundedApkInspector().inspect(
        inputs,
        ScanBudget(maxArtifactBytes = maxArtifactBytes),
        cancellation,
        onVerifiedDex
    )
    descriptors.forEachIndexed { index, descriptor ->
        val first = before[index]
        val last = Os.fstat(descriptor.fileDescriptor)
        if (!sameScannerDescriptorMetadata(first, last)) throw IOException("Scanner input changed")
    }
    return inspection
}

private fun sameScannerDescriptorMetadata(first: StructStat, last: StructStat): Boolean {
    val identity = first.st_dev == last.st_dev && first.st_ino == last.st_ino
    val content = first.st_size == last.st_size && first.st_mtime == last.st_mtime && first.st_ctime == last.st_ctime
    return identity && content
}
