package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterEngineControlCoordinatorTest {
    private suspend fun control(fixture: EngineApiFixture, dispatcher: kotlinx.coroutines.CoroutineDispatcher): RouterControlCoordinator {
        val store = MemoryRouterPairingStore()
        val pair = RouterPairing(fixture.identity, "pair-" + "c".repeat(32), ByteArray(32) { 3 }, "Synthetic phone", true)
        store.save(pair); store.save(RouterControlPreferences(pair.id, enabled = true, countryCode = "NL", countryAuto = false))
        val transport = object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint) = fixture.identity
            override fun api(identity: RouterTlsIdentity) = fixture.api(identity)
        }
        val control = RouterControlCoordinator(transport, store, store,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = dispatcher), copyProfile = {})
        control.open()
        assertEquals(RouterControlStage.CONNECTED, control.state.value.stage)
        return control
    }

    @Test fun statusAndPlanningNeverApplyOrPromptForAnotherPin() = runTest {
        val f = EngineApiFixture(); val c = control(f, StandardTestDispatcher(testScheduler))
        c.refreshEngines(); c.planEngines(RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        assertNotNull(c.state.value.engines.plan)
        assertEquals(0, f.actions.count { it == "engines.apply" || it == "unlock" || it == "pin.change" || it == "server.stop" })
        c.close()
    }

    @Test fun oneExactConfirmationConsumesThePlanAndCannotApplyTwice() = runTest {
        val f = EngineApiFixture(); val c = control(f, StandardTestDispatcher(testScheduler))
        c.refreshEngines(); c.planEngines(RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        val plan = requireNotNull(c.state.value.engines.plan)
        c.applyEnginePlan(plan.planId, plan.planSha256)
        assertNotNull(c.state.value.engines.job)
        c.applyEnginePlan(plan.planId, plan.planSha256)
        assertEquals(1, f.actions.count { it == "engines.apply" })
        assertEquals(RouterEngineFailureReason.STALE_PLAN, c.state.value.engines.failure)
        c.close()
    }

    @Test fun unreleasedProtocolsAndActiveRemovalAreRefusedBeforePlanNetworkCalls() = runTest {
        val f = EngineApiFixture(); val c = control(f, StandardTestDispatcher(testScheduler)); c.refreshEngines()
        c.planEngines(RouterEngineOperation.ADD, setOf(RouterInstallProtocol.TUIC))
        assertEquals(RouterEngineFailureReason.NOT_RELEASED, c.state.value.engines.failure)
        c.planEngines(RouterEngineOperation.REMOVE, setOf(RouterInstallProtocol.HYSTERIA2))
        assertEquals(RouterEngineFailureReason.IN_USE, c.state.value.engines.failure)
        assertFalse(f.actions.contains("engines.plan"))
        c.close()
    }

    @Test fun closingCancelsDelayedStatusAndCannotPublishItsLateResponse() = runTest {
        val f = EngineApiFixture(); val c = control(f, StandardTestDispatcher(testScheduler)); f.hold = "engines.status"
        val request = launch { c.refreshEngines() }; runCurrent()
        c.close(); f.delayed?.invoke(); runCurrent(); request.join()
        assertNull(c.state.value.engines.status)
        assertTrue(f.cancellations > 0)
    }

    @Test fun ownJobResumesAfterReopeningWithoutAnotherApply() = runTest {
        val f = EngineApiFixture(); val base = f.reply
        f.reply = { a, p -> when (a) {
            "engines.status" -> EngineTestDocuments.status(ownJob = EngineTestDocuments.job())
            "engines.job" -> EngineTestDocuments.job()
            else -> base(a, p)
        } }
        val c = control(f, StandardTestDispatcher(testScheduler)); c.refreshEngines()
        assertEquals("job-test", c.state.value.engines.job?.jobId)
        assertFalse(f.actions.contains("engines.apply"))
        c.close(); c.open(); c.refreshEngines()
        assertFalse(f.actions.contains("engines.apply"))
        c.close()
    }

    @Test fun aTerminalOwnFailureIsRecoveredAfterAProcessRestart() = runTest {
        val f = EngineApiFixture(); val base = f.reply
        val failed = EngineTestDocuments.job("FAILED", "RECONNECT_FAILED")
        f.reply = { action, payload -> when (action) {
            "engines.status" -> JsonObject(EngineTestDocuments.status(ownJob = failed) + mapOf(
                "busy" to JsonPrimitive(false), "activeOwnJob" to kotlinx.serialization.json.JsonNull))
            "engines.job" -> failed
            else -> base(action, payload)
        } }
        val c = control(f, StandardTestDispatcher(testScheduler)); c.refreshEngines()
        assertEquals(RouterEngineJobState.FAILED, c.state.value.engines.job?.state)
        assertEquals(RouterEngineFailureReason.RECONNECT_FAILED, c.state.value.engines.failure)
        assertFalse(c.state.value.engines.running)
        assertFalse(f.actions.contains("engines.apply"))
        c.close()
    }

    @Test fun anAbsentEnginesApiPreservesExistingRouterControl() = runTest {
        val f = EngineApiFixture(); val base = f.reply
        f.reply = { a, p -> if (a == "engines.status") buildJsonObject { put("_foxhole_error", "unknown_action") } else base(a, p) }
        val c = control(f, StandardTestDispatcher(testScheduler)); c.refreshEngines()
        assertEquals(false, c.state.value.engines.supported)
        assertEquals(RouterControlStage.CONNECTED, c.state.value.stage)
        assertNull(c.state.value.error)
        assertNotNull(c.state.value.snapshot)
        c.close()
    }

    @Test fun malformedApplyCannotReuseAPreviousSuccessfulJob() = runTest {
        val f = EngineApiFixture(); val c = control(f, StandardTestDispatcher(testScheduler)); c.refreshEngines()
        val base = f.reply
        f.reply = { a, p -> if (a == "engines.apply") EngineTestDocuments.job("SUCCEEDED") else base(a, p) }
        c.planEngines(RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        val first = requireNotNull(c.state.value.engines.plan)
        c.applyEnginePlan(first.planId, first.planSha256)
        assertEquals(RouterEngineJobState.SUCCEEDED, c.state.value.engines.job?.state)
        f.reply = { a, p -> when (a) {
            "engines.plan" -> EngineTestDocuments.plan("UPDATE", "hysteria2", "RECONNECT_REQUIRED")
            "engines.apply" -> JsonObject(mapOf("schema" to JsonPrimitive(1)))
            else -> base(a, p)
        } }
        c.planEngines(RouterEngineOperation.UPDATE, setOf(RouterInstallProtocol.HYSTERIA2))
        val plan = requireNotNull(c.state.value.engines.plan)
        c.applyEnginePlan(plan.planId, plan.planSha256)
        assertNull(c.state.value.engines.job)
        assertNull(c.state.value.engines.plan)
        assertNotNull(c.state.value.error)
        c.close()
    }

    @Test fun aFinishedJobCannotPublishSuccessFromAnotherRoutersStatus() = runTest {
        val f = EngineApiFixture(); val c = control(f, StandardTestDispatcher(testScheduler)); c.refreshEngines()
        c.planEngines(RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        val plan = requireNotNull(c.state.value.engines.plan)
        c.applyEnginePlan(plan.planId, plan.planSha256)
        assertTrue(c.state.value.engines.job?.running == true)
        val base = f.reply
        f.reply = { action, payload -> if (action == "engines.status")
            JsonObject(EngineTestDocuments.status() + ("routerId" to JsonPrimitive("foreign-router")))
            else base(action, payload) }
        c.refreshEngineJob("job-test")
        assertTrue(c.state.value.engines.job?.running == true)
        assertNotNull(c.state.value.error)
        assertEquals("router-test", c.state.value.engines.status?.routerId)
        c.close()
    }
}
