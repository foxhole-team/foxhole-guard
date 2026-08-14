package com.foxhole.guard.guardian

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardHostMonitorTest {
    @Test
    fun `repeated attach and detach are idempotent`() {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        var heartbeats = 0
        val monitor =
            monitor(
                active = { true },
                onStarted = started::add,
                onStopped = { host, _ -> stopped += host },
                onHeartbeat = { heartbeats++ },
            )

        monitor.attach("vpn-service")
        monitor.attach("vpn-service")

        assertEquals(listOf("vpn-service"), started)
        assertTrue(monitor.hasHosts())
        assertTrue(monitor.reconcile())
        assertEquals(1, heartbeats)

        monitor.detach("vpn-service", destroyed = true)
        monitor.detach("vpn-service", destroyed = true)

        assertEquals(listOf("vpn-service"), stopped)
        assertFalse(monitor.hasHosts())
        assertFalse(monitor.reconcile())
    }

    @Test
    fun `disable cancels heartbeat but retains a live runtime host for re-enable`() {
        var active = true
        var heartbeats = 0
        val started = mutableListOf<String>()
        val monitor =
            monitor(
                active = { active },
                onStarted = started::add,
                onHeartbeat = { heartbeats++ },
            )
        monitor.attach("vpn-service")
        assertEquals(1, heartbeats)
        assertEquals(listOf("vpn-service"), started)

        active = false
        assertFalse(monitor.reconcile())
        assertTrue(monitor.hasHosts())

        active = true
        assertTrue(monitor.reconcile())
        assertEquals(2, heartbeats)
        assertEquals(listOf("vpn-service", "vpn-service"), started)

        assertTrue(monitor.reconcile())
        assertEquals(listOf("vpn-service", "vpn-service"), started)
    }

    @Test
    fun `runtime attached while disabled is announced immediately after enable`() {
        var active = false
        var heartbeats = 0
        val started = mutableListOf<String>()
        val monitor =
            monitor(
                active = { active },
                onStarted = started::add,
                onHeartbeat = { heartbeats++ },
            )

        monitor.attach("vpn-service")
        assertTrue(monitor.hasHosts())
        assertFalse(monitor.reconcile())
        assertTrue(started.isEmpty())
        assertEquals(0, heartbeats)

        active = true
        assertTrue(monitor.reconcile())
        assertEquals(listOf("vpn-service"), started)
        assertEquals(1, heartbeats)
    }

    @Test
    fun `dedicated service does not count itself as a competing runtime host`() {
        val monitor = monitor(active = { true })

        monitor.attach(FoxholeGuardService.HOST_NAME)

        assertTrue(monitor.hasHosts())
        assertFalse(monitor.hasHostOtherThan(FoxholeGuardService.HOST_NAME))
        monitor.attach("vpn-service")
        assertTrue(monitor.hasHostOtherThan(FoxholeGuardService.HOST_NAME))
    }

    private fun monitor(
        active: () -> Boolean,
        onStarted: (String) -> Unit = {},
        onStopped: (String, Boolean) -> Unit = { _, _ -> },
        onHeartbeat: () -> Unit = {},
    ): GuardHostMonitor =
        GuardHostMonitor(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            monitoringActive = active,
            onHostStarted = onStarted,
            onHostStopped = onStopped,
            onHeartbeat = onHeartbeat,
            heartbeatIntervalMs = 60_000L,
        )
}
