package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pCarrierOwnershipTest {
    @Test
    fun `connected publication requires the exact owned vpn endpoint and applied runtime`() {
        assertTrue(
            carrierCurrent(
                activeVpnNetworkHandle = 361L,
                currentVpnNetworkHandle = 361L,
                currentEndpointGeneration = 17L,
                appliedRuntimeFingerprint = 23,
                sessionOwned = true,
            ),
        )
    }

    @Test
    fun `persisted settings or a stale snapshot cannot stand in for a live android carrier`() {
        assertFalse(carrierCurrent(activeVpnNetworkHandle = null, currentVpnNetworkHandle = null))
        assertFalse(carrierCurrent(activeVpnNetworkHandle = 361L, currentVpnNetworkHandle = null))
        assertFalse(carrierCurrent(activeVpnNetworkHandle = null, currentVpnNetworkHandle = 361L))
        assertFalse(carrierCurrent(sessionOwned = false))
    }

    @Test
    fun `replaced endpoint or unapplied config cannot publish connected`() {
        assertFalse(carrierCurrent(currentEndpointGeneration = 18L))
        assertFalse(carrierCurrent(appliedRuntimeFingerprint = 24))
    }

    @Test
    fun `cold readiness retries only while the original runtime owns the carrier`() {
        assertTrue(readinessProbeOwned())
        assertFalse(readinessProbeOwned(currentRuntimeGeneration = 102L))
        assertFalse(readinessProbeOwned(currentEndpointGeneration = 18L))
        assertFalse(readinessProbeOwned(currentEndpointGeneration = null))
        assertFalse(readinessProbeOwned(activeVpnNetworkHandle = 362L))
        assertFalse(readinessProbeOwned(activeVpnNetworkHandle = null))
        assertFalse(readinessProbeOwned(appliedRuntimeFingerprint = 24))
        assertFalse(readinessProbeOwned(appliedRuntimeFingerprint = null))
        assertFalse(readinessProbeOwned(sessionOwned = false))
    }

    private fun carrierCurrent(
        activeVpnNetworkHandle: Long? = 361L,
        currentVpnNetworkHandle: Long? = 361L,
        currentEndpointGeneration: Long? = 17L,
        appliedRuntimeFingerprint: Int? = 23,
        sessionOwned: Boolean = true,
    ): Boolean =
        isI2pCarrierConfirmationCurrent(
            expectedEndpointGeneration = 17L,
            currentEndpointGeneration = currentEndpointGeneration,
            expectedVpnNetworkHandle = 361L,
            activeVpnNetworkHandle = activeVpnNetworkHandle,
            currentVpnNetworkHandle = currentVpnNetworkHandle,
            expectedRuntimeFingerprint = 23,
            appliedRuntimeFingerprint = appliedRuntimeFingerprint,
            sessionOwned = sessionOwned,
        )

    private fun readinessProbeOwned(
        currentRuntimeGeneration: Long = 101L,
        currentEndpointGeneration: Long? = 17L,
        activeVpnNetworkHandle: Long? = 361L,
        appliedRuntimeFingerprint: Int? = 23,
        sessionOwned: Boolean = true,
    ): Boolean =
        isI2pReadinessProbeOwned(
            expectedRuntimeGeneration = 101L,
            currentRuntimeGeneration = currentRuntimeGeneration,
            expectedEndpointGeneration = 17L,
            currentEndpointGeneration = currentEndpointGeneration,
            expectedVpnNetworkHandle = 361L,
            activeVpnNetworkHandle = activeVpnNetworkHandle,
            expectedRuntimeFingerprint = 23,
            appliedRuntimeFingerprint = appliedRuntimeFingerprint,
            sessionOwned = sessionOwned,
        )
}
