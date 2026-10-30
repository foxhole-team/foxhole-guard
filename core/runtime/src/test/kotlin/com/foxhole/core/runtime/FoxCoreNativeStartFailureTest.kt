package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

internal class FoxCoreNativeStartFailureTest {
    @Test
    fun `unsupported flow attribution survives native exception classification`() {
        val diagnostics = StartFailureDiagnostics()
        val error = assertThrows(FoxCoreRuntimeException::class.java) {
            invokeNativeStartWithDiagnostics(diagnostics) {
                throw IllegalStateException("UID/package routing requires Android 10+ flow attribution")
            }
        }

        assertEquals(FoxCoreRuntimeFailure.FLOW_ATTRIBUTION_UNAVAILABLE, error.failure)
        assertEquals(listOf("reason=flow_attribution_unavailable"), diagnostics.details)
    }

    @Test
    fun `ordinary native initialization failure remains retryable without exposing its text`() {
        val diagnostics = StartFailureDiagnostics()
        val handle = invokeNativeStartWithDiagnostics(diagnostics) {
            throw IllegalStateException("initialize outbounds at private.example credential=secret")
        }

        assertNull(handle)
        assertEquals(listOf("reason=outbound_initialization_failed"), diagnostics.details)
    }

    @Test
    fun `successful native start preserves the engine handle`() {
        val diagnostics = StartFailureDiagnostics()

        assertEquals(42L, checkNotNull(invokeNativeStartWithDiagnostics(diagnostics) { 42L }))
        assertEquals(emptyList<String>(), diagnostics.details)
    }
}

private class StartFailureDiagnostics : RuntimeDiagnosticsSink {
    val details = mutableListOf<String>()

    override fun record(tag: String, message: String) = Unit

    override fun recordStructured(tag: String, headline: String, vararg details: String?) {
        this.details += details.filterNotNull()
    }
}
