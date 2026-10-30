package com.foxhole.guard.guardian

import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class GuardInventoryApp(
    val packageName: String,
    val versionCode: Long = 0L,
    val uid: Int? = null,
    val signerSha256: String? = null,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
)

@Serializable
data class GuardInventorySnapshot(
    val capturedAt: Long = 0L,
    val apps: List<GuardInventoryApp> = emptyList(),
    val lastAuthoritativeFullRepairAt: Long = 0L,
    val fullRepairPending: Boolean = false,
)

internal class GuardInventorySnapshotStore(
    private val file: File,
    private val cipher: FileCipher,
) {
    private val ioLock = Any()
    private val state = AtomicReference(GuardInventoryStoreEpoch())
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): GuardInventorySnapshot = synchronized(ioLock) {
        val captured = state.get()
        if (!captured.resetPending) recoverInventoryWrites()
        if (captured.resetPending || !file.isFile) return@synchronized GuardInventorySnapshot()
        val snapshot = json.decodeFromString(
            GuardInventorySnapshot.serializer(),
            cipher.readBytes(file).decodeToString(),
        )
        if (state.get() == captured) snapshot else GuardInventorySnapshot()
    }

    fun write(snapshot: GuardInventorySnapshot) {
        check(writeIfCurrent(snapshot) { true }) { "guard inventory epoch changed" }
    }

    fun writeIfCurrent(snapshot: GuardInventorySnapshot, current: () -> Boolean): Boolean = synchronized(ioLock) {
        val captured = state.get()
        if (captured.resetPending) removeInventoryStaging() else recoverInventoryWrites()
        if (!current()) return@synchronized false
        val files = GuardInventoryWriteFiles(file)
        val committedEpoch = captured.copy(resetPending = false)
        var primary: Throwable? = null
        try {
            cipher.writeBytesAtomic(
                files.staged,
                json.encodeToString(GuardInventorySnapshot.serializer(), snapshot).encodeToByteArray(),
            )
            files.copyPrevious()
            if (state.get() == captured && current()) {
                files.prepareAdoption()
                moveInventoryReplacing(files.staged, file)
                files.adopted = true
                syncInventoryDirectory(file)
                files.accepted = state.compareAndSet(captured, committedEpoch) && current()
                if (files.accepted) files.accept()
            }
        } catch (failure: Throwable) {
            primary = failure
            files.accepted = false
        }
        val failures = mutableListOf<Throwable>()
        var rollbackComplete = true
        if (files.adopted && !files.accepted && state.get() == committedEpoch) {
            rollbackComplete = inventoryCleanupAttempt(failures) {
                files.rollback(::restoreInventoryPrevious)
                state.compareAndSet(committedEpoch, captured)
            }
        }
        files.cleanup(failures, rollbackComplete)
        resolveInventoryCleanupFailure(primary, failures)?.let { throw it }
        files.accepted
    }

    private fun moveInventoryReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun syncInventoryDirectory(target: File) {
        try {
            FileChannel.open(requireNotNull(target.parentFile).toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Directory fsync is not available on every Android filesystem.
        }
    }

    private fun restoreInventoryPrevious(previous: File) {
        val restore = File(previous.parentFile, "restore")
        Files.copy(previous.toPath(), restore.toPath(), StandardCopyOption.REPLACE_EXISTING)
        moveInventoryReplacing(restore, file)
        syncInventoryDirectory(file)
    }

    private fun inventoryStagingDirectories(): List<File> = file.parentFile?.listFiles().orEmpty().filter {
        it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.name.startsWith(".${file.name}.stage-") &&
            runCatching { UUID.fromString(it.name.removePrefix(".${file.name}.stage-")) }.isSuccess
    }

    private fun recoverInventoryWrites() {
        val pending = inventoryStagingDirectories()
        check(pending.size <= 8) { "guard inventory recovery limit" }
        pending.forEach { directory ->
            val marker = File(directory, "rollback")
            check(!Files.isSymbolicLink(marker.toPath())) { "guard inventory recovery marker invalid" }
            if (marker.isFile) {
                check(marker.length() <= 16) { "guard inventory recovery marker invalid" }
                when (marker.readText()) {
                    "present" -> {
                        val previous = File(directory, "${file.name}.previous")
                        check(
                            previous.isFile && !Files.isSymbolicLink(previous.toPath())
                        ) { "guard inventory rollback data unavailable" }
                        restoreInventoryPrevious(previous)
                    }
                    "absent" -> check(!file.exists() || file.delete()) { "guard inventory rollback cleanup failed" }
                    else -> throw IOException("guard inventory recovery marker invalid")
                }
                check(marker.delete()) { "guard inventory rollback marker cleanup failed" }
            }
            removeOwnedInventoryDirectory(directory)
        }
    }

    private fun removeInventoryStaging() = inventoryStagingDirectories().forEach(::removeOwnedInventoryDirectory)

    private fun removeOwnedInventoryDirectory(directory: File) {
        directory.listFiles().orEmpty().forEach { entry ->
            check(entry.isFile && !Files.isSymbolicLink(entry.toPath()) && entry.delete()) {
                "guard inventory staging recovery failed"
            }
        }
        check(directory.delete()) { "guard inventory staging recovery failed" }
    }

    internal fun invalidateEpoch() {
        state.set(GuardInventoryStoreEpoch(resetPending = true))
    }

    internal fun settleInvalidation() = synchronized(ioLock) {
        val captured = state.get()
        if (captured.resetPending) {
            removeInventoryStaging()
            check(!file.exists() || file.delete()) { "guard inventory cleanup failed" }
            state.compareAndSet(captured, captured.copy(resetPending = false))
        }
    }

    internal fun packageWorkStore(capacity: Int = 256): GuardPackageWorkStore =
        GuardPackageWorkStore(File(file.parentFile, "package-work.json"), cipher, capacity)

    fun delete() {
        invalidateEpoch()
        settleInvalidation()
    }
}

internal object GuardInventoryDiff {
    fun diff(
        previous: GuardInventorySnapshot,
        current: GuardInventorySnapshot,
    ): List<GuardEvent> {
        if (previous.capturedAt == 0L) {
            return emptyList()
        }
        if (current.apps.isEmpty() && previous.apps.isNotEmpty()) {
            return emptyList()
        }
        val before = previous.apps.associateBy(GuardInventoryApp::packageName)
        val after = current.apps.associateBy(GuardInventoryApp::packageName)
        val events = mutableListOf<GuardEvent>()
        for ((packageName, app) in after) {
            val old = before[packageName]
            when {
                old == null -> events += app.reconciledEvent(GuardEventType.PACKAGE_ADDED)
                old.differsFrom(app) -> events += app.reconciledEvent(GuardEventType.PACKAGE_REPLACED)
            }
        }
        for (packageName in before.keys - after.keys) {
            events += before.getValue(packageName).reconciledEvent(GuardEventType.PACKAGE_REMOVED)
        }
        return events.sortedBy { event -> event.packageName.orEmpty() }
    }

    private fun GuardInventoryApp.differsFrom(current: GuardInventoryApp): Boolean =
        versionCode != current.versionCode ||
            (uid != null && uid != current.uid) ||
            (signerSha256 != null && signerSha256 != current.signerSha256) ||
            (firstInstallTime > 0L && firstInstallTime != current.firstInstallTime) ||
            (lastUpdateTime > 0L && lastUpdateTime != current.lastUpdateTime)

    private fun GuardInventoryApp.reconciledEvent(type: GuardEventType): GuardEvent =
        GuardEvent(
            type = type,
            packageName = packageName,
            uid = uid,
            versionCode = versionCode,
            signerSha256 = signerSha256,
            firstInstallTime = firstInstallTime.takeIf { value -> value > 0L },
            detail = RECONCILED_DETAIL,
        )

    const val RECONCILED_DETAIL = "reconciled"
}

private data class GuardInventoryStoreEpoch(val id: String = UUID.randomUUID().toString(), val resetPending: Boolean = false)

private fun inventoryCleanupAttempt(failures: MutableList<Throwable>, action: () -> Unit): Boolean = try {
    action()
    true
} catch (failure: Throwable) {
    failures += failure
    false
}

private fun resolveInventoryCleanupFailure(primary: Throwable?, cleanup: List<Throwable>): Throwable? {
    if (cleanup.isEmpty()) return primary
    val fatal = cleanup.firstOrNull { it is Error } ?: cleanup.firstOrNull { it is CancellationException }
    val selected = when {
        primary is Error || primary is CancellationException -> primary
        fatal != null -> fatal
        primary != null -> primary
        else -> cleanup.first()
    }
    if (primary != null && selected !== primary) selected.addSuppressed(primary)
    cleanup.filter { it !== selected && it !== primary }.forEach(selected::addSuppressed)
    return selected
}

private class GuardInventoryWriteFiles(private val target: File) {
    private val directory = File(requireNotNull(target.parentFile), ".${target.name}.stage-${UUID.randomUUID()}")
    val staged = File(directory, target.name)
    private val previous = File(directory, "${target.name}.previous")
    private val marker = File(directory, "rollback")
    var adopted = false
    var accepted = false

    init {
        check(directory.mkdirs()) { "guard inventory staging unavailable" }
    }

    fun copyPrevious() {
        if (target.isFile) Files.copy(target.toPath(), previous.toPath())
    }

    fun prepareAdoption() {
        marker.writeText(if (previous.isFile) "present" else "absent")
    }

    fun accept() {
        check(marker.delete()) { "guard inventory acceptance cleanup failed" }
    }

    fun rollback(restore: (File) -> Unit) {
        if (previous.isFile) {
            restore(previous)
        } else {
            check(target.delete()) { "guard inventory rollback failed" }
        }
        check(!marker.exists() || marker.delete()) { "guard inventory rollback marker cleanup failed" }
    }

    fun cleanup(failures: MutableList<Throwable>, rollbackComplete: Boolean) {
        inventoryCleanupAttempt(failures) { delete(staged) }
        if (rollbackComplete) {
            listOf(previous, marker, directory).forEach { entry ->
                inventoryCleanupAttempt(failures) { delete(entry) }
            }
        }
    }

    private fun delete(entry: File) {
        check(!entry.exists() || entry.delete()) { "guard inventory staging cleanup failed" }
    }
}
