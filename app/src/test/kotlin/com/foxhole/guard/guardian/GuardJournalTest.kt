package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.FakeGuardCrypto
import com.foxhole.guard.core.security.GuardKeypair
import com.foxhole.guard.core.security.KeyboxCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeGuardClock(
    var wallClock: Long = 1_700_000_000_000L,
    var elapsed: Long = 100_000L,
    var boots: Int = 1,
) : GuardClock {
    override fun wallClockMs(): Long = wallClock

    override fun elapsedRealtimeMs(): Long = elapsed

    override fun bootCount(): Int = boots

    fun advance(ms: Long) {
        wallClock += ms
        elapsed += ms
    }

    fun reboot() {
        boots += 1
        elapsed = 1_000L
        wallClock += 60_000L
    }
}

class GuardJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var crypto: FakeGuardCrypto
    private lateinit var keypair: GuardKeypair
    private lateinit var clock: FakeGuardClock
    private var checkpointSeq: Long = -1L

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder("journal")
        crypto = FakeGuardCrypto()
        keypair = crypto.sealedBoxKeypair()
        clock = FakeGuardClock()
        checkpointSeq = -1L
    }

    private fun journal(
        maxFileBytes: Long = GuardJournal.MAX_FILE_BYTES,
        maxFiles: Int = GuardJournal.MAX_FILES,
        publicKey: ByteArray? = keypair.publicKey,
    ) = GuardJournal(
        directory = directory,
        crypto = crypto,
        clock = clock,
        publicKeyProvider = { publicKey },
        checkpointSeqProvider = { checkpointSeq },
        maxFileBytes = maxFileBytes,
        maxFiles = maxFiles,
    )

    private fun verifier() = GuardJournalVerifier(directory, crypto)

    private fun verify(checkpoint: KeyboxCheckpoint? = null): GuardJournalReport =
        verifier().verify(keypair.publicKey, keypair.privateKey, checkpoint)

    @Test
    fun `append seals entries and the verifier round-trips them in order`() {
        val journal = journal()
        journal.append(GuardEvent(type = GuardEventType.GUARD_ENABLED))
        clock.advance(1_000L)
        journal.append(
            GuardEvent(
                type = GuardEventType.PACKAGE_ADDED,
                packageName = "com.evil.app",
                installer = "com.android.vending"
            )
        )
        clock.advance(1_000L)
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))

        val report = verify()

        assertEquals(GuardJournalStatus.OK, report.status)
        assertTrue(report.anomalies.isEmpty())
        // GENESIS is auto-prepended before the first explicit event.
        assertEquals(4, report.entries.size)
        assertEquals(GuardEventType.GENESIS, report.entries[0].event?.type)
        assertEquals(GuardEventType.PACKAGE_ADDED, report.entries[2].event?.type)
        assertEquals("com.evil.app", report.entries[2].event?.packageName)
        assertEquals((0L..3L).toList(), report.entries.map { it.record.seq })
    }

    @Test
    fun `append writes nothing when the guard public key is unavailable`() {
        val journal = journal(publicKey = null)

        assertFalse(journal.append(GuardEvent(type = GuardEventType.HEARTBEAT)))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `editing a record breaks the chain and is reported as rewritten`() {
        val journal = journal()
        repeat(3) {
            journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
            clock.advance(1_000L)
        }
        val file = directory.listFiles()!!.single()
        val lines = file.readLines().toMutableList()
        lines[1] = lines[1].replace("\"seq\":1", "\"seq\":1,\"forged\":true")
        file.writeText(lines.joinToString("\n") + "\n")

        val report = verify()

        assertEquals(GuardJournalStatus.REWRITTEN, report.status)
        assertTrue(report.anomalies.any { it.kind == GuardJournalAnomaly.Kind.CHAIN_BREAK })
    }

    @Test
    fun `truncating the tail below the checkpoint is reported as truncated`() {
        val journal = journal()
        repeat(5) {
            journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
            clock.advance(1_000L)
        }
        val head = verify()
        val checkpoint = KeyboxCheckpoint(seq = head.headSeq!!, headHash = head.headHash!!)
        val file = directory.listFiles()!!.single()
        val lines = file.readLines()
        file.writeText(lines.dropLast(2).joinToString("\n") + "\n")

        val report = verify(checkpoint)

        assertEquals(GuardJournalStatus.TRUNCATED, report.status)
        assertFalse(report.checkpointMatched)
    }

    @Test
    fun `wholesale rewrite after the checkpoint is reported as rewritten`() {
        val journal = journal()
        repeat(4) {
            journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
            clock.advance(1_000L)
        }
        val head = verify()
        val checkpoint = KeyboxCheckpoint(seq = head.headSeq!!, headHash = head.headHash!!)

        // Attacker wipes everything and fabricates a fresh-looking chain of the same length.
        directory.listFiles()!!.forEach(File::delete)
        val forged = journal()
        repeat(4) {
            forged.append(GuardEvent(type = GuardEventType.HEARTBEAT))
            clock.advance(1_000L)
        }

        val report = verify(checkpoint)

        assertEquals(GuardJournalStatus.REWRITTEN, report.status)
        assertTrue(report.anomalies.any { it.kind == GuardJournalAnomaly.Kind.CHECKPOINT_MISMATCH })
    }

    @Test
    fun `rotation keeps one chain across files and a deleted middle file is a gap`() {
        val journal = journal(maxFileBytes = 600L)
        repeat(12) {
            journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
            clock.advance(500L)
        }
        val files = directory.listFiles()!!.sortedBy(GuardJournal::firstSeqOf)
        assertTrue("rotation should have produced several files, got ${files.size}", files.size >= 3)
        assertEquals(GuardJournalStatus.OK, verify().status)

        files[1].delete()

        val report = verify()
        assertEquals(GuardJournalStatus.GAPS, report.status)
        assertTrue(report.anomalies.any { it.kind == GuardJournalAnomaly.Kind.SEQUENCE_GAP })
    }

    @Test
    fun `pre-checkpoint pruning does not look like tampering`() {
        val journal = journal(maxFileBytes = 600L, maxFiles = 2)
        repeat(12) {
            journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
            clock.advance(500L)
        }
        val head = verify()
        val verifiedHeadSeq = checkNotNull(head.headSeq)
        checkpointSeq = verifiedHeadSeq
        // The next append triggers pruning of files older than the checkpoint.
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))

        val remaining = directory.listFiles()!!.size
        assertTrue("old files should be pruned, remaining=$remaining", remaining <= 3)

        val report = verify(KeyboxCheckpoint(seq = verifiedHeadSeq, headHash = checkNotNull(head.headHash)))
        assertEquals(GuardJournalStatus.OK, report.status)
        assertTrue(report.checkpointMatched)
    }

    @Test
    fun `wall clock rollback is flagged as a clock anomaly`() {
        val journal = journal()
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
        clock.wallClock -= 30L * 60L * 1000L
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))

        val report = verify()

        assertEquals(GuardJournalStatus.GAPS, report.status)
        assertTrue(report.anomalies.any { it.kind == GuardJournalAnomaly.Kind.CLOCK_ROLLBACK })
    }

    @Test
    fun `elapsed clock regression within one boot prevents a clean status`() {
        val journal = journal()
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
        clock.advance(1_000L)
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
        clock.elapsed -= 500L
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))

        val report = verify()

        assertEquals(GuardJournalStatus.GAPS, report.status)
        assertTrue(
            report.anomalies.any { anomaly ->
                anomaly.kind == GuardJournalAnomaly.Kind.MONOTONIC_CLOCK_REGRESSION
            },
        )
    }

    @Test
    fun `reboot resets elapsedRealtime without a false anomaly`() {
        val journal = journal()
        journal.append(GuardEvent(type = GuardEventType.HEARTBEAT))
        clock.reboot()
        journal.append(GuardEvent(type = GuardEventType.BOOT_COMPLETED))

        val report = verify()

        assertEquals(GuardJournalStatus.OK, report.status)
        assertTrue(report.anomalies.isEmpty())
    }

    @Test
    fun `head state survives a process restart`() {
        journal().append(GuardEvent(type = GuardEventType.HEARTBEAT))
        clock.advance(1_000L)

        val reloaded = journal()
        reloaded.append(GuardEvent(type = GuardEventType.HEARTBEAT))

        val report = verify()
        assertEquals(2L, report.headSeq)
        assertEquals(GuardJournalStatus.OK, report.status)
    }

    @Test
    fun `sealed payloads are unreadable with a foreign keypair`() {
        journal().append(GuardEvent(type = GuardEventType.PACKAGE_ADDED, packageName = "secret.app"))
        val stranger = crypto.sealedBoxKeypair()

        val report = verifier().verify(stranger.publicKey, stranger.privateKey, null)

        assertTrue(report.entries.all { it.event == null })
        assertTrue(report.anomalies.any { it.kind == GuardJournalAnomaly.Kind.SEAL_UNREADABLE })
    }

    @Test
    fun `inventory diff detects installs removals and version changes`() {
        val before =
            GuardInventorySnapshot(
                capturedAt = 1L,
                apps =
                listOf(
                    GuardInventoryApp("com.keep", versionCode = 1),
                    GuardInventoryApp("com.gone", versionCode = 5),
                    GuardInventoryApp("com.upgraded", versionCode = 2),
                ),
            )
        val after =
            GuardInventorySnapshot(
                capturedAt = 2L,
                apps =
                listOf(
                    GuardInventoryApp("com.keep", versionCode = 1),
                    GuardInventoryApp("com.upgraded", versionCode = 3),
                    GuardInventoryApp("com.fresh", versionCode = 1),
                ),
            )

        val events = GuardInventoryDiff.diff(before, after)

        assertEquals(3, events.size)
        assertEquals(GuardEventType.PACKAGE_ADDED, events.first { it.packageName == "com.fresh" }.type)
        assertEquals(GuardEventType.PACKAGE_REMOVED, events.first { it.packageName == "com.gone" }.type)
        assertEquals(GuardEventType.PACKAGE_REPLACED, events.first { it.packageName == "com.upgraded" }.type)
        assertTrue(GuardInventoryDiff.diff(GuardInventorySnapshot(), after).isEmpty())
    }
}
