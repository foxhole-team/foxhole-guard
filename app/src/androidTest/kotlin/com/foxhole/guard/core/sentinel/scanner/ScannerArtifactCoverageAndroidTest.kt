package com.foxhole.guard.core.sentinel.scanner

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerArtifactCoverageAndroidTest {
    @Test fun oldReplyHasExplicitlyUnavailableArtifactCoverage() {
        val result = decodeScannerArtifactCoverage(Bundle(), false, claims)
        assertFalse(result.reported)
        assertEquals(2, result.coverage.requestedArtifacts)
        assertTrue(result.artifacts.isEmpty())
    }

    @Test fun fullBaseAndSplitDigestsRemainBoundToTheirOwnedDescriptors() {
        val result = decodeScannerArtifactCoverage(reply(), true, claims)
        assertTrue(result.reported)
        assertEquals(listOf("base", "split-1"), result.artifacts.map { it.sourceId })
        assertEquals(listOf("BASE", "SPLIT"), result.artifacts.map { it.kind })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), result.artifacts.map { it.sha256 })
        assertEquals(2, result.coverage.completedDexEntries)
    }

    @Test fun incompleteArtifactCannotPublishAFullFileDigest() {
        val data = reply(listOf(artifact("base", "BASE", 128, "a").apply { putBoolean("complete", false) }))
        assertTrue(runCatching { decodeScannerArtifactCoverage(data, false, claims) }.isFailure)
    }

    @Test fun changedOrDuplicateSourcesAndWrongFullSizesAreRejected() {
        val invalid = listOf(
            reply(listOf(artifact("unknown", "BASE", 128, "a"))),
            reply(listOf(artifact("base", "BASE", 128, "a"), artifact("base", "BASE", 128, "a"))),
            reply(listOf(artifact("base", "BASE", 127, "a"))),
            reply(listOf(artifact("base", "SPLIT", 128, "a"))),
        )
        invalid.forEach { assertTrue(runCatching { decodeScannerArtifactCoverage(it, false, claims) }.isFailure) }
    }

    @Test fun missingSplitOrCoverageFieldCannotClaimComplete() {
        assertTrue(runCatching { decodeScannerArtifactCoverage(reply(listOf(artifact("base", "BASE", 128, "a"))), true, claims) }.isFailure)
        assertTrue(runCatching { decodeScannerArtifactCoverage(reply().apply { remove("dex_declared") }, false, claims) }.isFailure)
    }

    @Test fun dexEntryLimitRetainsDeclaredCoverageWithoutClaimingItWasCompleted() {
        val data = reply().apply { putInt("dex_declared", 129); putInt("dex_completed", 0) }
        val result = decodeScannerArtifactCoverage(data, false, claims)
        assertEquals(129, result.coverage.declaredDexEntries)
        assertEquals(0, result.coverage.completedDexEntries)
        assertTrue(runCatching { decodeScannerArtifactCoverage(data, true, claims) }.isFailure)
    }

    private val claims = listOf(ScannerDescriptorClaim("base", ScannerArtifactKind.BASE, 128), ScannerDescriptorClaim("split-1", ScannerArtifactKind.SPLIT, 64))
    private fun artifact(source: String, kind: String, bytes: Long, digest: String) = Bundle().apply {
        putString("source", source); putString("kind", kind); putLong("bytes", bytes)
        putBoolean("complete", true); putString("sha256", digest.repeat(64))
        putInt("declared", 1); putInt("verified", 1); putBoolean("validated", true)
    }
    private fun reply(rows: List<Bundle> = listOf(artifact("base", "BASE", 128, "a"), artifact("split-1", "SPLIT", 64, "b"))) = Bundle().apply {
        putParcelableArrayList("artifact_reports", ArrayList(rows))
        putInt("coverage_requested", 2); putInt("coverage_opened", 2)
        putInt("dex_declared", 2); putInt("dex_completed", 2)
        putLong("expanded_bytes", 1024); putLong("work_units", 100)
    }
}
