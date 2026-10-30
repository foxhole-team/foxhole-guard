package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class CategorizedDnsActivationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun deferredRejectedAndSupersededNeverReportUpdatedOrChangeLastGood() = runBlocking {
        listOf(
            RuntimeDnsRuleSetInstallOutcome.Deferred,
            RuntimeDnsRuleSetInstallOutcome.Rejected,
            RuntimeDnsRuleSetInstallOutcome.Superseded
        ).forEach { outcome ->
            val store = CategorizedDnsStagedStore(temporary.newFolder())
            val input = bundle()
            val stage = store.stage(input)
            val result = activateCategorizedDnsStage(stage, input, store) { received, bytes ->
                assertSame(stage, received)
                assertArrayEquals(input.manifestBytes, bytes.manifestBytes)
                assertArrayEquals(input.artifactBytes, bytes.artifactBytes)
                CategorizedDnsNativeActivation(outcome) { true }
            }
            assertEquals(
                if (outcome == RuntimeDnsRuleSetInstallOutcome.Rejected) {
                    DnsFilterUpdateStatus.FAILED
                } else {
                    DnsFilterUpdateStatus.SKIPPED
                },
                result.status
            )
            assertNull(result.appliedVariant)
            assertNull(store.activeOrNull("normal-medium"))
        }
    }

    @Test
    fun installedRevisionWithoutOwnerProofCannotPromote() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val input = bundle()
        val stage = store.stage(input)
        val result = activateCategorizedDnsStage(stage, input, store) { _, _ ->
            CategorizedDnsNativeActivation(RuntimeDnsRuleSetInstallOutcome.Installed(1)) { false }
        }
        assertEquals(DnsFilterBundleState.SUPERSEDED, result.bundleState)
        assertNull(store.activeOrNull("normal-medium"))
    }

    @Test
    fun exactOwnedSuccessPromotesAndReportsAppliedVariant() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val input = bundle()
        val stage = store.stage(input)
        val result = activateCategorizedDnsStage(stage, input, store) { _, _ ->
            CategorizedDnsNativeActivation(RuntimeDnsRuleSetInstallOutcome.Installed(1)) { true }
        }
        assertEquals(DnsFilterUpdateStatus.UPDATED, result.status)
        assertEquals(DnsFilterBundleState.ACTIVE, result.bundleState)
        assertEquals("normal-medium", result.appliedVariant)
        assertEquals(stage, store.activeOrNull("normal-medium"))
    }

    @Test
    fun ordinaryCancellationAndFatalInstallerErrorsEscapeAdapter() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val input = bundle()
        val stage = store.stage(input)
        listOf(IOException("owned"), CancellationException("owned"), LinkageError("owned")).forEach { error ->
            val actual = assertThrows(error.javaClass) {
                runBlocking { activateCategorizedDnsStage(stage, input, store) { _, _ -> throw error } }
            }
            assertSame(error, actual)
            assertNull(store.activeOrNull("normal-medium"))
        }
    }
}
