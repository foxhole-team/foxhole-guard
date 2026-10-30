package com.foxhole.guard.core.sentinel.scanner

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReason
import com.foxhole.guard.sentinel.nativeengine.NativeYaraStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class IsolatedYaraAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun realNativeDexRulePositiveAndNegativeStayInsideTheChild() = runBlocking {
        val directory = File(context.cacheDir, "owned-yara-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val apk = File(directory, "owned.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                instrumentation.context.assets.open("owned-scanner/classes.dex").use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val rules = File(directory, "owned.yar").apply {
                writeText(
                    "import \"dex\"\nrule owned_verified_dex { condition: dex.is_dex }\nrule owned_absent_marker { strings: $" + "marker = \"FOXHOLE_OWNED_ABSENT_MARKER_20261003\" condition: $" + "marker }\n"
                )
            }
            val bytes = rules.readBytes()
            val hash = MessageDigest.getInstance(
                "SHA-256"
            ).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val owner = ScannerRequestOwner(UUID.randomUUID().toString(), "owned-fixture:1", hash)
            val input = ScannerInputFile(ScannerDescriptorClaim("base", ScannerArtifactKind.BASE, apk.length()), apk)
            val result = IsolatedScannerClient(
                context
            ).inspect(
                listOf(input),
                owner,
                yaraSource = ScannerYaraSource(rules, hash, bytes.size),
                currentOwner = { owner }
            )
            assertEquals(InspectionStatus.COMPLETE, result.status)
            val yara = requireNotNull(result.yara)
            assertTrue(yara.verifiedDexComplete)
            assertEquals(1, yara.scannedDexEntries)
            assertEquals(setOf(NativeYaraStatus.COMPLETE), yara.statuses)
            assertEquals(setOf(NativeYaraReason.SCANNED), yara.reasons)
            assertTrue("owned_verified_dex" in yara.matchedRuleIds)
            assertFalse("owned_absent_marker" in yara.matchedRuleIds)
            assertTrue(isStandardIsolatedScannerUid(result.processUid))
            assertNotEquals(Process.myPid(), result.processId)
        } finally { directory.deleteRecursively() }
    }
}
