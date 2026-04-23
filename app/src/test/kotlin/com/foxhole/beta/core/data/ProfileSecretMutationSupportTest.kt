package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.StoredProfileSecret
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSecretMutationSupportTest {
    @Test
    fun `rolls back staged secrets when mutation fails`() {
        val store = FakeProfileSecretStore()
        val cleanupFailures = mutableListOf<String>()

        runBlocking {
            try {
                executeSecretFirstMutation(
                    secretStore = store,
                    stagedWrites =
                        listOf(
                            StagedProfileSecretWrite("new-a", sampleSecret("a")),
                            StagedProfileSecretWrite("new-b", sampleSecret("b")),
                        ),
                    onCleanupFailure = { secretRef, _ -> cleanupFailures += secretRef },
                ) {
                    error("db failed")
                }
            } catch (_: IllegalStateException) {
                // expected
            }
        }

        assertTrue(store.values.isEmpty())
        assertEquals(
            listOf("write:new-a", "write:new-b", "delete:new-b", "delete:new-a"),
            store.events,
        )
        assertTrue(cleanupFailures.isEmpty())
    }

    @Test
    fun `stops before mutation when staged write fails and cleans previous writes`() {
        val store = FakeProfileSecretStore(failWriteRef = "new-b")

        runBlocking {
            try {
                executeSecretFirstMutation(
                    secretStore = store,
                    stagedWrites =
                        listOf(
                            StagedProfileSecretWrite("new-a", sampleSecret("a")),
                            StagedProfileSecretWrite("new-b", sampleSecret("b")),
                        ),
                ) {
                    error("mutation should not run")
                }
            } catch (_: IllegalStateException) {
                // expected
            }
        }

        assertFalse(store.values.containsKey("new-a"))
        assertEquals(
            listOf("write:new-a", "write:new-b", "delete:new-a"),
            store.events,
        )
    }

    @Test
    fun `cleans replaced secrets after successful commit and only logs cleanup failures`() {
        val store = FakeProfileSecretStore(failDeleteRef = "old-b")
        store.values["old-a"] = sampleSecret("old-a")
        store.values["old-b"] = sampleSecret("old-b")
        val cleanupFailures = mutableListOf<String>()

        val result =
            runBlocking {
                executeSecretFirstMutation(
                    secretStore = store,
                    stagedWrites = listOf(StagedProfileSecretWrite("new-a", sampleSecret("new-a"))),
                    cleanupSecretRefsAfterSuccess = listOf("old-a", "old-b"),
                    onCleanupFailure = { secretRef, _ -> cleanupFailures += secretRef },
                ) {
                    "committed"
                }
            }

        assertEquals("committed", result)
        assertTrue(store.values.containsKey("new-a"))
        assertFalse(store.values.containsKey("old-a"))
        assertTrue(store.values.containsKey("old-b"))
        assertEquals(listOf("old-b"), cleanupFailures)
    }

    private fun sampleSecret(rawInput: String): StoredProfileSecret =
        StoredProfileSecret(rawInput = rawInput)

    private class FakeProfileSecretStore(
        private val failWriteRef: String? = null,
        private val failDeleteRef: String? = null,
    ) : ProfileSecretStore {
        val values = linkedMapOf<String, StoredProfileSecret>()
        val events = mutableListOf<String>()

        override suspend fun write(secretRef: String, value: StoredProfileSecret) {
            events += "write:$secretRef"
            if (secretRef == failWriteRef) {
                throw IllegalStateException("write failed for $secretRef")
            }
            values[secretRef] = value
        }

        override suspend fun read(secretRef: String): StoredProfileSecret? = values[secretRef]

        override suspend fun delete(secretRef: String): Boolean {
            events += "delete:$secretRef"
            if (secretRef == failDeleteRef) {
                return false
            }
            return values.remove(secretRef) != null
        }
    }
}
