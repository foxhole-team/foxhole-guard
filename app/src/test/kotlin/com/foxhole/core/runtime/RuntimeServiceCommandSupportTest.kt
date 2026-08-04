package com.foxhole.core.runtime

import android.net.VpnService
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.isFailClosedRuntimeServiceCommand
import com.foxhole.guard.runtime.runtimeCommandForServiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeServiceCommandSupportTest {
    @Test
    fun `unknown commands fail closed but null lifecycle intents do not`() {
        assertFalse(isFailClosedRuntimeServiceCommand(null))
        assertFalse(isFailClosedRuntimeServiceCommand(VpnService.SERVICE_INTERFACE))
        assertTrue(isFailClosedRuntimeServiceCommand("com.foxhole.guard.action.UNKNOWN"))

        assertNull(
            runtimeCommandForServiceAction(
                action = VpnService.SERVICE_INTERFACE,
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_CONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_DISCONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL_TOR))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RELOAD))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RESTORE))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD))
    }

    @Test
    fun `service connect maps to typed tunnel command`() {
        val command =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                protocolOptionId = "hysteria2-main",
                previousVpnNetworkHandle = 42L,
            )

        assertEquals(
            RuntimeCommand.StartTunnel(
                profileId = 7L,
                optionId = "hysteria2-main",
                previousVpnNetworkHandle = 42L,
                source = RuntimeCommandSource.SERVICE,
            ),
            command,
        )
        assertEquals(RuntimeCommandPriority.SWITCH, command?.priority)
        assertEquals("connect:7:hysteria2-main", command?.queueReason)
    }

    @Test
    fun `service connect maps proxy service to proxy command`() {
        val command =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.PROXY,
                profileId = 8L,
                protocolOptionId = null,
                previousVpnNetworkHandle = 55L,
            )

        assertEquals(
            RuntimeCommand.StartProxy(
                profileId = 8L,
                optionId = null,
                source = RuntimeCommandSource.SERVICE,
            ),
            command,
        )
        assertEquals(RuntimeCommandPriority.SWITCH, command?.priority)
        assertEquals("proxy:8:default", command?.queueReason)
    }

    @Test
    fun `service stop reload restore and local guard map to runtime commands`() {
        val disconnect =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                trafficMode = TrafficMode.TUNNEL,
            )
        val reload =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_RELOAD,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 9L,
            )
        val restore =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                trafficMode = TrafficMode.TUNNEL,
            )
        val localGuard =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                trafficMode = TrafficMode.TUNNEL,
                localGuardMode = LocalGuardMode.FIREWALL,
            )

        assertEquals(RuntimeCommand.Stop("disconnect", RuntimeCommandSource.SERVICE), disconnect)
        assertEquals(RuntimeCommandPriority.USER_STOP, disconnect?.priority)
        assertEquals("disconnect", disconnect?.queueReason)

        assertEquals(RuntimeCommand.Reload("9", RuntimeCommandSource.SERVICE), reload)
        // Reload runs at NORMAL priority so a config reload never preempts (force-kills) a live
        // start; see RuntimeSupervisor.commandPriority.
        assertEquals(RuntimeCommandPriority.NORMAL, reload?.priority)
        assertEquals("reload:9", reload?.queueReason)

        assertEquals(RuntimeCommand.Restore(RuntimeCommandSource.SERVICE), restore)
        assertEquals(RuntimeCommandPriority.NORMAL, restore?.priority)
        assertEquals("restore", restore?.queueReason)

        assertEquals(RuntimeCommand.StartLocalGuard(LocalGuardMode.FIREWALL, RuntimeCommandSource.SERVICE), localGuard)
        assertEquals(RuntimeCommandPriority.SWITCH, localGuard?.priority)
        // cfg-штамп различает старты guard'а с разным конфигом: без него StartLocalGuard,
        // посланный из-за изменения настроек, коалессировался с ещё бегущим стартом того же mode.
        assertEquals("local_guard:firewall:cfg=0", localGuard?.queueReason)
    }

    @Test
    fun `local guard start is deferred while a profile runtime is active`() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            assertTrue(
                shouldDeferLocalGuardStartForActiveProfileRuntime(
                    snapshot =
                    ConnectionSnapshot(
                        state = state,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = 7L,
                    ),
                    activeProfileSessionPresent = false,
                ),
            )
        }
    }

    @Test
    fun `local guard start is not deferred while a tor-only runtime is active`() {
        // Enabling the firewall while Tor-only is running must switch modes (stop Tor, start the
        // guard), not defer silently — otherwise Tor keeps running and the dashboard keeps showing
        // the Tor exit IP. Only a real upstream VPN profile (id > 0) defers, because that tunnel
        // already enforces the guard's blocking/DNS duties in place.
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            assertFalse(
                shouldDeferLocalGuardStartForActiveProfileRuntime(
                    snapshot =
                    ConnectionSnapshot(
                        state = state,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    ),
                    activeProfileSessionPresent = false,
                ),
            )
        }
    }

    @Test
    fun `local guard start is not deferred for idle or existing local guard runtime`() {
        assertFalse(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
                activeProfileSessionPresent = false,
            ),
        )
        assertFalse(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
                activeProfileSessionPresent = false,
            ),
        )
    }

    @Test
    fun `disabling local guard does not stop an active profile runtime`() {
        assertFalse(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
            ),
        )
        assertFalse(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
        assertTrue(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
        // A guard stuck in ERROR still owns the runtime snapshot; disabling the firewall must tear
        // it down instead of skipping the stop.
        assertTrue(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
    }

    @Test
    fun `local guard start is deferred when the service still owns a profile session`() {
        assertTrue(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = null,
                ),
                activeProfileSessionPresent = true,
            ),
        )
    }

    @Test
    fun `unknown service action has no runtime command`() {
        assertNull(
            runtimeCommandForServiceAction(
                action = "com.foxhole.guard.action.UNKNOWN",
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
    }

    @Test
    fun `runtime service ownership follows active snapshot mode`() {
        val proxySnapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.PROXY,
            )

        assertTrue(proxySnapshot.isActiveRuntimeFor(TrafficMode.PROXY))
        assertFalse(proxySnapshot.isActiveRuntimeFor(TrafficMode.TUNNEL))
        assertTrue(proxySnapshot.isActiveRuntimeForAnotherMode(TrafficMode.TUNNEL))
    }

    @Test
    fun `dashboard stop tor uses dedicated kill tor service action`() {
        val toggleSource = sourceFile("guard/ui/HomeViewModelConnectionToggleSupport.kt").readText()
        val controllerSource = sourceFile("guard/runtime/FoxholeConnectionController.kt").readText()
        val lifecycleSource = sourceFile("guard/runtime/FoxholeConnectionLifecycle.kt").readText()

        assertTrue(toggleSource.contains("toggleTorOnlyRuntimeConnection(state)"))
        assertTrue(toggleSource.contains("state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID"))
        // Early-start pending (profile id not yet in the snapshot) must also resolve to a stop, not
        // a reconnect — see toggleTorOnlyRuntimeConnection.
        assertTrue(toggleSource.contains("state.torOperation.active && !state.connection.isPrimaryConnectionRuntime()"))
        assertTrue(toggleSource.contains("container.connectionController.disconnectTorOnly()"))
        assertTrue(controllerSource.contains("fun disconnectTorOnly(userInitiated: Boolean = true)"))
        assertTrue(lifecycleSource.contains("fun disconnectTorOnly(userInitiated: Boolean = true)"))
        assertTrue(lifecycleSource.contains("action = FoxholeConnectionServiceContract.ACTION_KILL_TOR"))
        assertTrue(lifecycleSource.contains("suppressLocalGuard = true"))
    }

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/$relativePath"),
            File("app/src/main/kotlin/com/foxhole/$relativePath"),
            File("../app/src/main/kotlin/com/foxhole/$relativePath"),
        ).first(File::isFile)
}
