package com.foxhole.guard.guardian

import java.util.UUID

internal data class GuardPackageInstallation(
    val packageName: String,
    val uid: Int?,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
) {
    fun isComplete(): Boolean = packageName.isNotBlank() && uid != null && uid >= 0 && versionCode >= 0 &&
        firstInstallTime > 0 && lastUpdateTime >= firstInstallTime
}

internal data class GuardPackageMetadata(
    val installation: GuardPackageInstallation,
    val signerSha256: String,
    val provenance: String,
) {
    fun isComplete(): Boolean = installation.isComplete() && signerSha256.matches(SHA256_PATTERN) && provenance.isNotBlank()

    private companion object { val SHA256_PATTERN = Regex("[0-9a-f]{64}") }
}

internal class GuardPackageMetadataUnavailableException : IllegalStateException("package metadata unavailable")

internal class GuardPackageMetadataCache(private val capacity: Int = 1024) {
    private val lock = Any()
    private val entries = linkedMapOf<String, GuardPackageMetadata>()
    private val dirty = mutableSetOf<String>()
    private var epoch = UUID.randomUUID().toString()

    fun resolve(
        installation: GuardPackageInstallation,
        provenance: String,
        inspect: () -> GuardPackageMetadata,
    ): GuardPackageMetadata {
        requireComplete(installation.isComplete())
        val capturedEpoch: String
        val cached: GuardPackageMetadata?
        synchronized(lock) {
            capturedEpoch = epoch
            cached = entries[installation.packageName]?.takeIf {
                installation.packageName !in dirty && it.installation == installation && it.provenance == provenance && it.isComplete()
            }
        }
        if (cached != null) return cached
        val fresh = inspect()
        requireComplete(fresh.isComplete() && fresh.installation == installation && fresh.provenance == provenance)
        synchronized(lock) {
            requireComplete(capturedEpoch == epoch)
            run {
                entries.remove(installation.packageName)
                if (entries.size >= capacity) entries.remove(entries.keys.first())
                entries[installation.packageName] = fresh
                dirty.remove(installation.packageName)
            }
        }
        return fresh
    }

    private fun requireComplete(complete: Boolean) {
        if (!complete) throw GuardPackageMetadataUnavailableException()
    }

    fun invalidate(packageName: String) = synchronized(lock) {
        epoch = UUID.randomUUID().toString()
        entries.remove(packageName)
        if (dirty.size < capacity) dirty += packageName
    }

    fun invalidateEpoch() = synchronized(lock) {
        epoch = UUID.randomUUID().toString()
        entries.clear()
        dirty.clear()
    }

    fun retainVisiblePackages(packageNames: Set<String>) = synchronized(lock) {
        entries.keys.retainAll(packageNames)
        dirty.retainAll(packageNames)
    }
}
