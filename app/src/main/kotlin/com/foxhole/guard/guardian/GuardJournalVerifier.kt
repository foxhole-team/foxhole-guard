package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.GuardCrypto
import com.foxhole.guard.core.security.KeyboxCheckpoint
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

enum class GuardJournalStatus {
    OK,
    GAPS,
    TRUNCATED,
    REWRITTEN,
}

class GuardJournalAnomaly(
    val seq: Long?,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        CHAIN_BREAK,
        SEQUENCE_GAP,
        UNREADABLE_RECORD,
        SEAL_UNREADABLE,
        CLOCK_ROLLBACK,
        MONOTONIC_CLOCK_REGRESSION,
        CHECKPOINT_MISMATCH,
        TAIL_MISSING,
    }
}

class GuardJournalReport(
    val status: GuardJournalStatus,
    val entries: List<GuardJournalEntry>,
    val anomalies: List<GuardJournalAnomaly>,
    val checkpointMatched: Boolean,
    val headSeq: Long?,
    val headHash: String?,
)

/**
 * Post-unlock verification: walks every journal file in seq order, recomputes the
 * hash chain, opens sealed payloads with the unwrapped private key, checks clock
 * monotonicity and compares the password-anchored keybox checkpoint against the
 * recomputed line hash at that seq. History missing BEFORE the checkpoint is legal
 * pruning; anything else missing, edited or reordered surfaces as an anomaly.
 */
internal class GuardJournalVerifier(
    private val directory: File,
    private val crypto: GuardCrypto,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("CyclomaticComplexMethod")
    fun verify(
        publicKey: ByteArray,
        privateKey: ByteArray,
        checkpoint: KeyboxCheckpoint?,
    ): GuardJournalReport {
        val entries = mutableListOf<GuardJournalEntry>()
        val anomalies = mutableListOf<GuardJournalAnomaly>()
        var expectedSeq: Long? = null
        var runningHash: String? = null
        var lastWallClock = 0L
        var lastElapsed = 0L
        var lastBootCount = 0
        var checkpointMatched = false
        var headSeq: Long? = null
        var headHash: String? = null
        val anchorSeq = if (checkpoint != null && checkpoint.seq >= 0) checkpoint.seq else null
        val anchorHash = checkpoint?.headHash

        val files =
            directory
                .listFiles { file -> file.isFile && file.name.startsWith("guard-") && file.name.endsWith(".jsonl") }
                .orEmpty()
                .sortedBy(GuardJournal::firstSeqOf)

        for (file in files) {
            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) {
                        return@forEach
                    }
                    val record = decodeRecordOrNull(line)
                    if (record == null) {
                        anomalies += GuardJournalAnomaly(expectedSeq, GuardJournalAnomaly.Kind.UNREADABLE_RECORD, "unparseable journal line")
                        return@forEach
                    }
                    if (expectedSeq == null) {
                        // First surviving record: history before it is legal only when checkpointed.
                        if (record.seq > 0 && (anchorSeq == null || record.seq > anchorSeq + 1)) {
                            anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.SEQUENCE_GAP, "journal starts past the checkpoint")
                        }
                    } else {
                        if (record.seq != expectedSeq) {
                            anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.SEQUENCE_GAP, "expected seq $expectedSeq, found ${record.seq}")
                        } else if (record.prevHash != runningHash) {
                            anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.CHAIN_BREAK, "hash chain broken at seq ${record.seq}")
                        }
                    }
                    val event =
                        runCatching {
                            val sealedBytes = Base64.getDecoder().decode(record.sealed)
                            json.decodeFromString(
                                GuardEvent.serializer(),
                                crypto.openSealed(sealedBytes, publicKey, privateKey).decodeToString()
                            )
                        }.getOrNull()
                    if (event == null) {
                        anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.SEAL_UNREADABLE, "sealed payload cannot be opened with this keybox")
                    }
                    if (record.bootCount == lastBootCount && record.elapsedRealtime < lastElapsed) {
                        anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.MONOTONIC_CLOCK_REGRESSION, "elapsedRealtime went backwards within one boot")
                    }
                    if (record.wallClock < lastWallClock - WALL_CLOCK_ROLLBACK_TOLERANCE_MS) {
                        anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.CLOCK_ROLLBACK, "wall clock rolled back by ${lastWallClock - record.wallClock} ms")
                    }
                    val lineHash = GuardJournal.sha256Hex(line)
                    if (anchorSeq != null && record.seq == anchorSeq) {
                        checkpointMatched = lineHash == anchorHash
                        if (!checkpointMatched) {
                            anomalies += GuardJournalAnomaly(record.seq, GuardJournalAnomaly.Kind.CHECKPOINT_MISMATCH, "record at the checkpoint seq does not match the anchored hash")
                        }
                    }
                    entries += GuardJournalEntry(record = record, event = event)
                    expectedSeq = record.seq + 1
                    runningHash = lineHash
                    lastWallClock = maxOf(lastWallClock, record.wallClock)
                    lastElapsed = if (record.bootCount == lastBootCount) maxOf(lastElapsed, record.elapsedRealtime) else record.elapsedRealtime
                    lastBootCount = maxOf(lastBootCount, record.bootCount)
                    headSeq = record.seq
                    headHash = lineHash
                }
            }
        }

        val finalHeadSeq = headSeq
        val checkpointSeq = anchorSeq
        if (checkpointSeq != null && !checkpointMatched) {
            val tailCut = finalHeadSeq == null || finalHeadSeq < checkpointSeq
            anomalies +=
                GuardJournalAnomaly(
                    checkpointSeq,
                    if (tailCut) GuardJournalAnomaly.Kind.TAIL_MISSING else GuardJournalAnomaly.Kind.CHECKPOINT_MISMATCH,
                    if (tailCut) "journal ends before the anchored checkpoint" else "anchored checkpoint record is missing from the chain",
                )
        }

        return GuardJournalReport(
            status = statusFrom(anomalies, checkpointSeq, checkpointMatched, finalHeadSeq),
            entries = entries,
            anomalies = anomalies,
            checkpointMatched = checkpointMatched || checkpointSeq == null,
            headSeq = finalHeadSeq,
            headHash = headHash,
        )
    }

    private fun statusFrom(
        anomalies: List<GuardJournalAnomaly>,
        checkpointSeq: Long?,
        checkpointMatched: Boolean,
        headSeq: Long?,
    ): GuardJournalStatus {
        val kinds = anomalies.map(GuardJournalAnomaly::kind).toSet()
        return when {
            checkpointSeq != null && !checkpointMatched && (headSeq == null || headSeq < checkpointSeq) -> GuardJournalStatus.TRUNCATED
            checkpointSeq != null && !checkpointMatched -> GuardJournalStatus.REWRITTEN
            GuardJournalAnomaly.Kind.CHAIN_BREAK in kinds || GuardJournalAnomaly.Kind.SEAL_UNREADABLE in kinds -> GuardJournalStatus.REWRITTEN
            GuardJournalAnomaly.Kind.UNREADABLE_RECORD in kinds -> GuardJournalStatus.TRUNCATED
            GuardJournalAnomaly.Kind.SEQUENCE_GAP in kinds ||
                GuardJournalAnomaly.Kind.CLOCK_ROLLBACK in kinds ||
                GuardJournalAnomaly.Kind.MONOTONIC_CLOCK_REGRESSION in kinds -> GuardJournalStatus.GAPS
            else -> GuardJournalStatus.OK
        }
    }

    private fun decodeRecordOrNull(line: String): GuardJournalRecord? =
        runCatching { json.decodeFromString(GuardJournalRecord.serializer(), line) }.getOrNull()

    private companion object {
        const val WALL_CLOCK_ROLLBACK_TOLERANCE_MS = 5L * 60L * 1000L
    }
}
