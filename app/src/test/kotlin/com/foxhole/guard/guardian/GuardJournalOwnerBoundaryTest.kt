package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.FakeGuardCrypto
import com.foxhole.guard.core.security.GuardCrypto
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardJournalOwnerBoundaryTest {
    @get:Rule val directory = TemporaryFolder()
    private fun journal(crypto: GuardCrypto): GuardJournal = GuardJournal(
        directory.newFolder(),
        crypto,
        object : GuardClock {
            override fun wallClockMs(): Long = 1000
            override fun elapsedRealtimeMs(): Long = 1000
            override fun bootCount(): Int = 1
        },
        { ByteArray(32) },
        { -1L },
    )
    private fun failing(throwable: Throwable): GuardCrypto = object : GuardCrypto by FakeGuardCrypto() {
        override fun seal(payload: ByteArray, publicKey: ByteArray): ByteArray = throw throwable
    }

    @Test fun cancellationIsTransparent() {
        val failure = CancellationException("owned cancellation")
        val owner = journal(failing(failure))
        val observed = runCatching { owner.append(GuardEvent(GuardEventType.HEARTBEAT)) }.exceptionOrNull()
        assertSame(failure, observed)
    }

    @Test fun fatalIsTransparent() {
        val failure = Error("owned fatal")
        val owner = journal(failing(failure))
        val observed = runCatching { owner.append(GuardEvent(GuardEventType.HEARTBEAT)) }.exceptionOrNull()
        assertSame(failure, observed)
    }

    @Test fun ordinaryAppendFailureIsRefused() {
        assertFalse(
            journal(failing(IllegalStateException("owned unavailable"))).append(GuardEvent(GuardEventType.HEARTBEAT))
        )
    }

    @Test fun queuedPreClearWriteIsRefusedAndNewWriteAccepted() {
        val owner = journal(FakeGuardCrypto())
        val old = owner.captureWriteEpoch()
        assertTrue(owner.append(GuardEvent(GuardEventType.HEARTBEAT)))
        owner.deleteAll()
        assertFalse(owner.appendAtEpoch(GuardEvent(GuardEventType.PACKAGE_ADDED), old))
        assertTrue(owner.appendAtEpoch(GuardEvent(GuardEventType.HEARTBEAT), owner.captureWriteEpoch()))
    }

    @Test fun clearHookRunsAfterFenceAndBeforeFileDeletion() {
        val owner = journal(FakeGuardCrypto())
        val old = owner.captureWriteEpoch()
        assertTrue(owner.append(GuardEvent(GuardEventType.HEARTBEAT)))
        owner.deleteAll {
            assertTrue(old != owner.captureWriteEpoch())
            assertEquals(1000L, owner.lastRecordWallClockMs())
            assertFalse(owner.appendAtEpoch(GuardEvent(GuardEventType.PACKAGE_ADDED), old))
        }
        assertEquals(null, owner.lastRecordWallClockMs())
    }

    @Test fun failedHookRetainsJournalButFencesOldWork() {
        val owner = journal(FakeGuardCrypto())
        val old = owner.captureWriteEpoch()
        assertTrue(owner.append(GuardEvent(GuardEventType.HEARTBEAT)))
        val failure = IllegalStateException("owned settlement failure")
        assertSame(failure, runCatching { owner.deleteAll { throw failure } }.exceptionOrNull())
        assertFalse(owner.appendAtEpoch(GuardEvent(GuardEventType.PACKAGE_ADDED), old))
        assertEquals(1000L, owner.lastRecordWallClockMs())
    }
}
