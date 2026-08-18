package com.foxhole.core.runtime

import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class KillSwitchTriggerSetTest {
    @Test
    fun `an unarmed switch never cuts anything`() {
        FailClosedEvent.entries.forEach { event ->
            assertNull(
                "an unarmed kill switch must leave every event exactly as it was: $event",
                killSwitchRevokeTarget(armed = false, event = event),
            )
        }
    }

    @Test
    fun `losing the tunnel cuts every flow`() {
        assertEquals(
            RevokeTarget.All,
            killSwitchRevokeTarget(armed = true, event = FailClosedEvent.TUNNEL_LOST),
        )
    }

    @Test
    fun `teardown cuts every flow so none outlives the tunnel`() {
        assertEquals(
            RevokeTarget.All,
            killSwitchRevokeTarget(armed = true, event = FailClosedEvent.RUNTIME_STOPPING),
        )
    }

    @Test
    fun `a refused policy reload cuts nothing`() {
        assertNull(killSwitchRevokeTarget(armed = true, event = FailClosedEvent.POLICY_RELOAD_REFUSED))
    }

    @Test
    fun `the tunnel coming up and running cut nothing`() {
        assertNull(killSwitchRevokeTarget(armed = true, event = FailClosedEvent.TUNNEL_STARTING))
        assertNull(killSwitchRevokeTarget(armed = true, event = FailClosedEvent.TUNNEL_UP))
    }

    @Test
    fun `every event has a recorded decision`() {
        val cutting = FailClosedEvent.entries.filter { killSwitchRevokeTarget(true, it) != null }

        assertEquals(
            setOf(FailClosedEvent.TUNNEL_LOST, FailClosedEvent.RUNTIME_STOPPING),
            cutting.toSet(),
        )
    }

    @Test
    fun `no event can produce anything but a cut`() {
        FailClosedEvent.entries.forEach { event ->
            val target = killSwitchRevokeTarget(armed = true, event = event)
            assertTrue(
                "only RevokeTarget.All is ever armed by the kill switch, got $target for $event",
                target == null || target == RevokeTarget.All,
            )
        }
    }

    @Test
    fun `cutting everything is the all target on the wire`() {
        assertEquals("""{"kind":"all"}""", RevokeTarget.All.toTargetJson())
    }

    @Test
    fun `an armed teardown reaches the runtime as a single cut and nothing else`() {
        val runtime = RecordingRuntime()

        val outcome = runtime.applyKillSwitch(armed = true, event = FailClosedEvent.RUNTIME_STOPPING)

        assertEquals(RevokeOutcome.Revoked(3), outcome)
        assertEquals(listOf("revoke:all"), runtime.calls)
    }

    @Test
    fun `an armed tunnel loss reaches the runtime as a single cut`() {
        val runtime = RecordingRuntime()

        runtime.applyKillSwitch(armed = true, event = FailClosedEvent.TUNNEL_LOST)

        assertEquals(listOf("revoke:all"), runtime.calls)
    }

    @Test
    fun `an unarmed switch never touches the runtime`() {
        val runtime = RecordingRuntime()

        FailClosedEvent.entries.forEach { event ->
            assertNull(runtime.applyKillSwitch(armed = false, event = event))
        }

        assertTrue("an unarmed switch made native calls: " + runtime.calls, runtime.calls.isEmpty())
    }

    @Test
    fun `an armed switch leaves the runtime alone on the events that cut nothing`() {
        val runtime = RecordingRuntime()

        listOf(
            FailClosedEvent.TUNNEL_STARTING,
            FailClosedEvent.TUNNEL_UP,
            FailClosedEvent.POLICY_RELOAD_REFUSED,
        ).forEach { event ->
            assertNull(runtime.applyKillSwitch(armed = true, event = event))
        }

        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `a cut after the runtime is gone degrades to not running`() {
        assertEquals(
            RevokeOutcome.NotRunning,
            StoppedRuntime().applyKillSwitch(armed = true, event = FailClosedEvent.RUNTIME_STOPPING),
        )
    }

    @Test
    fun `an armed hold carries kill_switch into the policy`() {
        assertTrue(trafficOf(killSwitch = true).getValue("kill_switch").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a session the app starts leaves the policy flag false`() {
        assertFalse(trafficOf(killSwitch = false).getValue("kill_switch").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the policy flag defaults to false when the caller omits it`() {
        val traffic =
            FoxCorePolicyTranslator
                .translate(
                    root = minimalRoot(),
                    primaryIsPacketTunnel = false,
                    overlays = emptySet(),
                    expectedRevision = null,
                ).document
                .getValue("traffic")
                .jsonObject

        assertFalse(traffic.getValue("kill_switch").jsonPrimitive.content.toBoolean())
    }

    private fun trafficOf(killSwitch: Boolean) =
        FoxCorePolicyTranslator
            .translate(
                root = minimalRoot(),
                primaryIsPacketTunnel = false,
                overlays = emptySet(),
                expectedRevision = null,
                killSwitch = killSwitch,
            ).document
            .getValue("traffic")
            .jsonObject

    private fun minimalRoot(): JsonObject =
        buildJsonObject {
            put("route", buildJsonObject {})
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("tag", DNS_REMOTE_TAG)
                                    put("type", "https")
                                    put("server", "1.1.1.1")
                                    put("server_port", 443)
                                    put("path", "/dns-query")
                                    put("detour", "proxy")
                                },
                            ),
                        ),
                    )
                    put("final", DNS_REMOTE_TAG)
                },
            )
        }

    private class RecordingRuntime : FoxholeRuntime {
        val calls = mutableListOf<String>()

        override suspend fun start(
            session: VpnSession,
            host: RuntimeServiceHost,
        ): Result<Unit> {
            calls += "start"
            return Result.success(Unit)
        }

        override suspend fun reload(
            session: VpnSession,
            host: RuntimeServiceHost,
        ): Result<Unit> {
            calls += "reload"
            return Result.success(Unit)
        }

        override suspend fun quiesceForInterfaceHandover(): Boolean {
            calls += "quiesce"
            return true
        }

        override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult {
            calls += "stop"
            return RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = false,
                elapsedMs = 0L,
            )
        }

        override fun revokeFlows(target: RevokeTarget): RevokeOutcome {
            calls += "revoke:" + target.toTargetJson().substringAfter(":\"").removeSuffix("\"}")
            return RevokeOutcome.Revoked(3)
        }
    }

    private class StoppedRuntime : FoxholeRuntime {
        override suspend fun start(
            session: VpnSession,
            host: RuntimeServiceHost,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun reload(
            session: VpnSession,
            host: RuntimeServiceHost,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun quiesceForInterfaceHandover(): Boolean = true

        override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
            RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = false,
                elapsedMs = 0L,
            )
    }
}
