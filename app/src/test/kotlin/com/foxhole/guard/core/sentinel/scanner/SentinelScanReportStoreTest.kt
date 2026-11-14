package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.core.sentinel.FileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SentinelScanReportStoreTest {
    @Test
    fun `completed scan with no findings has a durable report`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val file = File(directory, "reports")
            val cipher = TestCipher()
            SentinelScanReportStore(file, cipher) { 10 }.save(complete())
            val restored = SentinelScanReportStore(file, cipher) { 10 }
            restored.restore("new-process")
            assertEquals(ScanReportStatus.COMPLETE, restored.history.value.reports.single().status)
            assertTrue(restored.history.value.reports.single().observations.isEmpty())
            assertTrue(restored.history.value.reports.single().ruleIds.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `process death settles running report rather than keeping checking forever`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val file = File(directory, "reports")
            val cipher = TestCipher()
            val store = SentinelScanReportStore(file, cipher) { 20 }
            store.save(complete().copy(status = ScanReportStatus.RUNNING, finishedAtMs = null))
            SentinelScanReportStore(file, cipher) { 30 }.also {
                it.restore("next-process")
                assertEquals(ScanReportStatus.INTERRUPTED, it.history.value.reports.single().status)
                assertTrue("PROCESS_INTERRUPTED" in it.history.value.reports.single().issues)
                assertEquals(30L, it.history.value.reports.single().finishedAtMs)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `partial digest cannot enter durable report as a complete artifact hash`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val file = File(directory, "reports")
            val store = SentinelScanReportStore(file, TestCipher()) { 20 }
            val invalid = complete().copy(artifacts = listOf(complete().artifacts.single().copy(inputComplete = false)))
            assertTrue(runCatching { store.save(invalid) }.isFailure)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `restoration expires old reports without requiring a new scan`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val file = File(directory, "reports")
            val cipher = TestCipher()
            SentinelScanReportStore(file, cipher) { 10 }.save(complete())
            val restored = SentinelScanReportStore(file, cipher) { 31L * 24 * 60 * 60 * 1000 }
            restored.restore("next-process")
            assertTrue(restored.history.value.loaded)
            assertTrue(restored.history.value.reports.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `history has a bounded count and clear removes only its private document`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val file = File(directory, "reports")
            val unrelated = File(directory, "settings").apply { writeText("keep") }
            val store = SentinelScanReportStore(file, TestCipher()) { 1000 }
            repeat(105) { index -> store.save(complete().copy(id = "report-$index", startedAtMs = index.toLong(), finishedAtMs = index + 1L)) }
            assertEquals(100, store.history.value.reports.size)
            assertEquals("report-104", store.history.value.reports.first().id)
            store.clear()
            assertFalse(file.exists())
            assertEquals("keep", unrelated.readText())
            assertTrue(store.history.value.reports.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `malformed or oversized history never becomes a clean empty result`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val file = File(directory, "reports")
            file.writeText("{broken")
            var store = SentinelScanReportStore(file, TestCipher()) { 10 }
            assertTrue(runCatching { store.restore("process") }.isFailure)
            assertFalse(store.history.value.loaded)
            file.writeBytes(ByteArray(2 * 1024 * 1024 + 1025))
            store = SentinelScanReportStore(file, TestCipher()) { 10 }
            assertTrue(runCatching { store.restore("process") }.isFailure)
            assertFalse(store.history.value.loaded)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `dex limit result preserves declared count beyond the analysis limit`() {
        val directory = Files.createTempDirectory("scan-report-test").toFile()
        try {
            val store = SentinelScanReportStore(File(directory, "reports"), TestCipher()) { 10 }
            store.save(complete().copy(status = ScanReportStatus.PARTIAL, issues = setOf("DEX_ENTRY_LIMIT"), coverage = ScanCoverageReport(1, 1, 129, 0)))
            assertEquals(129, store.history.value.reports.single().coverage.declaredDexEntries)
            assertEquals(ScanReportStatus.PARTIAL, store.history.value.reports.single().status)
        } finally { directory.deleteRecursively() }
    }

    private fun complete() = SentinelScanReport(
        id = "report-1", processOwner = "old-process", packageName = "org.example.app", installationId = "installation-1",
        startedAtMs = 1, finishedAtMs = 2, requestType = "PACKAGE", status = ScanReportStatus.COMPLETE,
        analyzerVersion = "bounded-apk-dex-2", appVersion = "test", ruleEpoch = "dex-only",
        artifacts = listOf(ScanArtifactReport("base", "BASE", 128, true, "a".repeat(64), 1, 1, true)),
        coverage = ScanCoverageReport(1, 1, 1, 1),
    )

    private class TestCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes()
        override fun writeBytesAtomic(file: File, plaintext: ByteArray) { file.writeBytes(plaintext) }
    }
}
