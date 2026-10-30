package com.foxhole.guard.core.sentinel.scanner

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.core.sentinel.apk.StaticCoverage
import com.foxhole.guard.core.sentinel.apk.StaticLimitation
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedScannerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(): File {
        val directory = File(context.cacheDir, "owned-scanner-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "owned.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            instrumentation.context.assets.open("owned-scanner/classes.dex").use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return file
    }

    private fun owner() = ScannerRequestOwner(UUID.randomUUID().toString(), "owned-fixture:1", "dex:1")

    @Test fun realBoundChildScansReadonlyDescriptorsAndLeavesNoParentFd() = runBlocking {
        val file = fixture()
        try {
            val owner = owner()
            val input = ScannerInputFile(ScannerDescriptorClaim("base", ScannerArtifactKind.BASE, file.length()), file)
            val result = IsolatedScannerClient(context).inspect(listOf(input), owner) { owner }
            assertEquals(InspectionStatus.COMPLETE, result.status)
            assertTrue(result.coverageComplete)
            assertEquals(StaticCoverage.entries.toSet(), result.coverage)
            assertTrue(StaticLimitation.APK_SIGNER_NOT_VERIFIED in result.limitations)
            assertTrue(StaticLimitation.NO_YARA_ENGINE in result.limitations)
            assertTrue(isStandardIsolatedScannerUid(result.processUid))
            assertNotEquals(Process.myPid(), result.processId)
            assertEquals(0, descriptorsFor(file))
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun claimedSizeMismatchCannotProduceCompleteCoverage() = runBlocking {
        val file = fixture()
        try {
            val owner = owner()
            val input = ScannerInputFile(ScannerDescriptorClaim("base", ScannerArtifactKind.BASE, file.length() + 1), file)
            val result = IsolatedScannerClient(context).inspect(listOf(input), owner) { owner }
            assertEquals(InspectionStatus.UNAVAILABLE, result.status)
            assertFalse(result.coverageComplete)
            assertEquals(0, descriptorsFor(file))
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun replacedInstallationOwnerCannotPublishChildResult() = runBlocking {
        val file = fixture()
        try {
            val owner = owner()
            val replacement = owner.copy(installationId = "owned-fixture:2")
            val input = ScannerInputFile(ScannerDescriptorClaim("base", ScannerArtifactKind.BASE, file.length()), file)
            assertThrows(IOException::class.java) {
                runBlocking { IsolatedScannerClient(context).inspect(listOf(input), owner) { replacement } }
            }
            assertEquals(0, descriptorsFor(file))
        } finally { file.parentFile?.deleteRecursively() }
    }

    private fun descriptorsFor(file: File): Int = File("/proc/self/fd").listFiles().orEmpty().count { fd ->
        try { Os.readlink(fd.absolutePath) == file.absolutePath } catch (_: android.system.ErrnoException) { false }
    }
}
