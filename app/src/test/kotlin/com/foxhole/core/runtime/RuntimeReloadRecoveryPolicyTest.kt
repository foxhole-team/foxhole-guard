package com.foxhole.core.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.runtime.requiresColdRestartAfterTorRemoval
import com.foxhole.guard.runtime.requiresColdRestartForTorRouteApply
import com.foxhole.guard.runtime.shouldAttemptRuntimeReloadRestore
import com.foxhole.guard.runtime.shouldFinalizeSuccessfulTorDetach
import com.foxhole.guard.runtime.shouldReapTorHelpersAfterReloadRestore
import com.foxhole.guard.runtime.shouldReapTorHelpersAfterReloadStop
import com.foxhole.guard.runtime.shouldReapTorHelpersAfterRetiredStop
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReloadRecoveryPolicyTest {
    @Test
    fun `reload restore is attempted for same profile with changed config`() {
        assertTrue(
            shouldAttemptRuntimeReloadRestore(
                previousSession = testSession(configJson = """{"route":"vpn"}"""),
                failedSession = testSession(configJson = """{"route":"vpn","tor":true}"""),
            ),
        )
    }

    @Test
    fun `reload restore is not attempted without a previous runtime`() {
        assertFalse(
            shouldAttemptRuntimeReloadRestore(
                previousSession = null,
                failedSession = testSession(configJson = """{"route":"vpn","tor":true}"""),
            ),
        )
    }

    @Test
    fun `reload restore is not attempted for another profile or identical config`() {
        assertFalse(
            shouldAttemptRuntimeReloadRestore(
                previousSession = testSession(profileId = 1L, configJson = """{"route":"vpn"}"""),
                failedSession = testSession(profileId = 2L, configJson = """{"route":"vpn","tor":true}"""),
            ),
        )
        assertFalse(
            shouldAttemptRuntimeReloadRestore(
                previousSession = testSession(configJson = """{"route":"vpn"}"""),
                failedSession = testSession(configJson = """{"route":"vpn"}"""),
            ),
        )
    }

    @Test
    fun `removing tor hot reloads on the same tun and cleans up only after replacement`() {
        val tor = testSession(configJson = "old", torActive = true, tunPlanMarker = "shared")
        val plain = testSession(configJson = "new", torActive = false, tunPlanMarker = "shared")
        val changedTun = plain.copy(foxCoreConfig = testFoxCoreConfig("changed"))

        assertFalse(requiresColdRestartAfterTorRemoval(tor, plain))
        assertFalse(requiresColdRestartAfterTorRemoval(plain, tor))
        assertFalse(requiresColdRestartAfterTorRemoval(tor, tor.copy(configJson = "next-tor")))
        assertFalse(
            requiresColdRestartForTorRouteApply(
                previousSession = tor,
                nextSession = plain,
            ),
        )
        assertTrue(requiresColdRestartAfterTorRemoval(tor, changedTun))
        assertTrue(requiresColdRestartForTorRouteApply(tor, changedTun))
        val appliedPlain =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = plain.profileId,
                torActive = false,
            )
        assertTrue(
            shouldFinalizeSuccessfulTorDetach(
                previousSession = tor,
                nextSession = plain,
                appliedSnapshot = appliedPlain,
                nextSessionIsActive = true,
            ),
        )
        assertFalse(
            shouldFinalizeSuccessfulTorDetach(
                previousSession = tor,
                nextSession = plain,
                appliedSnapshot = appliedPlain.copy(inPlaceRuntimeReload = true),
                nextSessionIsActive = true,
            ),
        )
        assertFalse(
            shouldFinalizeSuccessfulTorDetach(
                previousSession = tor,
                nextSession = plain,
                appliedSnapshot = appliedPlain,
                nextSessionIsActive = false,
            ),
        )
        assertFalse(
            shouldFinalizeSuccessfulTorDetach(
                previousSession = plain,
                nextSession = tor,
                appliedSnapshot = appliedPlain,
                nextSessionIsActive = true,
            ),
        )
    }

    @Test
    fun `reload restart reaps helpers whenever either runtime carries tor`() {
        val plain = testSession(configJson = "plain")
        val tor = testSession(configJson = "tor", torActive = true)
        val torOnly = testSession(profileId = TOR_ONLY_PROFILE_ID, configJson = "tor-only")

        assertTrue(shouldReapTorHelpersAfterReloadStop(tor, plain))
        assertTrue(shouldReapTorHelpersAfterReloadStop(plain, tor))
        assertTrue(shouldReapTorHelpersAfterReloadStop(torOnly, plain))
        assertFalse(shouldReapTorHelpersAfterReloadStop(plain, plain.copy(configJson = "next")))
    }

    @Test
    fun `failed tor attach reaps helpers only after restoring a non tor runtime`() {
        val plain = testSession(configJson = "plain")
        val tor = testSession(configJson = "tor", torActive = true)

        assertTrue(shouldReapTorHelpersAfterReloadRestore(plain, tor))
        assertFalse(shouldReapTorHelpersAfterReloadRestore(tor, tor.copy(configJson = "next-tor")))
        assertFalse(shouldReapTorHelpersAfterReloadRestore(null, tor))
        assertFalse(shouldReapTorHelpersAfterReloadRestore(plain, plain.copy(configJson = "next-plain")))
    }

    @Test
    fun `retired runtime cleanup never kills transports owned by its replacement`() {
        val plain = testSession(configJson = "plain")
        val tor = testSession(configJson = "tor", torActive = true)
        val torOnly = testSession(profileId = TOR_ONLY_PROFILE_ID, configJson = "tor-only")

        assertTrue(shouldReapTorHelpersAfterRetiredStop(null))
        assertTrue(shouldReapTorHelpersAfterRetiredStop(plain))
        assertFalse(shouldReapTorHelpersAfterRetiredStop(tor))
        assertFalse(shouldReapTorHelpersAfterRetiredStop(torOnly))
    }

    private fun testSession(
        profileId: Long = 1L,
        configJson: String,
        torActive: Boolean = false,
        tunPlanMarker: String = "shared",
    ): VpnSession =
        VpnSession(
            profileId = profileId,
            profileName = "Profile",
            protocolHint = ProtocolHint.VLESS,
            protocolOptionId = "vless",
            configJson = configJson,
            correlationId = "session-$profileId",
            foxCoreConfig = testFoxCoreConfig(tunPlanMarker),
            torActive = torActive,
        )

    private fun testFoxCoreConfig(tunPlanMarker: String): FoxCoreSessionConfig =
        FoxCoreSessionConfig(
            engineConfigJson = "{}",
            policyConfigJson = "{}",
            tunPlan =
            FoxCoreTunPlan(
                mtu = 1_500,
                ipv4Address = "172.19.0.1",
                ipv4PrefixLength = 30,
                ipv6Address = null,
                ipv6PrefixLength = null,
                routes = emptyList(),
                advertisedDnsServers = emptyList(),
                allowedApplications = listOf(tunPlanMarker),
            ),
        )
}
