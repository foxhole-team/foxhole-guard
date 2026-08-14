package com.foxhole.core.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A queued guard start meets settings that may have moved since it was sent.
 *
 * Guard commands are serialized, so "start the firewall guard" can reach the service after the user
 * (or another sync) has already asked for something else. Only one of the three answers may tear the
 * runtime down, and the one that used to be wrong is the mode mismatch: it took the same teardown
 * branch as "no guard wanted", which stopped a running, still-wanted guard and — because that
 * teardown suppresses the guard re-raise — left the firewall down with nothing to bring it back.
 */
internal class LocalGuardStartResolutionTest {
    @Test
    fun `a start for the mode the settings want is simply started`() {
        assertEquals(
            LocalGuardStartResolution.START_REQUESTED,
            resolveLocalGuardStart(
                desiredMode = LocalGuardMode.FIREWALL,
                requestedMode = LocalGuardMode.FIREWALL,
            ),
        )
    }

    @Test
    fun `a stale start for the other mode starts the wanted guard instead of stopping it`() {
        // The regression this exists for. A firewall enabled while a DNS-guard start was still in
        // the queue used to end as "local guard started" followed a second later by "local guard
        // stopped", with no producer left to raise it again.
        assertEquals(
            LocalGuardStartResolution.START_DESIRED,
            resolveLocalGuardStart(
                desiredMode = LocalGuardMode.FIREWALL,
                requestedMode = LocalGuardMode.DNS,
            ),
        )
        assertEquals(
            LocalGuardStartResolution.START_DESIRED,
            resolveLocalGuardStart(
                desiredMode = LocalGuardMode.DNS,
                requestedMode = LocalGuardMode.FIREWALL,
            ),
        )
    }

    @Test
    fun `a start that nothing wants any more is the one case that tears the runtime down`() {
        LocalGuardMode.entries.forEach { requested ->
            assertEquals(
                "requested=$requested",
                LocalGuardStartResolution.STOP,
                resolveLocalGuardStart(desiredMode = null, requestedMode = requested),
            )
        }
    }

    @Test
    fun `engaged i2p raises a replacement carrier after profile stop by default`() {
        val settings =
            Settings(
                i2p = I2pSettings(enabled = true, engaged = true),
            )

        assertEquals(LocalGuardMode.FIREWALL, settings.localGuardModeAfterCleanProfileDisconnect())
    }

    @Test
    fun `disabled i2p carrier recovery does not independently raise a guard`() {
        val settings =
            Settings(
                i2p =
                I2pSettings(
                    enabled = true,
                    engaged = true,
                    autoReconnectAfterVpnDisconnect = false,
                ),
            )

        assertEquals(null, settings.localGuardModeAfterCleanProfileDisconnect())
        assertEquals(false, settings.runtimeSettingsAfterCleanProfileDisconnect().i2p.engaged)
        assertEquals(true, settings.i2p.engaged)
    }

    @Test
    fun `firewall and dns guards remain authoritative when i2p carrier recovery is off`() {
        val i2pWithoutRecovery =
            I2pSettings(
                enabled = true,
                engaged = true,
                autoReconnectAfterVpnDisconnect = false,
            )

        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert = ExpertSettings(firewallEnabled = true),
                i2p = i2pWithoutRecovery,
            ).localGuardModeAfterCleanProfileDisconnect(),
        )
        assertEquals(
            LocalGuardMode.DNS,
            Settings(
                dns = DnsSettings(replaceSystemDns = true),
                i2p = i2pWithoutRecovery,
            ).localGuardModeAfterCleanProfileDisconnect(),
        )
    }
}
