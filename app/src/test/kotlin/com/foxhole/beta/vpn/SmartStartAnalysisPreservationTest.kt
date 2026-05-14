package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartStartAnalysisPreservationTest {
    @Test
    fun `preserves analysis marker during internal smart start disconnect`() {
        val resolved =
            resolveSmartStartAnalysisPreservation(
                previousSnapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    message = "Analysis",
                    isSmartStartConnection = true,
                ),
                analysisStatus = "Analysis",
                disconnectMessage = null,
                preserveSmartStartAnalysis = true,
            )

        assertEquals("Analysis", resolved.message)
        assertTrue(resolved.isSmartStartConnection)
    }

    @Test
    fun `does not preserve analysis marker for real errors or ordinary disconnects`() {
        val smartSnapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                message = "Analysis",
                isSmartStartConnection = true,
            )

        assertFalse(
            resolveSmartStartAnalysisPreservation(
                previousSnapshot = smartSnapshot,
                analysisStatus = "Analysis",
                disconnectMessage = "failed",
                preserveSmartStartAnalysis = true,
            ).isSmartStartConnection,
        )
        assertNull(
            resolveSmartStartAnalysisPreservation(
                previousSnapshot = smartSnapshot,
                analysisStatus = "Analysis",
                disconnectMessage = null,
                preserveSmartStartAnalysis = false,
            ).message,
        )
    }
}
