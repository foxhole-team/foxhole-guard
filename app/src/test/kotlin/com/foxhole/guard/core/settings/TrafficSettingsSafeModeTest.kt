package com.foxhole.guard.core.settings

import com.foxhole.core.model.DomainStrategy
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TunStack
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TrafficSettingsSafeModeTest : SettingsRepositoryTestSupport() {
    private val pristine = Settings().normalized()

    @Test
    fun `fresh install runs in safe mode, so this is the default path and not an edge case`() {
        assertTrue(Settings().connection.safeModeEnabled)
        assertTrue(pristine.connection.safeModeEnabled)
    }

    @Test
    fun `tun stack survives normalization and a reread on a fresh install`() {
        val stored = updateTunStackIn(pristine, TunStack.GVISOR).normalized()

        assertNotEquals(pristine, stored)
        assertEquals(TunStack.GVISOR, stored.traffic.tunStack)
        assertEquals(TunStack.GVISOR, reread(stored).traffic.tunStack)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `mtu survives normalization and a reread on a fresh install`() {
        val stored = updateTrafficMtuIn(pristine, 1280).normalized()

        assertNotEquals(pristine, stored)
        assertEquals(1280, stored.traffic.mtu)
        assertEquals(1280, reread(stored).traffic.mtu)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `out of range mtu is clamped instead of being dropped`() {
        val stored = updateTrafficMtuIn(pristine, 42).normalized()

        assertEquals(SETTINGS_MIN_MTU, stored.traffic.mtu)
        assertEquals(SETTINGS_MIN_MTU, reread(stored).traffic.mtu)
    }

    @Test
    fun `prefer ipv6 survives normalization and a reread on a fresh install`() {
        val stored = updatePreferIpv6In(pristine, true).normalized()

        assertNotEquals(pristine, stored)
        assertTrue(stored.traffic.preferIpv6)
        assertTrue(reread(stored).traffic.preferIpv6)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `domain strategy survives normalization and a reread on a fresh install`() {
        val stored = updateDomainStrategyIn(pristine, DomainStrategy.IPV4_ONLY).normalized()

        assertNotEquals(pristine, stored)
        assertEquals(DomainStrategy.IPV4_ONLY, stored.traffic.domainStrategy)
        assertEquals(DomainStrategy.IPV4_ONLY, reread(stored).traffic.domainStrategy)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `all four tuning fields survive together`() {
        val stored =
            updateDomainStrategyIn(
                updatePreferIpv6In(
                    updateTrafficMtuIn(
                        updateTunStackIn(pristine, TunStack.GVISOR),
                        1400,
                    ),
                    true,
                ),
                DomainStrategy.IPV6_ONLY,
            ).normalized()

        val restored = reread(stored)
        assertEquals(TunStack.GVISOR, restored.traffic.tunStack)
        assertEquals(1400, restored.traffic.mtu)
        assertTrue(restored.traffic.preferIpv6)
        assertEquals(DomainStrategy.IPV6_ONLY, restored.traffic.domainStrategy)
    }

    @Test
    fun `safe mode still pins the traffic mode to tunnel`() {
        val forced = pristine.copy(traffic = pristine.traffic.copy(mode = TrafficMode.PROXY)).normalized()

        assertEquals(TrafficMode.TUNNEL, forced.traffic.mode)
    }

    @Test
    fun `coercing the mode back to tunnel does not take the tuning fields with it`() {
        val stored =
            updateTunStackIn(pristine, TunStack.GVISOR)
                .let { settings -> updateTrafficMtuIn(settings, 1400) }
                .let { settings -> settings.copy(traffic = settings.traffic.copy(mode = TrafficMode.PROXY)) }
                .normalized()

        assertEquals(TrafficMode.TUNNEL, stored.traffic.mode)
        assertEquals(TunStack.GVISOR, stored.traffic.tunStack)
        assertEquals(1400, stored.traffic.mtu)
    }

    @Test
    fun `leaving safe mode keeps the tuning fields untouched`() {
        val stored = updateTrafficMtuIn(pristine, 1400).normalized()
        val unlocked = stored.copy(connection = stored.connection.copy(safeModeEnabled = false)).normalized()

        assertEquals(1400, unlocked.traffic.mtu)
        assertEquals(1400, reread(unlocked).traffic.mtu)
    }

    private fun reread(value: Settings): Settings =
        json.decodeFromString<Settings>(json.encodeToString(value)).normalized()
}
