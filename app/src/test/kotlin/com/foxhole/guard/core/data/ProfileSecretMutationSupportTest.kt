package com.foxhole.guard.core.data

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSecretMutationSupportTest {
    @Test
    fun `profile editor validates every edit before either store is mutated`() {
        val writes = mutableListOf<StoredProfileSecret>()
        var metadataUpdates = 0

        runBlocking {
            try {
                persistValidatedProfileEditorChanges(
                    originalSecret = sampleSecret("original"),
                    edits =
                    listOf(
                        ProfileEditorConfigUpdate(null, validConfig("first")),
                        ProfileEditorConfigUpdate(null, "invalid"),
                    ),
                    json = Json,
                    sanitizer = { raw -> if (raw == "invalid") error("invalid config") else raw },
                    writeSecret = writes::add,
                    updateMetadata = { metadataUpdates += 1 },
                    onRollbackFailure = {},
                )
            } catch (_: IllegalStateException) {
            }
        }

        assertTrue(writes.isEmpty())
        assertEquals(0, metadataUpdates)
    }

    @Test
    fun `profile editor writes all validated configs once`() {
        val writes = mutableListOf<StoredProfileSecret>()
        var metadataUpdates = 0

        val updated =
            runBlocking {
                persistValidatedProfileEditorChanges(
                    originalSecret = sampleSecret("original"),
                    edits =
                    listOf(
                        ProfileEditorConfigUpdate(null, validConfig("first")),
                        ProfileEditorConfigUpdate(null, validConfig("second")),
                    ),
                    json = Json,
                    sanitizer = { raw -> raw },
                    writeSecret = writes::add,
                    updateMetadata = { metadataUpdates += 1 },
                    onRollbackFailure = {},
                )
            }

        assertEquals(1, writes.size)
        assertEquals(updated, writes.single())
        assertEquals(validConfig("second"), updated.resolvedConfigJson)
        assertEquals(1, metadataUpdates)
    }

    @Test
    fun `profile editor restores original secret when metadata commit fails`() {
        val original = sampleSecret("original")
        val writes = mutableListOf<StoredProfileSecret>()

        runBlocking {
            try {
                persistValidatedProfileEditorChanges(
                    originalSecret = original,
                    edits = listOf(ProfileEditorConfigUpdate(null, validConfig("edited"))),
                    json = Json,
                    sanitizer = { raw -> raw },
                    writeSecret = writes::add,
                    updateMetadata = { error("database failed") },
                    onRollbackFailure = {},
                )
            } catch (_: IllegalStateException) {
            }
        }

        assertEquals(2, writes.size)
        assertEquals(validConfig("edited"), writes.first().resolvedConfigJson)
        assertEquals(original, writes.last())
    }

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

    @Test
    fun `updates only requested smart protocol config when editing non-selected option`() {
        val secret =
            StoredProfileSecret(
                resolvedConfigJson = "vless-config",
                selectedProtocolOptionId = "vless",
                protocolOptions =
                listOf(
                    storedOption("vless", ProtocolHint.VLESS, "vless-config"),
                    storedOption("trojan", ProtocolHint.TROJAN, "trojan-config"),
                ),
            )

        val updated =
            secret.withUpdatedResolvedConfigJson(
                sanitized = "edited-trojan-config",
                protocolOptionIdOverride = "trojan",
            )

        assertEquals("vless-config", updated.resolvedConfigJson)
        assertEquals("vless-config", updated.protocolOptions.first { it.id == "vless" }.normalizedConfigJson)
        assertEquals("edited-trojan-config", updated.protocolOptions.first { it.id == "trojan" }.normalizedConfigJson)
    }

    @Test
    fun `mirrors edited smart protocol config to top level when editing selected option`() {
        val secret =
            StoredProfileSecret(
                resolvedConfigJson = "vless-config",
                selectedProtocolOptionId = "vless",
                protocolOptions =
                listOf(
                    storedOption("vless", ProtocolHint.VLESS, "vless-config"),
                    storedOption("trojan", ProtocolHint.TROJAN, "trojan-config"),
                ),
            )

        val updated =
            secret.withUpdatedResolvedConfigJson(
                sanitized = "edited-vless-config",
                protocolOptionIdOverride = "vless",
            )

        assertEquals("edited-vless-config", updated.resolvedConfigJson)
        assertEquals("edited-vless-config", updated.protocolOptions.first { it.id == "vless" }.normalizedConfigJson)
        assertEquals("trojan-config", updated.protocolOptions.first { it.id == "trojan" }.normalizedConfigJson)
    }

    @Test
    fun `missing explicit target rejects all edits before either store changes`() = runBlocking {
        val original = StoredProfileSecret(
            resolvedConfigJson = validConfig("original"),
            selectedProtocolOptionId = "a",
            protocolOptions = listOf(storedOption("a", ProtocolHint.VLESS, validConfig("original"))),
        )
        val writes = mutableListOf<StoredProfileSecret>()
        var metadata = false
        val result = runCatching {
            persistValidatedProfileEditorChanges(
                original,
                listOf(
                    ProfileEditorConfigUpdate("a", validConfig("first")),
                    ProfileEditorConfigUpdate("deleted", validConfig("second")),
                ),
                Json,
                sanitizer = { it },
                writeSecret = writes::add,
                updateMetadata = { metadata = true },
                onRollbackFailure = {},
            )
        }
        assertTrue(result.isFailure)
        assertTrue(writes.isEmpty())
        assertFalse(metadata)
    }

    @Test
    fun `revision detects subscription changes and profile rename`() {
        val original = sampleSecret("original")
        val revision = profileEditorRevision(original, "name")
        requireProfileEditorRevision(original, "name", revision)
        assertTrue(
            runCatching {
                requireProfileEditorRevision(original.copy(rawInput = "refreshed"), "name", revision)
            }.isFailure,
        )
        assertTrue(runCatching { requireProfileEditorRevision(original, "renamed", revision) }.isFailure)
    }

    @Test
    fun `cancellation after the secret write cannot skip its metadata commit`() = runBlocking {
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var metadata = false
        val pending = async(Dispatchers.Default) {
            persistValidatedProfileEditorChanges(
                sampleSecret("original"),
                listOf(ProfileEditorConfigUpdate(null, validConfig("updated"))),
                Json,
                sanitizer = { it },
                writeSecret = {
                    written.complete(Unit)
                    release.await()
                },
                updateMetadata = { metadata = true },
                onRollbackFailure = {},
            )
        }
        written.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(metadata)
    }

    @Test
    fun `a write which changes storage then fails restores the original secret`() = runBlocking {
        val original = sampleSecret("original")
        var stored = original
        var writes = 0
        var metadata = false
        val result = runCatching {
            persistValidatedProfileEditorChanges(
                original,
                listOf(ProfileEditorConfigUpdate(null, validConfig("updated"))),
                Json,
                sanitizer = { it },
                writeSecret = { value ->
                    stored = value
                    writes++
                    if (writes == 1) error("write completion failed")
                },
                updateMetadata = { metadata = true },
                onRollbackFailure = {},
            )
        }
        assertTrue(result.isFailure)
        assertEquals(original, stored)
        assertEquals(2, writes)
        assertFalse(metadata)
    }

    private fun sampleSecret(rawInput: String): StoredProfileSecret =
        StoredProfileSecret(rawInput = rawInput)

    private fun validConfig(tag: String): String =
        """{"outbounds":[{"type":"direct","tag":"$tag"}]}"""

    private fun storedOption(
        id: String,
        protocolHint: ProtocolHint,
        normalizedConfigJson: String,
    ): StoredProfileProtocolOption =
        StoredProfileProtocolOption(
            id = id,
            displayName = protocolHint.name,
            protocolHint = protocolHint,
            normalizedConfigJson = normalizedConfigJson,
        )

    private class FakeProfileSecretStore(
        private val failWriteRef: String? = null,
        private val failDeleteRef: String? = null,
    ) : ProfileSecretStore {
        val values = linkedMapOf<String, StoredProfileSecret>()
        val events = mutableListOf<String>()

        override suspend fun write(secretRef: String, value: StoredProfileSecret) {
            events += "write:$secretRef"
            if (secretRef == failWriteRef) {
                error("write failed for $secretRef")
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
