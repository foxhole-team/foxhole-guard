package com.foxhole.guard.router

import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GuardRouterSocketOwnerRegistryTest {
    private class State {
        val identity = Any()
        var runtime = Any()
        var tunnelOwner = Any()
        var serviceGeneration = 1L
        var generation = 2L
        var sequence = 3L
        var commandOwner = 4L
        var allowed = true
        fun capture() = if (allowed) GuardRouterSocketOwnerSnapshot(runtime, tunnelOwner, serviceGeneration, generation, sequence, commandOwner) else null
    }
    private fun protected(change: (GuardRouterSocketOwnerRegistry, State) -> Unit = { _, _ -> }): Boolean {
        val registry = GuardRouterSocketOwnerRegistry(); val state = State()
        registry.register(state.identity, state::capture) { change(registry, state); true }
        return Socket().use(registry::protect)
    }
    @Test fun stableLiveOwnerProtects() { assertTrue(protected()) }
    @Test fun noOwnerRefuses() { assertFalse(Socket().use(GuardRouterSocketOwnerRegistry()::protect)) }
    @Test fun externalVpnOrPermissionLossBeforeCaptureNeverCallsNativeProtect() {
        val registry = GuardRouterSocketOwnerRegistry(); val state = State().also { it.allowed = false }; var called = false
        registry.register(state.identity, state::capture) { called = true; true }
        assertFalse(Socket().use(registry::protect)); assertFalse(called)
    }
    @Test fun revokeDuringProtectRefuses() { assertFalse(protected { registry, state -> registry.invalidate(state.identity) }) }
    @Test fun permissionLossDuringProtectRefuses() { assertFalse(protected { _, state -> state.allowed = false }) }
    @Test fun runtimeReplacedDuringProtectRefuses() { assertFalse(protected { _, state -> state.runtime = Any() }) }
    @Test fun tunnelSessionReplacedDuringProtectRefuses() { assertFalse(protected { _, state -> state.tunnelOwner = Any() }) }
    @Test fun serviceGenerationChangedDuringProtectRefuses() { assertFalse(protected { _, state -> state.serviceGeneration++ }) }
    @Test fun runtimeGenerationChangedDuringProtectRefuses() { assertFalse(protected { _, state -> state.generation++ }) }
    @Test fun queuedCommandDuringProtectRefuses() { assertFalse(protected { _, state -> state.sequence++ }) }
    @Test fun commandOwnerChangedDuringProtectRefuses() { assertFalse(protected { _, state -> state.commandOwner++ }) }
    @Test fun replacingRegistrationRefusesEvenWithIdenticalSnapshot() {
        assertFalse(protected { registry, state -> registry.register(state.identity, state::capture) { true } })
    }
    @Test fun oldDestroyDoesNotClearNewOwner() {
        val registry = GuardRouterSocketOwnerRegistry(); val old = State(); val current = State()
        registry.register(old.identity, old::capture) { true }; registry.register(current.identity, current::capture) { true }
        registry.invalidate(old.identity); assertTrue(Socket().use(registry::protect))
    }
    @Test fun nativeProtectFailureRefuses() {
        val registry = GuardRouterSocketOwnerRegistry(); val state = State(); registry.register(state.identity, state::capture) { false }
        assertFalse(Socket().use(registry::protect))
    }
    private fun refuseDuringBind(change: (GuardRouterSocketOwnerRegistry, State) -> Unit) {
        val registry = GuardRouterSocketOwnerRegistry(); val state = State(); val socket = Socket()
        registry.register(state.identity, state::capture) { true }
        val protection = object : RouterSocketProtection {
            override fun protect(socket: Socket) = registry.protect(socket)
            override fun isCurrent(socket: Socket) = registry.isCurrent(socket)
        }
        assertThrows(RouterVpnSocketUnavailable::class.java) {
            createRouterWifiSocket({ true }, protection, { change(registry, state) }, { socket })
        }
        assertTrue(socket.isClosed)
    }
    @Test fun revokeDuringPhysicalBindClosesBeforeTls() { refuseDuringBind { registry, state -> registry.invalidate(state.identity) } }
    @Test fun foreignVpnPermissionChangeDuringPhysicalBindClosesBeforeTls() { refuseDuringBind { _, state -> state.allowed = false } }
    @Test fun generationChangeDuringPhysicalBindClosesBeforeTls() { refuseDuringBind { _, state -> state.generation++ } }
}
