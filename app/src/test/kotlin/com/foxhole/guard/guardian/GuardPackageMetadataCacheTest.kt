package com.foxhole.guard.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GuardPackageMetadataCacheTest {
    @Test
    fun `unchanged complete installation reuses signer facts without another heavy query`() {
        val subject = GuardPackageMetadataCache()
        var queries = 0
        val facts = metadata()
        val first = subject.resolve(facts.installation, facts.provenance) {
            queries++
            facts
        }
        val second = subject.resolve(facts.installation, facts.provenance) { error("unchanged signer must be cached") }
        assertSame(first, second)
        assertEquals(1, queries)
    }

    @Test
    fun `version uid install update provenance and broadcast invalidate cached facts`() {
        val original = metadata()
        val identities = listOf(
            original.installation.copy(versionCode = 2),
            original.installation.copy(uid = 42),
            original.installation.copy(firstInstallTime = 2),
            original.installation.copy(lastUpdateTime = 3)
        )
        identities.forEach { changed ->
            val subject = GuardPackageMetadataCache()
            subject.resolve(original.installation, original.provenance) { original }
            var queries = 0
            val expected = original.copy(installation = changed)
            assertEquals(
                expected,
                subject.resolve(changed, original.provenance) {
                    queries++
                    expected
                }
            )
            assertEquals(1, queries)
        }
        val subject = GuardPackageMetadataCache()
        subject.resolve(original.installation, original.provenance) { original }
        subject.invalidate(original.installation.packageName)
        var queries = 0
        subject.resolve(original.installation, original.provenance) {
            queries++
            original
        }
        assertEquals(1, queries)
        val newProvenance = original.copy(provenance = "owned-api33")
        subject.resolve(original.installation, newProvenance.provenance) {
            queries++
            newProvenance
        }
        assertEquals(2, queries)
    }

    @Test
    fun `missing signer and incomplete PM identity are never cached as completed clean facts`() {
        val subject = GuardPackageMetadataCache()
        val complete = metadata()
        assertTrue(
            runCatching {
                subject.resolve(
                    complete.installation.copy(uid = null),
                    complete.provenance
                ) { complete }
            }.isFailure
        )
        assertTrue(
            runCatching {
                subject.resolve(
                    complete.installation,
                    complete.provenance
                ) { complete.copy(signerSha256 = "") }
            }.isFailure
        )
        var queried = false
        subject.resolve(complete.installation, complete.provenance) {
            queried = true
            complete
        }
        assertTrue(queried)
    }

    @Test
    fun `epoch invalidation during signer query rejects stale publication`() {
        val subject = GuardPackageMetadataCache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val facts = metadata()
        val thread = Thread {
            try {
                subject.resolve(facts.installation, facts.provenance) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    facts
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            thread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            subject.invalidateEpoch()
            release.countDown()
            thread.join(5000)
            assertTrue(!thread.isAlive)
            assertTrue(failure.get() is GuardPackageMetadataUnavailableException)
            var query = false
            subject.resolve(facts.installation, facts.provenance) {
                query = true
                facts
            }
            assertTrue(query)
        } finally {
            release.countDown()
            thread.join(5000)
        }
    }

    @Test
    fun `capacity evicts cache only and forces a new query rather than returning unknown facts`() {
        val subject = GuardPackageMetadataCache(1)
        val first = metadata()
        val second = first.copy(installation = first.installation.copy(packageName = "owned.second"))
        subject.resolve(first.installation, first.provenance) { first }
        subject.resolve(second.installation, second.provenance) { second }
        var query = false
        subject.resolve(first.installation, first.provenance) {
            query = true
            first
        }
        assertTrue(query)
    }

    private fun metadata() = GuardPackageMetadata(
        GuardPackageInstallation("owned.package", 41, 1L, 1L, 2L),
        "a".repeat(64),
        "owned-api28-current-signers",
    )
}
