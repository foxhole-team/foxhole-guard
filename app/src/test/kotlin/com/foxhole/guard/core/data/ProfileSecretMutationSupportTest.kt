package com.foxhole.guard.core.data

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
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

    private fun sampleSecret(rawInput: String): StoredProfileSecret =
        StoredProfileSecret(rawInput = rawInput)

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
