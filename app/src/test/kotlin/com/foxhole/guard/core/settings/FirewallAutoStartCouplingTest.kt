package com.foxhole.guard.core.settings

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enabling the firewall enables autostart in the toggle handler only, never in normalization: normalization runs on every load and would make 'firewall on, autostart off' unreachable.
 * Disabling the firewall deliberately leaves autostart alone, since it may already carry a VPN profile restore.
 */
internal class FirewallAutoStartCouplingTest {
    @Test
    fun `normalization does not couple the firewall to auto start, so auto start stays switchable off`() {
        val normalized =
            Settings(
                connection = ConnectionSettings(autoStartOnBoot = false),
                expert = ExpertSettings(firewallEnabled = true),
            ).normalized()

        assertTrue(normalized.expert.firewallEnabled)
        assertFalse(normalized.connection.autoStartOnBoot)
    }

    @Test
    fun `disabling the firewall never revokes auto start`() {
        val normalized =
            Settings(
                connection = ConnectionSettings(autoStartOnBoot = true),
                expert = ExpertSettings(firewallEnabled = false),
            ).normalized()

        assertFalse(normalized.expert.firewallEnabled)
        assertTrue(normalized.connection.autoStartOnBoot)
    }

    @Test
    fun `auto start does not arm the firewall either`() {
        val normalized =
            Settings(
                connection = ConnectionSettings(autoStartOnBoot = true),
                expert = ExpertSettings(firewallEnabled = false),
            ).normalized()

        assertFalse(normalized.expert.firewallEnabled)
    }
}
