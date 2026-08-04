package com.foxhole.core.runtime

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
}
