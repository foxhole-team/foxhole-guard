package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveActivatingDnsRuleSetStoreTest {
    @Test
    fun `persists before installing the exact bytes into the live core`() =
        runBlocking {
            val calls = mutableListOf<String>()
            var outcome: RuntimeDnsRuleSetInstallOutcome? = null
            val ruleSet = testRuleSet()
            val delegate =
                object : DnsFilterRuleSetStore {
                    override suspend fun installVerifiedDnsRuleSet(ruleSet: VerifiedDnsRuleSet): String {
                        calls += "persist"
                        return "/private/rules.fhds"
                    }
                }
            val store =
                LiveActivatingDnsRuleSetStore(
                    delegate = delegate,
                    installer = { name, manifest, signature, artifact ->
                        calls += "activate"
                        assertEquals(ruleSet.manifest.name, name)
                        assertSame(ruleSet.manifestBytes, manifest)
                        assertSame(ruleSet.signatureBytes, signature)
                        assertSame(ruleSet.artifactBytes, artifact)
                        RuntimeDnsRuleSetInstallOutcome.Installed(7L)
                    },
                    onOutcome = { value -> outcome = value },
                )

            assertEquals("/private/rules.fhds", store.installVerifiedDnsRuleSet(ruleSet))
            assertEquals(listOf("persist", "activate"), calls)
            assertEquals(RuntimeDnsRuleSetInstallOutcome.Installed(7L), outcome)
        }

    @Test
    fun `keeps the persisted update when live activation is rejected`() =
        runBlocking {
            var outcome: RuntimeDnsRuleSetInstallOutcome? = null
            val delegate =
                object : DnsFilterRuleSetStore {
                    override suspend fun installVerifiedDnsRuleSet(
                        ruleSet: VerifiedDnsRuleSet,
                    ): String = "/private/rules.fhds"
                }
            val store =
                LiveActivatingDnsRuleSetStore(
                    delegate = delegate,
                    installer = { _, _, _, _ -> error("native refusal") },
                    onOutcome = { value -> outcome = value },
                )

            assertEquals("/private/rules.fhds", store.installVerifiedDnsRuleSet(testRuleSet()))
            assertEquals(RuntimeDnsRuleSetInstallOutcome.Rejected, outcome)
        }
}

private fun testRuleSet(): VerifiedDnsRuleSet =
    VerifiedDnsRuleSet(
        manifest = DnsFilterManifest(
            schema = 2,
            name = "foxhole-adguard-dns-filter",
            format = "foxhole-dns-fst-v1",
            sequence = 7L,
            generatedAtUnix = 10L,
            expiresAtUnix = 20L,
            keySha256 = "key",
            source = DnsFilterManifestSource(
                name = "source",
                repo = "repo",
                commit = "commit",
                license = "license",
                inputPath = "input",
                inputSha256 = "input-sha",
            ),
            artifact = DnsFilterManifestArtifact(
                file = "adguard-dns-filter.fhds",
                size = 3L,
                sha256 = "artifact-sha",
                blockEntries = 1L,
                allowEntries = 0L,
            ),
            compatibility = DnsFilterManifestCompatibility(coreSchema = 1),
        ),
        manifestBytes = byteArrayOf(1),
        signatureBytes = byteArrayOf(2),
        artifactBytes = byteArrayOf(3),
    )
