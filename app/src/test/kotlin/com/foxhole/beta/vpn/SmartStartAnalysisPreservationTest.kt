package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
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

    @Test
    fun `smart start analysis window does not publish app owned ip as dashboard route ip`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
                message = "Analysis",
                isSmartStartConnection = true,
            )

        val publish =
            shouldPublishAppOwnedIpInfoForSnapshot(
                snapshot = snapshot,
                analysisStatus = "Analysis",
            )

        assertFalse(publish)
    }

    @Test
    fun `ordinary idle state may publish app owned ip`() {
        val publish =
            shouldPublishAppOwnedIpInfoForSnapshot(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                ),
                analysisStatus = "Analysis",
            )

        assertTrue(publish)
    }
}
