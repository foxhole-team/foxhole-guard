package com.foxhole.guard.ui

import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelRoutingDecisionTest {

    private fun shape(
        mode: TrafficMode = TrafficMode.TUNNEL,
        torOnly: Boolean = false,
    ) = RoutingChangeState(trafficMode = mode, torOnlyRuntime = torOnly)

    @Test
    fun `same-shape changes hot reload without restart or prompt`() {
        listOf(
            "split mode FULL<->INCLUDE<->EXCLUDE on a live tunnel" to (shape() to shape()),
            "app set edit on a live tunnel" to (shape() to shape()),
            "proxy surface or port edit on a live proxy runtime" to
                (shape(TrafficMode.PROXY) to shape(TrafficMode.PROXY)),
            "tor overlay attach (VPN -> VPN+TOR) on a live tunnel" to (shape() to shape()),
            "tor overlay detach (VPN+TOR -> VPN) on a live tunnel" to (shape() to shape()),
        ).forEach { (case, transition) ->
            assertEquals(
                case,
                RoutingChangeAction.HOT_RELOAD,
                resolveRoutingChangeAction(old = transition.first, new = transition.second),
            )
        }
    }

    @Test
    fun `runtime shape changes are a full switch`() {
        listOf(
            "live VPN -> standalone tor-only" to (shape() to shape(torOnly = true)),
            "standalone tor-only -> anything else" to (shape(torOnly = true) to shape()),
            "live TUNNEL -> PROXY" to (shape(TrafficMode.TUNNEL) to shape(TrafficMode.PROXY)),
            "live PROXY -> TUNNEL" to (shape(TrafficMode.PROXY) to shape(TrafficMode.TUNNEL)),
        ).forEach { (case, transition) ->
            assertEquals(
                case,
                RoutingChangeAction.FULL_SWITCH,
                resolveRoutingChangeAction(old = transition.first, new = transition.second),
            )
        }
    }

    @Test
    fun `standalone tor-only runtime ignores the traffic-mode setting`() {
        assertEquals(
            RoutingChangeAction.HOT_RELOAD,
            resolveRoutingChangeAction(
                old = shape(TrafficMode.TUNNEL, torOnly = true),
                new = shape(TrafficMode.PROXY, torOnly = true),
            ),
        )
    }

    @Test
    fun `every live routing decision goes through the single matrix`() {
        val appRoutingSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelAppRoutingSettingsSupport.kt").readText()
        val runtimeSupportSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelRuntimeSupport.kt").readText()
        assertTrue(appRoutingSource.contains("resolveRoutingChangeAction"))
        assertTrue(runtimeSupportSource.contains("resolveRoutingChangeAction"))
        assertFalse(appRoutingSource.contains("liveSnapshot.trafficMode != targetTrafficMode"))
        assertFalse(appRoutingSource.contains("targetTrafficMode == liveTrafficMode"))
        assertFalse(appRoutingSource.contains("shouldUseHotReloadForPrivacyRouteModeChange"))
    }
}
