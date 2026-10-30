package com.foxhole.guard.core.sentinel.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class VerifiedDexCallbackTest {
    private fun input(bytes: ByteArray) = ApkScanInput("base", ApkSourceKind.BASE) { ByteArrayInputStream(bytes) }

    @Test fun eachVerifiedClassicDexIsOfferedOnceAndDoesNotAlterItsFingerprint() {
        val dex = OwnedInspectionFixtures.dex()
        val apk = OwnedInspectionFixtures.zip("classes.dex" to dex, "classes2.dex" to dex)
        val baseline = BoundedApkInspector().inspect(listOf(input(apk)))
        var called = 0
        val result = BoundedApkInspector().inspect(
            listOf(input(apk)),
            onVerifiedDex = { bytes ->
                called++
                bytes.fill(0)
            }
        )
        assertEquals(2, called)
        assertEquals(baseline.sourceFingerprint, result.sourceFingerprint)
        assertEquals(baseline.dexEntries.map { it.sha256 }, result.dexEntries.map { it.sha256 })
        assertTrue(result.coverage.complete)
    }

    @Test fun unsupportedOrMalformedDexNeverReachesOptionalEngine() {
        var called = false
        val apk = OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex("041"))
        val result = BoundedApkInspector().inspect(listOf(input(apk)), onVerifiedDex = { called = true })
        assertFalse(called)
        assertFalse(result.cacheEligible)
    }

    @Test fun optionalEngineCancellationCannotPublishCompleteStaticCoverage() {
        val apk = OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex())
        val result = BoundedApkInspector().inspect(
            listOf(input(apk)),
            onVerifiedDex = { throw CancellationException("owned callback") }
        )
        assertFalse(result.cacheEligible)
        assertTrue(result.issues.any { it.reason == InspectionReason.CANCELLED })
    }

    @Test fun optionalEngineFatalFailureIsTransparent() {
        val apk = OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex())
        val failure = Error("owned fatal callback")
        assertSame(
            failure,
            runCatching {
                BoundedApkInspector().inspect(
                    listOf(input(apk)),
                    onVerifiedDex = { throw failure }
                )
            }.exceptionOrNull()
        )
    }
}
