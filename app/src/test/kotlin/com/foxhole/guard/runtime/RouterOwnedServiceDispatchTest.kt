package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.priority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouterOwnedServiceDispatchTest {
    private val token = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val start = RouterOwnedResume(token, 7L, "hysteria2", 4L, 9L)
    private val stop = RouterOwnedDisconnect(7L, "hysteria2", 11L, 42L, 3L, 55L, "192.168.1.1", 8443, 10_000L, 9L, token)
    private class Calls {
        val pending = mutableListOf<Pair<RuntimeCommand, suspend (RuntimeCommand) -> Unit>>()
        val scoped = mutableListOf<String>()
        var generic = 0
        val handlers = RuntimeServiceCommandHandlers(
            dispatch = { command, execute -> pending += command to execute },
            connect = { _, _, _, _, _, _, _ -> generic++ }, disconnect = { _, _, _ -> generic++ },
            reload = { generic++ }, enforceQuarantine = { generic++ }, startLocalGuard = { _, _ -> generic++ },
            activeProfileSessionPresent = { true }, failClosedTeardown = { _, _ -> generic++ },
            connectOwned = { owner, id -> scoped += "start:${owner.profileId}:$id" },
            disconnectOwned = { owner, id -> scoped += "stop:${owner.profileId}:$id" })
    }
    @Test fun knownOwnedActionsAreNotClassifiedAsGenericFailClosedKill() {
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_CONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT))
        assertTrue(isFailClosedRuntimeServiceCommand("com.foxhole.guard.action.UNKNOWN"))
    }
    @Test fun missingOrInvalidReceiptsAreScopedDropsAgainstAnUnrelatedActiveRuntime() {
        assertTrue(isMalformedRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_CONNECT, { null }, { stop }))
        assertTrue(isMalformedRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, { start }, { stop.copy(smartToken = null) }))
        assertTrue(isMalformedRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, { start }, { stop.copy(smartToken = "bad") }))
        assertFalse(isMalformedRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_CONNECT, { start }, { null }))
        assertFalse(isMalformedRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, { null }, { stop }))
        val calls = Calls()
        assertTrue(dispatchRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_CONNECT, 51, calls.handlers, { null }, { stop }))
        assertTrue(dispatchRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, 52, calls.handlers, { start }, { null }))
        assertTrue(dispatchRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, 53, calls.handlers, { start }, { stop.copy(smartToken = null) }))
        assertTrue(dispatchRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, 54, calls.handlers, { start }, { stop.copy(smartToken = "bad") }))
        assertTrue(calls.pending.isEmpty()); assertEquals(0, calls.generic); assertTrue(calls.scoped.isEmpty())
    }
    @Test fun validOwnedReceiptsReachOnlyConditionalNormalPriorityHandlers() = runTest {
        val calls = Calls()
        assertTrue(dispatchRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_CONNECT, 51, calls.handlers, { start }, { null }))
        assertTrue(dispatchRouterOwnedRuntimeCommand(FoxholeConnectionServiceContract.ACTION_ROUTER_OWNED_DISCONNECT, 52, calls.handlers, { null }, { stop }))
        assertEquals(2, calls.pending.size)
        calls.pending.forEach { (command, execute) -> assertEquals(RuntimeCommandPriority.NORMAL, command.priority); execute(command) }
        assertEquals(listOf("start:7:51", "stop:7:52"), calls.scoped); assertEquals(0, calls.generic)
    }
    @Test fun unknownActionsRetainTheirExistingGenericPolicy() {
        val calls = Calls()
        assertFalse(dispatchRouterOwnedRuntimeCommand("unknown", 51, calls.handlers, { error("not owned") }, { error("not owned") }))
        assertTrue(isFailClosedRuntimeServiceCommand("unknown")); assertTrue(calls.pending.isEmpty())
    }
}
