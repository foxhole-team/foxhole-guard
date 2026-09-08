package com.foxhole.guard

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.foxhole.core.model.WebAppRoute
import com.foxhole.guard.core.security.AtomicFileWrites
import com.foxhole.guard.core.webapps.WebAppsDataCleaner
import com.foxhole.guard.core.webapps.WebAppPendingDeletions
import com.foxhole.guard.core.webapps.WebAppProxyActivation
import com.foxhole.guard.core.webapps.WebAppProxyLease
import com.foxhole.guard.core.webapps.publishWebAppAcquisition
import com.foxhole.guard.core.webapps.WebAppProfiles
import com.foxhole.guard.core.webapps.WebAppProxyController
import com.foxhole.guard.core.webapps.WebAppProxyPlan
import com.foxhole.guard.runtime.NetworkRuleCommandRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GuardBoundaryAndroidTest {
    @Test
    fun notificationCapabilityRejectsForgeryAndReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = NetworkRuleCommandRequests.issue(context, 41L, "one")
        assertFalse(NetworkRuleCommandRequests.consume(context, "forged", 41L, "one"))
        assertFalse(NetworkRuleCommandRequests.consume(context, token, 42L, "one"))
        assertFalse(NetworkRuleCommandRequests.consume(context, token, 41L, "other"))
        assertTrue(NetworkRuleCommandRequests.consume(context, token, 41L, "one"))
        assertFalse(NetworkRuleCommandRequests.consume(context, token, 41L, "one"))
    }

    @Test
    fun lateProxyReleaseDoesNotRevokeTheNewLease() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = WebAppProxyController(context) { _, _ -> WebAppProxyPlan.Direct }
        val a = controller.activate(WebAppRoute.DIRECT, false)
        assertTrue(a.applied)
        assertFalse(controller.activate(WebAppRoute.DIRECT, false).applied)
        val aLease = requireNotNull(a.lease)
        assertTrue(controller.clear(aLease))
        val b = controller.activate(WebAppRoute.DIRECT, false)
        val bLease = requireNotNull(b.lease)
        try {
            assertTrue(controller.clear(aLease))
            assertTrue(bLease.active)
            assertFalse(aLease.active)
            assertTrue(bLease.generation > aLease.generation)
        } finally {
            assertTrue(controller.clear(bLease))
        }
    }

    @Test
    fun separateWebAppsRetainDistinctVerifiedStorageProfiles() = runBlocking {
        withContext(Dispatchers.Main) {
            assumeTrue(WebAppProfiles.supported)
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val pending = WebAppPendingDeletions(context)
            // These isolated test profiles never use the default store; clear a previous run before loading them.
            for (id in listOf(TEST_APP_A, TEST_APP_B)) {
                assertTrue(WebAppProfiles.delete(id))
                assertTrue(pending.completed(id))
            }
            val first = WebView(context)
            val second = WebView(context)
            try {
                assertTrue(WebAppProfiles.install(first, TEST_APP_A))
                assertTrue(WebAppProfiles.install(second, TEST_APP_B))
                assertEquals("webapp-$TEST_APP_A", WebViewCompat.getProfile(first).name)
                assertEquals("webapp-$TEST_APP_B", WebViewCompat.getProfile(second).name)
                val firstCookies = WebAppProfiles.cookieManager(first)
                val secondCookies = WebAppProfiles.cookieManager(second)
                firstCookies.setCookie("https://guard-boundary.invalid", "session=first; Secure")
                firstCookies.flush()
                assertTrue(firstCookies.getCookie("https://guard-boundary.invalid").orEmpty().contains("session=first"))
                assertFalse(secondCookies.getCookie("https://guard-boundary.invalid").orEmpty().contains("session=first"))
            } finally {
                first.destroy()
                second.destroy()
                pending.apply {
                    record(TEST_APP_A, "https://guard-boundary.invalid")
                    record(TEST_APP_B, "https://guard-boundary.invalid")
                }
            }
        }
    }

    @Test
    fun biometricAtomicWriterUsesAnActualDirectoryDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "guard-boundary-atomic")
        try {
            AtomicFileWrites.writeBytesAtomic(file, "first".encodeToByteArray())
            AtomicFileWrites.writeBytesAtomic(file, "second".encodeToByteArray())
            assertEquals("second", file.readText())
            val descriptor = android.system.Os.open(context.cacheDir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun cancelledAcquisitionReleasesItsUnpublishedLease() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val lease = WebAppProxyLease(9L)
        var released = false
        var published = false
        val task = async(Dispatchers.Default) {
            publishWebAppAcquisition(
                isCurrent = { true },
                acquire = {
                    entered.complete(Unit)
                    complete.await()
                    WebAppProxyActivation(applied = true, lease = lease)
                },
                release = { released = true; it.revoke() },
                publish = { published = true },
            )
        }
        entered.await()
        task.cancel()
        complete.complete(Unit)
        task.join()
        assertTrue(released)
        assertFalse(published)
        assertFalse(lease.active)
    }

    @Test
    fun failedLegacyCleanupRetainsThePendingDeletionUntilBothStoresSucceed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = 8_800_003L
        val pending = WebAppPendingDeletions(context)
        var legacyCleared = false
        val cleaner = WebAppsDataCleaner(
            context,
            profileSupported = { true },
            siteSupported = { true },
            deleteProfile = { true },
            deleteLegacySite = { if (legacyCleared) it else null },
        )
        try {
            assertEquals(null, cleaner.clearApp(id, "https://guard-boundary.invalid"))
            assertTrue(id in pending.entries())
            assertFalse(cleaner.prepareForLoad(id))
            legacyCleared = true
            assertEquals("guard-boundary.invalid", cleaner.clearApp(id, "https://guard-boundary.invalid"))
            assertFalse(id in pending.entries())
        } finally {
            pending.completed(id)
        }
    }

    private companion object {
        const val TEST_APP_A = 8_800_001L
        const val TEST_APP_B = 8_800_002L
    }
}
