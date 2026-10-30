package com.foxhole.guard.guardian

import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class GuardPackageObservation(
    val seq: Long,
    val type: GuardEventType,
    val packageName: String,
    val observedAt: Long,
    val journalEpoch: String = "",
)

@Serializable
internal data class GuardPackageWorkDocument(
    val epoch: String = UUID.randomUUID().toString(),
    val nextSeq: Long = 1L,
    val revision: Long = 0L,
    val observations: List<GuardPackageObservation> = emptyList(),
    val dropped: Long = 0L,
    val fullRepairRequired: Boolean = false,
    val journalEpoch: String = UUID.randomUUID().toString(),
    val journalDropped: Long = dropped,
)

internal data class GuardPackageWorkClaim(
    val owner: String,
    val epoch: String,
    val revision: Long,
    val observations: List<GuardPackageObservation>,
    val dropped: Long,
    val fullRepairRequired: Boolean,
    val journalEpoch: String,
    val journalDropped: Long,
)

internal enum class GuardPackageAdmission { ACCEPTED, GAP_RECORDED, INACTIVE }

internal class GuardPackageWorkStore(
    private val file: File,
    private val cipher: FileCipher,
    private val capacity: Int = 256,
) {
    private val lock = Any()
    private val ioLock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var document: GuardPackageWorkDocument? = null
    private var resetPending = false
    private var journalFencePending = false
    private var journalOverride: String? = null
    private var runningOwner: String? = null
    private var wakeReservation: String? = null
    private var activeClaim: GuardPackageWorkClaim? = null

    @Volatile
    private var epoch: String? = null

    @Volatile
    private var journalEpoch: String? = null

    fun enqueue(type: GuardEventType, packageName: String, observedAt: Long, allowed: () -> Boolean): GuardPackageAdmission =
        synchronized(ioLock) {
            if (!allowed()) return@synchronized GuardPackageAdmission.INACTIVE
            require(type in PACKAGE_EVENT_TYPES)
            require(packageName.isNotBlank() && packageName.length <= MAX_PACKAGE_NAME_LENGTH)
            loadUnderIo()
            val admitted = synchronized(lock) {
                if (!allowed()) return@synchronized null
                val before = requireNotNull(document)
                check(before.revision < Long.MAX_VALUE) { "package work revision exhausted" }
                admittedDocument(before, type, packageName, observedAt).copy(revision = before.revision + 1) to
                    (before.observations.size >= capacity)
            } ?: return@synchronized GuardPackageAdmission.INACTIVE
            if (!persistUnderIo(admitted.first)) return@synchronized GuardPackageAdmission.INACTIVE
            if (admitted.second) GuardPackageAdmission.GAP_RECORDED else GuardPackageAdmission.ACCEPTED
        }

    private fun admittedDocument(
        before: GuardPackageWorkDocument,
        type: GuardEventType,
        packageName: String,
        observedAt: Long,
    ): GuardPackageWorkDocument {
        if (before.observations.size >= capacity) {
            return before.copy(
                dropped = before.dropped.saturatedIncrement(),
                journalDropped = before.journalDropped.saturatedIncrement(),
                fullRepairRequired = true
            )
        }
        check(before.nextSeq < Long.MAX_VALUE) { "package observation sequence exhausted" }
        return before.copy(
            nextSeq = before.nextSeq + 1,
            observations = before.observations + GuardPackageObservation(before.nextSeq, type, packageName, observedAt, before.journalEpoch)
        )
    }

    fun reserveWake(): String? = synchronized(ioLock) {
        loadUnderIo()
        synchronized(lock) {
            if (runningOwner != null || wakeReservation != null || !requireNotNull(document).hasPending()) {
                null
            } else {
                UUID.randomUUID().toString().also { wakeReservation = it }
            }
        }
    }

    fun releaseFailedWake(reservation: String) = synchronized(lock) {
        if (wakeReservation == reservation) wakeReservation = null
    }

    fun beginDrain(): String? = synchronized(lock) {
        if (runningOwner != null) {
            null
        } else {
            UUID.randomUUID().toString().also {
                runningOwner = it
                wakeReservation = null
            }
        }
    }

    fun claim(owner: String, limit: Int = 32): GuardPackageWorkClaim = synchronized(ioLock) {
        require(limit > 0)
        loadUnderIo()
        synchronized(lock) {
            check(runningOwner == owner) { "package drain owner changed" }
            check(activeClaim == null) { "package work claim is not settled" }
            val current = requireNotNull(document)
            GuardPackageWorkClaim(
                owner,
                current.epoch,
                current.revision,
                current.observations.take(limit),
                current.dropped,
                current.fullRepairRequired,
                current.journalEpoch,
                current.journalDropped,
            )
                .also { activeClaim = it }
        }
    }

    fun acknowledge(claim: GuardPackageWorkClaim, repairSettled: Boolean): Boolean = synchronized(ioLock) {
        loadUnderIo()
        val next = synchronized(lock) {
            check(runningOwner == claim.owner) { "package drain owner changed" }
            check(activeClaim === claim) { "package work claim changed" }
            val current = requireNotNull(document)
            if (current.epoch != claim.epoch) {
                activeClaim = null
                return@synchronized null
            }
            val ids = claim.observations.map(GuardPackageObservation::seq).toSet()
            current.copy(
                observations = current.observations.filterNot { it.seq in ids },
                dropped = (current.dropped - claim.dropped).coerceAtLeast(0L),
                journalDropped = if (current.journalEpoch == claim.journalEpoch) {
                    (current.journalDropped - claim.journalDropped).coerceAtLeast(0L)
                } else {
                    current.journalDropped
                },
                fullRepairRequired = current.fullRepairRequired && (!repairSettled || current.revision != claim.revision),
            )
        } ?: return@synchronized false
        val persisted = persistUnderIo(next)
        synchronized(lock) { activeClaim = null }
        persisted
    }

    fun finishDrain(owner: String, reserveSuccessor: Boolean = true): String? = synchronized(ioLock) {
        loadUnderIo()
        synchronized(lock) {
            check(runningOwner == owner) { "package drain owner changed" }
            runningOwner = null
            activeClaim = null
            if (!reserveSuccessor || !requireNotNull(document).hasPending() || wakeReservation != null) {
                null
            } else {
                UUID.randomUUID().toString().also { wakeReservation = it }
            }
        }
    }

    fun currentEpoch(): String = synchronized(ioLock) {
        loadUnderIo()
        requireNotNull(epoch)
    }

    fun invalidateEpoch() = synchronized(lock) {
        val empty = GuardPackageWorkDocument()
        document = empty
        epoch = empty.epoch
        journalEpoch = empty.journalEpoch
        journalOverride = null
        journalFencePending = false
        resetPending = true
        wakeReservation = null
    }

    fun invalidateJournalEpoch() = synchronized(lock) {
        val next = UUID.randomUUID().toString()
        journalEpoch = next
        journalOverride = next
        document = document?.copy(journalEpoch = next, journalDropped = 0L)
        journalFencePending = true
    }

    fun settleJournalInvalidation() = synchronized(ioLock) {
        loadUnderIo()
        val current = synchronized(lock) { document.takeIf { journalFencePending } } ?: return@synchronized
        check(persistUnderIo(requireNotNull(current))) { "guard package journal epoch changed" }
    }

    fun currentJournalEpoch(): String = synchronized(ioLock) {
        loadUnderIo()
        requireNotNull(journalEpoch)
    }

    fun isJournalCurrent(expected: String): Boolean = journalEpoch == expected

    fun settleInvalidation() = synchronized(ioLock) {
        val resetEpoch = synchronized(lock) { document?.epoch.takeIf { resetPending } } ?: return@synchronized
        check(!file.exists() || file.delete()) { "guard package work cleanup failed" }
        synchronized(lock) { if (epoch == resetEpoch) resetPending = false }
    }

    fun isCurrent(expected: String): Boolean = epoch == expected

    fun pendingCount(): Int = synchronized(ioLock) {
        loadUnderIo()
        synchronized(lock) { requireNotNull(document).observations.size }
    }

    private fun loadUnderIo() {
        if (synchronized(lock) { document != null }) return
        val loaded = if (!file.exists()) {
            GuardPackageWorkDocument()
        } else {
            check(file.length() <= MAX_WORK_FILE_BYTES) { "package work file too large" }
            json.decodeFromString(GuardPackageWorkDocument.serializer(), cipher.readBytes(file).decodeToString())
        }
        check(
            loaded.observations.size <= capacity && loaded.nextSeq > 0L && loaded.dropped >= 0L
        ) { "invalid package work document" }
        synchronized(lock) {
            if (document == null) {
                val migrated = loaded.copy(
                    observations = loaded.observations.map {
                        if (it.journalEpoch.isBlank()) it.copy(journalEpoch = loaded.journalEpoch) else it
                    }
                )
                val installed = journalOverride?.let { migrated.copy(journalEpoch = it, journalDropped = 0L) } ?: migrated
                document = installed
                epoch = installed.epoch
                journalEpoch = installed.journalEpoch
            }
        }
    }

    private fun persistUnderIo(value: GuardPackageWorkDocument): Boolean {
        file.parentFile?.mkdirs()
        cipher.writeBytesAtomic(
            file,
            json.encodeToString(GuardPackageWorkDocument.serializer(), value).encodeToByteArray()
        )
        return synchronized(lock) {
            if (epoch != value.epoch) {
                false
            } else {
                val journalUnchanged = journalEpoch == value.journalEpoch
                document = if (journalUnchanged) value else value.copy(journalEpoch = requireNotNull(journalEpoch), journalDropped = 0L)
                if (journalUnchanged) journalFencePending = false
                resetPending = false
                true
            }
        }
    }

    private fun GuardPackageWorkDocument.hasPending(): Boolean = observations.isNotEmpty() || dropped > 0L || fullRepairRequired
    private fun Long.saturatedIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1L

    private companion object {
        const val MAX_PACKAGE_NAME_LENGTH = 255
        const val MAX_WORK_FILE_BYTES = 512L * 1024L
        val PACKAGE_EVENT_TYPES = setOf(
            GuardEventType.PACKAGE_ADDED,
            GuardEventType.PACKAGE_REMOVED,
            GuardEventType.PACKAGE_REPLACED
        )
    }
}
