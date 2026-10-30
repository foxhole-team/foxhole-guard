package com.foxhole.guard.guardian

import com.foxhole.guard.core.sentinel.FileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GuardPackageWorkStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `durable ordered add remove observations survive a fresh process store`() {
        val file = file()
        val first = store(file)
        first.enqueue(GuardEventType.PACKAGE_ADDED, "owned.transient", 1, { true })
        first.enqueue(GuardEventType.PACKAGE_REMOVED, "owned.transient", 2, { true })
        val restarted = store(file)
        assertNotNull(restarted.reserveWake())
        val owner = checkNotNull(restarted.beginDrain())
        val claim = restarted.claim(owner)
        assertEquals(
            listOf(GuardEventType.PACKAGE_ADDED, GuardEventType.PACKAGE_REMOVED),
            claim.observations.map { it.type }
        )
        assertEquals(listOf(1L, 2L), claim.observations.map { it.seq })
        restarted.acknowledge(claim, true)
        assertEquals(null, restarted.finishDrain(owner))
    }

    @Test
    fun `failed journal or baseline settlement leaves claimed observations pending for retry`() {
        val subject = store(file())
        enqueue(subject)
        val owner = checkNotNull(subject.beginDrain())
        val claim = subject.claim(owner)
        assertEquals(1, claim.observations.size)
        val reservation = checkNotNull(subject.finishDrain(owner))
        subject.releaseFailedWake(reservation)
        val second = checkNotNull(subject.beginDrain())
        assertEquals(claim.observations, subject.claim(second).observations)
    }

    @Test
    fun `arrival while running is not removed by acknowledgement of an older claim`() {
        val subject = store(file())
        enqueue(subject)
        val owner = checkNotNull(subject.beginDrain())
        val claimed = subject.claim(owner)
        subject.enqueue(GuardEventType.PACKAGE_REPLACED, "owned.next", 2, { true })
        assertEquals(null, subject.reserveWake())
        subject.acknowledge(claimed, true)
        assertNotNull(subject.finishDrain(owner))
        assertEquals(null, subject.reserveWake())
        val successor = checkNotNull(subject.beginDrain())
        assertEquals(listOf("owned.next"), subject.claim(successor).observations.map { it.packageName })
    }

    @Test
    fun `arrival after final empty transition reserves exactly one successor wake`() {
        val subject = store(file())
        val owner = checkNotNull(subject.beginDrain())
        val empty = subject.claim(owner)
        subject.acknowledge(empty, true)
        assertEquals(null, subject.finishDrain(owner))
        enqueue(subject)
        assertNotNull(subject.reserveWake())
        assertEquals(null, subject.reserveWake())
    }

    @Test
    fun `failed scheduler reservation can be retried without acknowledging pending work`() {
        val subject = store(file())
        enqueue(subject)
        val reservation = checkNotNull(subject.reserveWake())
        subject.releaseFailedWake(reservation)
        assertEquals(1, subject.pendingCount())
        assertNotNull(subject.reserveWake())
    }

    @Test
    fun `capacity records a durable typed gap and repair requirement without replacing admitted edges`() {
        val file = file()
        val subject = store(file, 1)
        enqueue(subject)
        assertEquals(
            GuardPackageAdmission.GAP_RECORDED,
            subject.enqueue(GuardEventType.PACKAGE_REMOVED, "owned.overflow", 2, { true })
        )
        val restarted = store(file, 1)
        val owner = checkNotNull(restarted.beginDrain())
        val claim = restarted.claim(owner)
        assertEquals(1, claim.observations.size)
        assertEquals(1L, claim.dropped)
        assertTrue(claim.fullRepairRequired)
    }

    @Test
    fun `new gap during repair cannot be cleared by acknowledgement of an earlier repair snapshot`() {
        val subject = store(file(), 1)
        enqueue(subject)
        subject.enqueue(GuardEventType.PACKAGE_REMOVED, "owned.firstgap", 2, { true })
        val owner = checkNotNull(subject.beginDrain())
        val before = subject.claim(owner)
        subject.enqueue(GuardEventType.PACKAGE_REMOVED, "owned.secondgap", 3, { true })
        subject.acknowledge(before, true)
        val after = subject.claim(owner)
        assertEquals(1L, after.dropped)
        assertTrue(after.fullRepairRequired)
    }

    @Test
    fun `privacy epoch invalidation rejects old claim and old append predicate`() {
        val subject = store(file())
        enqueue(subject)
        val owner = checkNotNull(subject.beginDrain())
        val old = subject.claim(owner)
        subject.invalidateEpoch()
        assertFalse(subject.isCurrent(old.epoch))
        subject.acknowledge(old, true)
        assertEquals(0, subject.pendingCount())
        assertEquals(null, subject.finishDrain(owner))
    }

    @Test
    fun `inactive admission writes no file or package facts`() {
        val file = file()
        val subject = store(file)
        assertEquals(
            GuardPackageAdmission.INACTIVE,
            subject.enqueue(GuardEventType.PACKAGE_ADDED, "owned.inactive", 1, { false })
        )
        assertFalse(file.exists())
    }

    @Test
    fun `an acknowledged claim cannot acknowledge later arrivals twice`() {
        val subject = store(file())
        enqueue(subject)
        val owner = checkNotNull(subject.beginDrain())
        val claim = subject.claim(owner)
        subject.acknowledge(claim, true)
        enqueue(subject)
        assertTrue(runCatching { subject.acknowledge(claim, true) }.isFailure)
        assertEquals(1, subject.pendingCount())
    }

    @Test
    fun `simultaneous admitted edges remain ordered and durable with unique sequence numbers`() {
        val subject = store(file())
        val start = CountDownLatch(1)
        val errors = AtomicReference<Throwable>()
        val threads = (1..16).map { id ->
            Thread {
                try {
                    check(
                        start.await(5, TimeUnit.SECONDS)
                    )
                    subject.enqueue(
                        GuardEventType.PACKAGE_ADDED,
                        "owned.$id",
                        id.toLong(),
                        { true }
                    )
                } catch (error: Throwable) { errors.compareAndSet(null, error) }
            }
        }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach {
            it.join(5000)
            assertFalse(it.isAlive)
        }
        assertEquals(null, errors.get())
        val owner = checkNotNull(subject.beginDrain())
        val admitted = subject.claim(owner).observations
        assertEquals((1L..16L).toList(), admitted.map { it.seq })
        assertEquals(16, admitted.map { it.packageName }.toSet().size)
    }

    @Test
    fun `late failure from consumed wake does not release a newer successor reservation`() {
        val subject = store(file())
        enqueue(subject)
        val oldWake = checkNotNull(subject.reserveWake())
        val owner = checkNotNull(subject.beginDrain())
        val successor = checkNotNull(subject.finishDrain(owner))
        subject.releaseFailedWake(oldWake)
        assertEquals(null, subject.reserveWake())
        subject.releaseFailedWake(successor)
        assertNotNull(subject.reserveWake())
    }

    @Test
    fun `logical queue invalidation does not wait on paused durable admission`() {
        val file = file()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val subject = GuardPackageWorkStore(
            file,
            object : FileCipher {
                override fun readBytes(file: File): ByteArray = file.readBytes()
                override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    file.writeBytes(plaintext)
                }
            }
        )
        val result = AtomicReference<GuardPackageAdmission>()
        val admission = Thread { result.set(subject.enqueue(GuardEventType.PACKAGE_ADDED, "owned.stale", 1, { true })) }
        val invalidated = CountDownLatch(1)
        val invalidate = Thread {
            subject.invalidateEpoch()
            invalidated.countDown()
        }
        try {
            admission.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            invalidate.start()
            assertTrue(invalidated.await(5, TimeUnit.SECONDS))
            release.countDown()
            admission.join(5000)
            assertEquals(GuardPackageAdmission.INACTIVE, result.get())
            assertEquals(0, subject.pendingCount())
            subject.settleInvalidation()
            assertFalse(file.exists())
        } finally {
            release.countDown()
            admission.join(5000)
            invalidate.join(5000)
        }
    }

    @Test
    fun `failed queue cleanup remains fenced and reports failure instead of accepting old disk contents`() {
        val file = object : File(temporary.newFolder(), "owned-refused-cleanup.json") {
            override fun delete(): Boolean = false
        }
        val subject = store(file)
        enqueue(subject)
        val epoch = subject.currentEpoch()
        subject.invalidateEpoch()
        assertTrue(runCatching { subject.settleInvalidation() }.isFailure)
        assertFalse(subject.isCurrent(epoch))
        assertEquals(0, subject.pendingCount())
    }

    @Test
    fun `journal fence during paused admission preserves durable edge but suppresses old journal eligibility`() {
        val file = file()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var pause = true
        val cipher = object : FileCipher {
            override fun readBytes(file: File): ByteArray = file.readBytes()
            override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                if (pause) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                file.writeBytes(plaintext)
            }
        }
        val subject = GuardPackageWorkStore(file, cipher)
        val result = AtomicReference<GuardPackageAdmission>()
        val admission = Thread {
            result.set(
                subject.enqueue(GuardEventType.PACKAGE_REMOVED, "owned.remove", 1, { true })
            )
        }
        val fenced = CountDownLatch(1)
        val fence = Thread {
            subject.invalidateJournalEpoch()
            fenced.countDown()
        }
        try {
            admission.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fence.start()
            assertTrue(fenced.await(5, TimeUnit.SECONDS))
            release.countDown()
            admission.join(5000)
            assertEquals(GuardPackageAdmission.ACCEPTED, result.get())
            pause = false
            subject.settleJournalInvalidation()
            val restarted = GuardPackageWorkStore(file, cipher)
            val owner = checkNotNull(restarted.beginDrain())
            val claim = restarted.claim(owner)
            assertEquals(1, claim.observations.size)
            assertFalse(restarted.isJournalCurrent(claim.observations.single().journalEpoch))
        } finally {
            release.countDown()
            admission.join(5000)
            fence.join(5000)
        }
    }

    private fun enqueue(subject: GuardPackageWorkStore) =
        subject.enqueue(GuardEventType.PACKAGE_ADDED, "owned.first", 1, { true })
    private fun file(): File = File(temporary.newFolder(), "owned-package-work.json")
    private fun store(file: File, capacity: Int = 256) = GuardPackageWorkStore(file, OwnedFileCipher, capacity)

    private object OwnedFileCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes()
        override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
            val staging = File(file.parentFile, "${file.name}.staging")
            FileOutputStream(staging).use { out ->
                out.write(plaintext)
                out.fd.sync()
            }
            check(staging.renameTo(file))
        }
    }
}
