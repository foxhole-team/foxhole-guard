package com.foxhole.guard.core.data

import com.foxhole.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRefreshProtocolChangesTest {
    @Test
    fun `reports added removed available and unsupported protocols`() {
        val changes =
            profileRefreshProtocolChanges(
                before = setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN),
                after = setOf(ProtocolHint.VLESS, ProtocolHint.WIREGUARD),
                unavailableProtocolLabels = listOf("TUIC", "TUIC", " AMNEZIAWG "),
            )

        assertEquals(listOf("VLESS", "WIREGUARD"), changes.availableProtocolLabels)
        assertEquals(listOf("WIREGUARD"), changes.addedProtocolLabels)
        assertEquals(listOf("TROJAN"), changes.removedProtocolLabels)
        assertEquals(listOf("AMNEZIAWG", "TUIC"), changes.unavailableProtocolLabels)
        assertTrue(changes.hasChanges)
    }

    @Test
    fun `unchanged supported protocol set has no changes`() {
        val changes =
            profileRefreshProtocolChanges(
                before = setOf(ProtocolHint.HYSTERIA2),
                after = setOf(ProtocolHint.HYSTERIA2),
            )

        assertEquals(listOf("HYSTERIA2"), changes.availableProtocolLabels)
        assertFalse(changes.hasChanges)
    }
}
