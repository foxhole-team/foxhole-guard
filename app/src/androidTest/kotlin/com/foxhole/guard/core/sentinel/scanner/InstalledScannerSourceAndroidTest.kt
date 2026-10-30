package com.foxhole.guard.core.sentinel.scanner

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class InstalledScannerSourceAndroidTest {
    @Test fun actualInstalledBaseSplitsAndFullCurrentSignerSetHaveAStableOwner() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = instrumentation.context.packageName
        val provider = InstalledScannerSourceProvider(context)
        val captured = provider.capture(packageName)
        assumeTrue("modern signer metadata requires API 28", Build.VERSION.SDK_INT >= 28)
        @Suppress("DEPRECATION")
        val actual = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val expected = requireNotNull(actual.signingInfo).apkContentsSigners.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
        val app = requireNotNull(actual.applicationInfo)
        assertEquals(app.uid, captured.uid)
        assertEquals(actual.longVersionCode, captured.versionCode)
        assertEquals(expected, captured.currentSignerSha256)
        assertEquals(File(app.sourceDir).canonicalPath, captured.artifacts.first().path)
        assertEquals(app.splitSourceDirs?.size ?: 0, captured.artifacts.size - 1)
        assertTrue(captured.artifacts.all { it.inode > 0 && it.bytes > 0 })
        assertTrue(provider.isCurrent(captured))
        assertFalse(provider.isCurrent(captured.copy(lastUpdateTime = captured.lastUpdateTime + 1)))
        assertFalse(provider.isCurrent(captured.copy(currentSignerSha256 = setOf("0".repeat(64)))))
    }

    @Test fun oversizedInstalledApkIsRejectedWithoutACompleteResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = requireNotNull(context.applicationInfo.sourceDir)
        assumeTrue("owned app is above the per-artifact budget", File(path).length() > 67108864L)
        var failure: IllegalArgumentException? = null
        try {
            InstalledScannerSourceProvider(context).capture(context.packageName)
        } catch (expected: IllegalArgumentException) {
            failure = expected
        }
        assertTrue(failure != null)
    }
}
