package com.foxhole.guard

import com.foxhole.core.model.AppLocale
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

internal class AppLanguageCoordinatorTest {
    private val en = AppLocale.EN
    private val ru = AppLocale.RU
    private val de = AppLocale.DE
    private val fr = AppLocale.FR
    private val system = AppLocale.SYSTEM

    private class FakePort(
        var encrypted: AppLocale,
        var platform: AppLocale?,
        var journal: AppLanguageJournal<AppLocale> = AppLanguageJournal(),
    ) : AppLanguagePort<AppLocale> {
        var known = true
        var failPendingBefore = false
        var failPendingAfter = false
        var failWriteBefore = false
        var failWriteAfter = false
        var failPlatformBefore = false
        var failPlatformAfter = false
        var failCompleteBefore = false
        var failCompleteAfter = false
        var writes = 0
        var applies = 0
        var falseCompletions = 0
        var beforeWrite: suspend () -> Unit = {}
        var beforeApply: suspend () -> Unit = {}
        override suspend fun readStored(): AppLocale? = encrypted.takeIf { known }
        override suspend fun readPlatform(): AppLocale? = platform
        override suspend fun readJournal(): AppLanguageJournal<AppLocale> = journal
        override suspend fun savePending(pending: PendingAppLanguage<AppLocale>) {
            if (failPendingBefore) throw IOException("pending_before")
            journal = journal.copy(pending = pending)
            if (failPendingAfter) throw IOException("pending_after")
        }
        override suspend fun writeStored(expected: AppLocale, desired: AppLocale): Boolean {
            beforeWrite()
            if (failWriteBefore) throw IOException("encrypted_before")
            if (encrypted != expected) return false
            writes++
            encrypted = desired
            if (failWriteAfter) throw IOException("encrypted_after")
            return true
        }
        override suspend fun applyPlatform(desired: AppLocale) {
            beforeApply()
            if (failPlatformBefore) throw IOException("platform_before")
            applies++
            platform = desired
            if (failPlatformAfter) throw IOException("platform_after")
        }
        override suspend fun complete(desired: AppLocale, pending: PendingAppLanguage<AppLocale>?): Boolean {
            if (failCompleteBefore) { failCompleteBefore = false; throw IOException("checkpoint_before") }
            if (!known || encrypted != desired || platform != desired || journal.pending != pending) {
                falseCompletions++
                return false
            }
            journal = AppLanguageJournal(completed = desired)
            if (failCompleteAfter) { failCompleteAfter = false; throw IOException("checkpoint_after") }
            return true
        }
        override suspend fun clearPending(pending: PendingAppLanguage<AppLocale>): Boolean {
            if (journal.pending != pending) return false
            journal = journal.copy(pending = null)
            return true
        }
    }

    private fun coordinator(port: FakePort, authoritative: Boolean = true) =
        AppLanguageCoordinator(port, system, authoritative)

    private suspend fun fails(block: suspend () -> Unit) {
        try { block(); fail("Expected storage or platform failure") }
        catch (_: IOException) { }
    }

    @Test fun `failed encrypted update never invents a completed old checkpoint`() = runBlocking {
        val port = FakePort(en, system)
        port.failWriteBefore = true
        fails { coordinator(port).select(ru) }
        assertEquals(en, port.encrypted)
        assertEquals(system, port.platform)
        assertNull(port.journal.completed)
        assertNull(port.journal.pending)
        assertEquals(0, port.applies)
        assertEquals(0, port.falseCompletions)
    }

    @Test fun `failed encrypted update with existing different OS locale does not mark old stored synced`() = runBlocking {
        val port = FakePort(en, fr)
        port.failWriteBefore = true
        fails { coordinator(port).select(ru) }
        assertEquals(en, port.encrypted)
        assertEquals(fr, port.platform)
        assertNull(port.journal.completed)
        assertEquals(0, port.applies)
    }

    @Test fun `pending journal failure before or after commit does not write settings or platform`() = runBlocking {
        for (after in listOf(false, true)) {
            val port = FakePort(en, fr)
            port.failPendingBefore = !after
            port.failPendingAfter = after
            fails { coordinator(port).select(ru) }
            assertEquals(0, port.writes)
            assertEquals(0, port.applies)
            assertNull(port.journal.completed)
        }
    }

    @Test fun `unknown durable outcome retains pending rather than trusting cached prior value`() = runBlocking {
        val port = FakePort(en, system)
        port.beforeWrite = { port.known = false; throw IOException("unknown_write") }
        fails { coordinator(port).select(ru) }
        assertEquals(PendingAppLanguage(en, ru), port.journal.pending)
        assertNull(port.journal.completed)
        assertFalse(coordinator(port).synchronize())
        assertEquals(0, port.applies)
    }

    @Test fun `fresh recovery discards an uncommitted pending choice and preserves initial explicit language`() = runBlocking {
        val port = FakePort(en, system, AppLanguageJournal(pending = PendingAppLanguage(en, ru)))
        assertTrue(coordinator(port).synchronize())
        assertEquals(en, port.platform)
        assertEquals(en, port.journal.completed)
        assertNull(port.journal.pending)
        assertEquals(0, port.writes)
    }

    @Test fun `fresh recovery resumes a committed pending choice without rewriting encrypted settings`() = runBlocking {
        val port = FakePort(ru, en, AppLanguageJournal(pending = PendingAppLanguage(en, ru)))
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.platform)
        assertEquals(ru, port.journal.completed)
        assertNull(port.journal.pending)
        assertEquals(0, port.writes)
    }

    @Test fun `process interruption after platform apply checkpoints actual agreement without replay`() = runBlocking {
        val port = FakePort(ru, ru, AppLanguageJournal(completed = en, pending = PendingAppLanguage(en, ru)))
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.journal.completed)
        assertNull(port.journal.pending)
        assertEquals(0, port.writes)
        assertEquals(0, port.applies)
    }

    @Test fun `checkpoint failure cannot change agreement and cleanup only records the actual target`() = runBlocking {
        for (after in listOf(false, true)) {
            val port = FakePort(en, fr)
            port.failCompleteBefore = !after
            port.failCompleteAfter = after
            fails { coordinator(port).select(ru) }
            assertEquals(ru, port.encrypted)
            assertEquals(ru, port.platform)
            assertEquals(ru, port.journal.completed)
            assertNull(port.journal.pending)
            assertEquals(0, port.falseCompletions)
        }
    }

    @Test fun `third durable value discards stale pending without applying its target`() = runBlocking {
        val port = FakePort(de, fr, AppLanguageJournal(completed = en, pending = PendingAppLanguage(en, ru)))
        assertFalse(coordinator(port).synchronize())
        assertEquals(de, port.encrypted)
        assertEquals(fr, port.platform)
        assertNull(port.journal.pending)
        assertEquals(en, port.journal.completed)
        assertEquals(0, port.applies)
    }

    @Test fun `a failed newer choice restores the prior committed pending app intent`() = runBlocking {
        val oldPending = PendingAppLanguage(en, ru)
        val port = FakePort(ru, en, AppLanguageJournal(completed = en, pending = oldPending))
        port.failWriteBefore = true
        fails { coordinator(port).select(de) }
        assertEquals(PendingAppLanguage(ru, ru), port.journal.pending)
        assertEquals(ru, port.encrypted)
        assertEquals(en, port.platform)
        assertEquals(en, port.journal.completed)
        port.failWriteBefore = false
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.journal.completed)
        assertTrue(coordinator(port).select(de))
        assertEquals(de, port.encrypted)
        assertEquals(de, port.journal.completed)
    }

    @Test fun `process interruption before a newer write recovers the previous committed intent`() = runBlocking {
        val port = FakePort(ru, en, AppLanguageJournal(completed = en,
            pending = PendingAppLanguage(ru, de, resumePrior = ru)))
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.platform)
        assertEquals(ru, port.journal.completed)
        assertNull(port.journal.pending)
        assertEquals(0, port.writes)
    }

    @Test fun `a newer explicit choice may supersede an unresolved committed intent`() = runBlocking {
        val port = FakePort(ru, en, AppLanguageJournal(completed = en, pending = PendingAppLanguage(en, ru)))
        assertTrue(coordinator(port).select(de))
        assertEquals(de, port.encrypted)
        assertEquals(de, port.platform)
        assertEquals(de, port.journal.completed)
        assertNull(port.journal.pending)
    }

    @Test fun `same enum app selection still overrides a different OS locale truthfully`() = runBlocking {
        val port = FakePort(en, fr)
        assertTrue(coordinator(port).select(en))
        assertEquals(en, port.platform)
        assertEquals(en, port.journal.completed)
        assertEquals(0, port.writes)
    }

    @Test fun `failure after encrypted commit retains recoverable target`() = runBlocking {
        val port = FakePort(en, system)
        port.failWriteAfter = true
        fails { coordinator(port).select(ru) }
        assertEquals(ru, port.encrypted)
        assertEquals(PendingAppLanguage(en, ru), port.journal.pending)
        assertNull(port.journal.completed)
        port.failWriteAfter = false
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.journal.completed)
    }

    @Test fun `platform failure retains target and completion happens only after agreement`() = runBlocking {
        val port = FakePort(en, fr)
        port.failPlatformBefore = true
        fails { coordinator(port).select(ru) }
        assertEquals(ru, port.encrypted)
        assertEquals(fr, port.platform)
        assertNull(port.journal.completed)
        assertNotNull(port.journal.pending)
        port.failPlatformBefore = false
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.journal.completed)
        assertEquals(0, port.falseCompletions)
    }

    @Test fun `exception after successful platform apply may checkpoint only the actual agreed target`() = runBlocking {
        val port = FakePort(en, fr)
        port.failPlatformAfter = true
        fails { coordinator(port).select(ru) }
        assertEquals(ru, port.encrypted)
        assertEquals(ru, port.platform)
        assertEquals(ru, port.journal.completed)
        assertNull(port.journal.pending)
    }

    @Test fun `cancellation before encrypted commit remains cancellable and cannot mark completed`() = runBlocking {
        val port = FakePort(en, system)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        port.beforeWrite = { entered.complete(Unit); gate.await() }
        val job = launch { coordinator(port).select(ru) }
        entered.await()
        try { withTimeout(1000) { job.cancelAndJoin() } }
        finally { gate.complete(Unit); job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(en, port.encrypted)
        assertEquals(system, port.platform)
        assertNull(port.journal.completed)
        assertEquals(0, port.applies)
    }

    @Test fun `cancellation after encrypted commit retains pending and performs no platform apply in cleanup`() = runBlocking {
        val port = FakePort(en, fr)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        port.beforeApply = { entered.complete(Unit); gate.await() }
        val job = launch { coordinator(port).select(ru) }
        entered.await()
        try { withTimeout(1000) { job.cancelAndJoin() } }
        finally { gate.complete(Unit); job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(ru, port.encrypted)
        assertEquals(fr, port.platform)
        assertNotNull(port.journal.pending)
        assertNull(port.journal.completed)
        assertEquals(0, port.applies)
        port.beforeApply = {}
        assertTrue(coordinator(port).synchronize())
        assertEquals(ru, port.journal.completed)
    }

    @Test fun `completed journal makes later OS reset to system authoritative`() = runBlocking {
        val port = FakePort(ru, system, AppLanguageJournal(completed = ru))
        assertTrue(coordinator(port).synchronize())
        assertEquals(system, port.encrypted)
        assertEquals(system, port.journal.completed)
        assertEquals(0, port.applies)
    }

    @Test fun `OS mirror interrupted by a newer OS choice never becomes a pending app intent`() = runBlocking {
        val port = FakePort(en, ru, AppLanguageJournal(completed = en))
        port.beforeWrite = { port.platform = de }
        assertFalse(coordinator(port).synchronize())
        assertEquals(ru, port.encrypted)
        assertNull(port.journal.pending)
        assertEquals(en, port.journal.completed)
        port.beforeWrite = {}
        assertTrue(coordinator(port).synchronize())
        assertEquals(de, port.encrypted)
        assertEquals(de, port.journal.completed)
        assertEquals(0, port.applies)
    }

    @Test fun `first migration existing OS choice and pre33 stored authority remain distinct`() = runBlocking {
        val api33 = FakePort(en, fr)
        assertTrue(coordinator(api33).synchronize())
        assertEquals(fr, api33.encrypted)
        val pre33 = FakePort(ru, en)
        assertTrue(coordinator(pre33, authoritative = false).synchronize())
        assertEquals(ru, pre33.platform)
    }
}
