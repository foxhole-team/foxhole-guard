package com.foxhole.guard.router

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterPackageUpdateCoordinatorTest {
    private class Fixture {
        val http = EngineApiFixture()
        var lostApply = false
        var applyCount = 0
        var own: JsonObject? = null
        var statusTransform: (JsonObject) -> JsonObject = { it }
        var auto = false
        var activeProfile: String? = null
        var profiles = false
        var routingRevision = 1
        var stopCalls = 0
        var startCalls = 0
        var lostStop = false
        var statusFailures = 0
        private fun state() = buildJsonObject {
            put("schema", 1); put("revision", routingRevision)
            put("settings", buildJsonObject { put("localRules", true); put("localScope", "all") })
            listOf("countries", "devices", "sites", "clients").forEach { put(it, JsonArray(emptyList())) }
            put("servers", JsonArray(if (!profiles) emptyList() else listOf("server-000000000001", "server-000000000002").map {
                buildJsonObject { put("id", it); put("name", it); put("protocol", "hysteria2"); put("endpoint", "vpn.example.invalid:443") }
            }))
            put("runtime", buildJsonObject { put("desiredServerId", activeProfile?.let(::JsonPrimitive) ?: JsonNull); put("connected", activeProfile != null) })
        }
        private fun updateStatus(): JsonObject {
            var value = UpdateDocuments.status()
            own?.let { ref ->
                value = if (ref["state"]!!.jsonPrimitive.content in setOf("SUCCEEDED", "FAILED", "RECOVERY_REQUIRED"))
                    value plusField ("lastOwnJob" to ref) else value plusField ("activeOwnJob" to ref)
            }
            value = value plusField ("autoUpdate" to (value["autoUpdate"]!!.jsonObject plusField ("enabled" to JsonPrimitive(auto))))
            return statusTransform(value)
        }
        fun complete() {
            own = UpdateDocuments.ref() plusField ("state" to JsonPrimitive("SUCCEEDED"))
            own = own!! plusField ("step" to JsonPrimitive("COMPLETED"))
            own = own!! plusField ("progressPercent" to JsonPrimitive(100))
            statusTransform = { it plusField ("availability" to JsonPrimitive("CURRENT")) plusField
                ("currentVersion" to JsonPrimitive("1.0.1-r1")) plusField ("availableVersion" to JsonPrimitive("1.0.1-r1")) }
        }
        init {
            val old = http.reply
            http.reply = { action, payload -> when (action) {
                "phone.capabilities" -> capabilities()
                "telemetry" -> buildJsonObject { put("sample", buildJsonObject { put("wan", buildJsonObject {}) }) }
                "state" -> state()
                "server.stop" -> { stopCalls++; activeProfile = null; routingRevision++; state() }
                "server.start" -> { startCalls++; activeProfile = payload["id"]!!.jsonPrimitive.content; state() }
                "updates.status" -> updateStatus()
                "updates.plan" -> UpdateDocuments.plan()
                "updates.job" -> UpdateDocuments.job(state = own?.get("state")?.jsonPrimitive?.content ?: "INSTALLING")
                "updates.check" -> UpdateDocuments.job("CHECK", "CHECKING")
                "updates.settings" -> {
                    auto = payload["autoUpdateEnabled"]!!.jsonPrimitive.boolean
                    val value = UpdateDocuments.status()
                    value plusField ("autoUpdate" to (value["autoUpdate"]!!.jsonObject plusField ("enabled" to JsonPrimitive(auto))))
                }
                else -> old(action, payload)
            } }
        }
        suspend fun control(dispatcher: CoroutineDispatcher): RouterControlCoordinator {
            val store = MemoryRouterPairingStore()
            val pair = RouterPairing(http.identity, "pair-" + "c".repeat(32), ByteArray(32) { 3 }, "Synthetic phone", true)
            store.save(pair); store.save(RouterControlPreferences(pair.id, enabled = true, countryCode = "NL", countryAuto = true))
            val transport = object : RouterWizardTransport {
                override fun gateway() = "192.168.1.1"
                override suspend fun inspectIdentity(endpoint: RouterEndpoint) = http.identity
                override fun api(identity: RouterTlsIdentity) = http.api(identity)
            }
            return RouterControlCoordinator(transport, store, store, RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = dispatcher,
                mobileCall = { api, action, payload ->
                    if (action == RouterMobileAction.UPDATES_APPLY) {
                        applyCount++
                        own = UpdateDocuments.ref()
                        if (lostApply) throw IOException("synthetic lost acknowledgement")
                        UpdateDocuments.job()
                    } else if (action == RouterMobileAction.PROFILE_STOP && lostStop) {
                        stopCalls++; activeProfile = null; routingRevision++
                        throw IOException("synthetic lost STOP acknowledgement")
                    } else if (action == RouterMobileAction.UPDATES_STATUS && statusFailures > 0) {
                        statusFailures--; throw IOException("synthetic temporary rpcd restart")
                    } else api.mobileRequest(action, payload)
                }), copyProfile = {}).also { it.open(); it.refreshUpdates() }
        }
    }

    @Test fun lostApplyAcknowledgementReattachesByPlanWithoutReplayOrCountryWrites() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        val plan = requireNotNull(c.state.value.updates.plan)
        f.lostApply = true
        c.applyUpdate(plan.planId, plan.planSha256)
        assertNotNull(c.state.value.updates.pending)
        assertEquals(RouterControlStage.OFFLINE, c.state.value.stage)
        f.http.actions.clear()
        c.refreshUpdates()
        assertEquals(UpdateDocuments.JOB, c.state.value.updates.job?.jobId)
        assertNull(c.state.value.updates.pending)
        assertFalse(f.http.actions.any { it == "telemetry" || it == "settings" || it == "server.start" || it == "server.stop" })
        c.applyUpdate(plan.planId, plan.planSha256)
        assertEquals(1, f.applyCount)
        c.close()
    }

    @Test fun queuedDuplicateConfirmationCannotClearAnUncertainAcknowledgement() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        val plan = requireNotNull(c.state.value.updates.plan)
        f.lostApply = true
        c.applyUpdate(plan.planId, plan.planSha256)
        c.applyUpdate(plan.planId, plan.planSha256)
        assertNotNull(c.state.value.updates.pending)
        assertFalse(c.state.value.updates.canCheck)
        assertFalse(c.state.value.updates.canApply)
        assertEquals(1, f.applyCount)
        c.close()
    }

    @Test fun anOldLastCheckCannotResolveTheUncertainApplyOrEnableASecondApply() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        val plan = requireNotNull(c.state.value.updates.plan)
        f.lostApply = true; c.applyUpdate(plan.planId, plan.planSha256)
        f.own = null
        f.statusTransform = { it plusField ("lastOwnJob" to UpdateDocuments.ref("CHECK")) }
        c.refreshUpdates()
        assertNotNull(c.state.value.updates.pending)
        assertFalse(c.state.value.updates.canApply)
        assertFalse(f.http.actions.contains("updates.job"))
        assertEquals(1, f.applyCount)
        c.close()
    }

    @Test fun aSurvivingJobIsDiscoveredBeforeCountryAutoWritesOnAColdOpen() = runTest {
        val f = Fixture(); f.own = UpdateDocuments.ref()
        val c = f.control(StandardTestDispatcher(testScheduler))
        assertTrue(c.state.value.updates.job?.running == true)
        assertFalse(f.http.actions.any { it == "telemetry" || it == "settings" })
        assertEquals(0, f.applyCount)
        c.close()
    }

    @Test fun aStaleSessionRefreshesTheSamePairWithStatusOnly() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        val old = f.http.reply
        f.http.reply = { a, p -> if (a == "updates.status") buildJsonObject { put("_foxhole_error", "update_stale_session") } else old(a,p) }
        c.refreshUpdates()
        assertEquals(RouterControlStage.OFFLINE, c.state.value.stage)
        f.http.reply = old; f.http.actions.clear()
        c.refreshUpdates()
        assertEquals(RouterControlStage.CONNECTED, c.state.value.stage)
        assertTrue(f.http.actions.contains("pair.unlock"))
        assertFalse(f.http.actions.any { it == "telemetry" || it == "settings" || it == "updates.apply" || it == "updates.check" })
        c.close()
    }

    @Test fun authoritativeAutoReadbackIsTheOnlySettingAndNoPhonePreferenceIsAdded() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        c.setUpdateAuto(true)
        assertTrue(c.state.value.updates.status!!.autoUpdate.enabled)
        assertEquals(1, f.http.actions.count { it == "updates.settings" })
        assertEquals(setOf("token", "expectedRevision", "autoUpdateEnabled"),
            f.http.payloads[f.http.actions.indexOf("updates.settings")].keys)
        c.close()
    }

    @Test fun lateStatusAfterCloseCannotPublishToTheNextRouterGeneration() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        f.http.hold = "updates.status"
        val pending = launch { c.refreshUpdates() }; runCurrent()
        c.close(); f.http.delayed?.invoke(); runCurrent(); pending.join()
        assertNull(c.state.value.updates.status)
        assertTrue(f.http.cancellations > 0)
    }

    @Test fun staleRevisionAndForeignRouterCannotBecomeAnApplyPlan() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        f.statusTransform = { it plusField ("routerId" to JsonPrimitive("f".repeat(64))) }
        c.refreshUpdates()
        assertNull(c.state.value.updates.plan)
        assertEquals(RouterUpdateFailureReason.INVALID_REQUEST, c.state.value.updates.failure)
        assertEquals(0, f.applyCount)
        c.close()
    }

    @Test fun absentOptionalCapabilityMeansUnsupportedWithoutAnUpdateRequest() = runTest {
        val f = Fixture()
        val original = f.http.reply
        f.http.reply = { a, p -> if (a == "phone.capabilities") JsonObject(capabilities().filterKeys { it != "packageUpdates" }) else original(a,p) }
        val c = f.control(StandardTestDispatcher(testScheduler))
        assertEquals(false, c.state.value.updates.supported)
        assertFalse(f.http.actions.any { it.startsWith("updates.") })
        assertEquals(RouterControlStage.CONNECTED, c.state.value.stage)
        c.close()
    }

    @Test fun stopDuringAnUpdatePersistsOffAndCannotSwitchToTheOtherSelectedProfile() = runTest {
        val f = Fixture(); f.profiles = true; f.activeProfile = "server-000000000001"
        val c = f.control(StandardTestDispatcher(testScheduler))
        c.selectServer("server-000000000002")
        val plan = requireNotNull(c.state.value.updates.plan); c.applyUpdate(plan.planId, plan.planSha256)
        assertFalse(c.state.value.vpnButtonStarts)
        assertTrue(c.state.value.canStopVpn)
        c.setVpn(true)
        assertEquals(0, f.startCalls)
        c.setVpn(false)
        assertEquals(1, f.stopCalls)
        assertNull(c.state.value.snapshot?.desiredServerId)
        assertNull(c.state.value.updates.plan)
        assertTrue(c.state.value.updates.job?.running == true)
        f.complete(); c.refreshUpdates(statusOnly = true)
        assertEquals(RouterEngineJobState.SUCCEEDED, c.state.value.updates.job?.progress?.state)
        assertNull(c.state.value.snapshot?.desiredServerId)
        assertEquals(0, f.startCalls)
        assertEquals(1, f.applyCount)
        c.close()
    }

    @Test fun lostStopAcknowledgementNeverReplaysStopOrStartsAProfile() = runTest {
        val f = Fixture(); f.profiles = true; f.activeProfile = "server-000000000001"
        val c = f.control(StandardTestDispatcher(testScheduler))
        val plan = requireNotNull(c.state.value.updates.plan); c.applyUpdate(plan.planId, plan.planSha256)
        f.lostStop = true; c.setVpn(false)
        assertEquals(RouterControlStage.OFFLINE, c.state.value.stage)
        c.refreshUpdates(statusOnly = true)
        assertEquals(1, f.stopCalls)
        assertEquals(0, f.startCalls)
        assertEquals(1, f.applyCount)
        c.close()
    }

    @Test fun finiteForegroundRecoveryReattachesTheSameOwnJobAfterTemporaryNetworkLoss() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        val plan = requireNotNull(c.state.value.updates.plan); c.applyUpdate(plan.planId, plan.planSha256)
        val epoch = c.state.value.updates.pollEpoch
        val budget = RouterUpdatePollBudget(testScheduler.currentTime, c.state.value.selectedRouterId)
        f.statusFailures = 1; c.refreshUpdates(statusOnly = true)
        assertTrue(c.state.value.updates.unreachable)
        f.complete(); f.http.actions.clear()
        val step = requireNotNull(budget.next(c.state.value, testScheduler.currentTime))
        assertTrue(step.statusOnly); delay(step.delayMs)
        c.refreshUpdates(statusOnly = step.statusOnly)
        assertEquals(UpdateDocuments.JOB, c.state.value.updates.job?.jobId)
        assertEquals(RouterEngineJobState.SUCCEEDED, c.state.value.updates.job?.progress?.state)
        assertEquals(epoch, c.state.value.updates.pollEpoch)
        assertNull(budget.next(c.state.value, testScheduler.currentTime))
        assertFalse(f.http.actions.any { it in setOf("settings", "telemetry", "updates.apply", "updates.check", "updates.plan", "server.start") })
        assertEquals(1, f.applyCount)
        c.close()
    }

    @Test fun authenticationFailureEndsForegroundRecoveryWithoutAnotherAttempt() = runTest {
        val f = Fixture(); val c = f.control(StandardTestDispatcher(testScheduler))
        val plan = requireNotNull(c.state.value.updates.plan); c.applyUpdate(plan.planId, plan.planSha256)
        val old = f.http.reply
        f.http.reply = { a,p -> if (a == "updates.status") buildJsonObject { put("_foxhole_error", "update_authentication_failed") } else old(a,p) }
        c.refreshUpdates(statusOnly = true)
        val budget = RouterUpdatePollBudget(testScheduler.currentTime, c.state.value.selectedRouterId)
        assertNull(budget.next(c.state.value, testScheduler.currentTime))
        assertEquals(RouterControlStage.PAIRING_REQUIRED, c.state.value.stage)
        assertEquals(1, f.applyCount)
        c.close()
    }

    companion object {
        private fun capabilities() = Json.parseToJsonElement("""{"schema":1,"coreProtocols":["hysteria2"],"legacyProtocols":[],"inboundProtocols":[],
            "phoneRemoteProvisioning":false,"subscriptionSelection":false,"independentPhoneChoices":false,"phoneRegistration":true,"homeProof":false,"packageUpdates":true}""").jsonObject
    }
}
