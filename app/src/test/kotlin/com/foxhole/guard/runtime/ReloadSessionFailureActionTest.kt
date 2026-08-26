package com.foxhole.guard.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.withLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReloadSessionFailureActionTest {
    @Test
    fun `a live route survives a session build that failed`() {
        listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING).forEach { state ->
            assertEquals(
                state.name,
                ReloadSessionFailureAction.KEEP_LIVE_ROUTE,
                reloadSessionFailureAction(session(), state),
            )
        }
    }

    @Test
    fun `without a live session the failure still fails closed`() {
        assertEquals(
            ReloadSessionFailureAction.FAIL_CLOSED,
            reloadSessionFailureAction(null, ConnectionState.CONNECTED),
        )
        listOf(ConnectionState.IDLE, ConnectionState.ERROR).forEach { state ->
            assertEquals(
                state.name,
                ReloadSessionFailureAction.FAIL_CLOSED,
                reloadSessionFailureAction(session(), state),
            )
        }
    }

    @Test
    fun `an authoritative TOR detach never keeps the live TOR route`() {
        assertEquals(
            ReloadSessionFailureAction.FAIL_CLOSED,
            reloadSessionFailureAction(
                activeSession = torSession(),
                connectionState = ConnectionState.CONNECTED,
                authoritativeTorReconcile = true,
            ),
        )
    }

    @Test
    fun `desired TOR route mismatch requires authoritative reconciliation`() {
        val active = torSession(scope = PrivacyRouteScope.SELECTED_APPS)
        val disabled = Settings()
        val allApps = torSettings(scope = PrivacyRouteScope.ALL_APPS)
        val selected = torSettings(scope = PrivacyRouteScope.SELECTED_APPS)

        assertTrue(requiresAuthoritativeTorReconcile(active, disabled))
        assertTrue(requiresAuthoritativeTorReconcile(active, allApps))
        assertFalse(requiresAuthoritativeTorReconcile(active, selected))
        assertTrue(requiresAuthoritativeTorReconcile(session(), allApps))
        assertTrue(requiresAuthoritativeTorReconcile(active.copy(appliedTorRoute = null), selected))
    }

    @Test
    fun `a reload result built for an older TOR generation is discarded`() {
        val selectedSession = torSession(scope = PrivacyRouteScope.SELECTED_APPS)

        assertTrue(reloadSessionMatchesCurrentTorIntent(selectedSession, torSettings(PrivacyRouteScope.SELECTED_APPS)))
        assertFalse(reloadSessionMatchesCurrentTorIntent(selectedSession, torSettings(PrivacyRouteScope.ALL_APPS)))
        assertFalse(reloadSessionMatchesCurrentTorIntent(selectedSession, Settings()))
    }

    @Test
    fun `TOR scope TUN-plan change uses a cold restart`() {
        val previous = torSession(scope = PrivacyRouteScope.SELECTED_APPS, allowedPackages = listOf("com.example.one"))
        val next = torSession(scope = PrivacyRouteScope.ALL_APPS, allowedPackages = emptyList())

        assertTrue(requiresColdRestartForTorRouteApply(previous, next))
        assertFalse(requiresColdRestartForTorRouteApply(next, next.copy(correlationId = "same-plan")))
    }

    @Test
    fun `the reload session-load failure handler routes through the decision instead of stopping`() {
        val handler =
            File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceReloadSupport.kt")
                .readText()
                .substringAfter("private suspend fun FoxholeVpnService.loadReloadSessionOrFail(")
                .substringBefore("internal enum class ReloadSessionFailureAction")
                .lineSequence()
                .filterNot { line -> line.trimStart().startsWith("//") }
                .joinToString("\n")

        assertTrue("the handler must exist", handler.isNotBlank())
        assertTrue(
            "the session-load failure must consult reloadSessionFailureAction",
            handler.contains("reloadSessionFailureAction("),
        )
        assertTrue(
            "keeping the live route must be one of its outcomes",
            handler.contains("keepLiveRuntimeAfterReloadSessionFailure("),
        )
        assertEquals(
            "fail() may be reached only through the FAIL_CLOSED branch",
            1,
            handler.split("fail(").size - 1,
        )
        assertTrue(
            "the only fail() left must be the fail-closed branch",
            handler.contains("ReloadSessionFailureAction.FAIL_CLOSED -> fail(message)"),
        )
    }

    private fun session() =
        VpnSession(
            profileId = 1L,
            profileName = "owner",
            protocolHint = ProtocolHint.VLESS,
            configJson = "{}",
            correlationId = "reload-failure",
        )

    private fun torSession(
        scope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
        allowedPackages: List<String> = emptyList(),
    ): VpnSession =
        session().copy(
            correlationId = "tor-$scope-${allowedPackages.size}",
            torActive = true,
            appliedTorRoute =
            AppliedTorRoute(
                scope = scope,
                bypassVpnTunnel = false,
                selectedPackages = if (scope == PrivacyRouteScope.SELECTED_APPS) SELECTED_TOR_PACKAGES else emptyList(),
            ),
            foxCoreConfig =
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
                    allowedApplications = allowedPackages,
                ),
            ),
        )

    private fun torSettings(scope: PrivacyRouteScope): Settings =
        Settings(
            expert =
            if (scope == PrivacyRouteScope.SELECTED_APPS) {
                ExpertSettings().withLane(AppTunnelLane.TOR, SELECTED_TOR_PACKAGES)
            } else {
                ExpertSettings()
            },
            privacyRoute =
            PrivacyRouteSettings(
                permitted = true,
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                scope = scope,
            ),
        )

    private companion object {
        val SELECTED_TOR_PACKAGES = listOf("com.example.tor")
    }
}
