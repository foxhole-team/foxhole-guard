package com.foxhole.guard.core.data

import com.foxhole.core.model.StoredProfileSecret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class ProfileMutationSettlementTest {
    @Test
    fun `retired secret cancellation preserves committed replacement and original cancellation`() = runBlocking {
        assertRetiredFailure(CancellationException("owned retired deletion cancellation"))
    }

    @Test
    fun `retired secret fatal error preserves committed replacement and original error`() = runBlocking {
        assertRetiredFailure(AssertionError("owned retired deletion fatal"))
    }

    @Test
    fun `precommit failure remains primary when rollback deletion cancels and remaining cleanup proceeds`() = runBlocking {
        val primary = IllegalStateException("owned database failure")
        val secondary = CancellationException("owned rollback cleanup cancellation")
        val store = SettlementSecretStore("new-b", secondary)
        val failure = runCatching {
            executeSecretFirstMutation(
                store,
                listOf(StagedProfileSecretWrite("new-a", secret), StagedProfileSecretWrite("new-b", secret)),
            ) { throw primary }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(primary.suppressed.any { it === secondary })
        assertFalse(store.values.containsKey("new-a"))
        assertTrue(store.values.containsKey("new-b"))
        assertEquals(listOf("new-b", "new-a"), store.deletions)
    }

    @Test
    fun `ordinary retired deletion failure stays warning and committed replacement remains`() = runBlocking {
        val ordinary = IllegalStateException("owned temporary cleanup failure")
        val store = SettlementSecretStore("old", ordinary)
        val warnings = mutableListOf<Throwable>()
        val result = executeSecretFirstMutation(
            store,
            listOf(StagedProfileSecretWrite("new", secret)),
            listOf("old"),
            onCleanupFailure = { _, error -> warnings += error },
        ) { "committed" }
        assertEquals("committed", result)
        assertEquals(listOf(ordinary), warnings)
        assertTrue(store.values.containsKey("new"))
    }

    @Test
    fun `reset cancellation after durable settings commit still clears owned rows and secrets`() = runBlocking {
        val directory = Files.createTempDirectory("owned-reset-settlement").toFile()
        val settings = directory.resolve("settings").apply { writeText("old") }
        val rows = directory.resolve("rows").apply { writeText("owned profile") }
        val secrets = directory.resolve("secrets").apply { writeText("owned secret") }
        val parent = SupervisorJob()
        val failure = AtomicReference<Throwable>()
        val cancelled = CancellationException("owned after settings commit")
        try {
            val scope = CoroutineScope(Dispatchers.Default + parent)
            scope.launch {
                val caller = coroutineContext[Job]!!
                try {
                    executeSettledLocalReset(
                        commitSettings = {
                            settings.writeText("reset")
                            caller.cancel(cancelled)
                        },
                        clearOwnedData = {
                            check(rows.delete())
                            check(secrets.delete())
                            "cleared"
                        },
                    )
                    failure.set(AssertionError("cancelled reset returned successful acknowledgement"))
                } catch (error: Throwable) { failure.set(error) }
            }.join()
            assertTrue(failure.get() is CancellationException)
            assertEquals("reset", settings.readText())
            assertFalse(rows.exists())
            assertFalse(secrets.exists())
        } finally {
            parent.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reset fatal cleanup failure is preserved and never reported as successful acknowledgement`() = runBlocking {
        val primary = AssertionError("owned destructive storage error")
        var durableSettings = false
        val failure = runCatching {
            executeSettledLocalReset(
                commitSettings = { durableSettings = true },
                clearOwnedData = { throw primary },
            )
        }.exceptionOrNull()
        assertTrue(durableSettings)
        assertSame(primary, failure)
    }

    private suspend fun assertRetiredFailure(primary: Throwable) {
        val store = SettlementSecretStore("old", primary)
        val failure = runCatching {
            executeSecretFirstMutation(
                store,
                listOf(StagedProfileSecretWrite("new", secret)),
                listOf("old", "later"),
            ) { "committed" }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(store.values.containsKey("new"))
        assertFalse(store.deletions.contains("new"))
        assertEquals(listOf("old", "later"), store.deletions)
    }

    private class SettlementSecretStore(private val failingRef: String, private val failure: Throwable) : ProfileSecretStore {
        val values = mutableMapOf<String, StoredProfileSecret>()
        val deletions = mutableListOf<String>()
        override suspend fun read(secretRef: String): StoredProfileSecret? = values[secretRef]
        override suspend fun write(secretRef: String, value: StoredProfileSecret) { values[secretRef] = value }
        override suspend fun delete(secretRef: String): Boolean {
            deletions += secretRef
            if (secretRef == failingRef) throw failure
            return values.remove(secretRef) != null
        }
    }

    private companion object { val secret = StoredProfileSecret(resolvedConfigJson = "owned synthetic config") }
}
