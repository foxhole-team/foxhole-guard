package com.foxhole.guard.router

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RouterPackageUpdateContractsTest {
    @Test fun moduleOnlyAvailabilityDoesNotRequireANewerBaseVersion() {
        val status = parseRouterUpdateStatus(UpdateDocuments.status() plusField ("availableVersion" to JsonPrimitive("1.0.0-r2")))
        val plan = parseRouterUpdatePlan(UpdateDocuments.plan(baseChange = false), status)
        assertEquals(plan.fromVersion, plan.toVersion)
        assertEquals(listOf("hysteria2"), plan.changes.map { it.id })
    }

    @Test fun baseReplacementRequiresReconnectAndInstalledStorageCannotChange() {
        val status = parseRouterUpdateStatus(UpdateDocuments.status())
        rejected { parseRouterUpdatePlan(UpdateDocuments.plan() plusField ("impact" to JsonPrimitive("KEEP_ACTIVE")), status) }
        val module = UpdateDocuments.plan(false)
        val changed = module["changes"]!!.jsonArray.single().jsonObject plusField ("storage" to JsonPrimitive("RAM"))
        rejected { parseRouterUpdatePlan(module plusField ("changes" to JsonArray(listOf(changed))),
            status.copy(availableVersion = status.currentVersion)) }
    }

    @Test fun unknownEnumsBadHashesDuplicateModulesAndAutomaticOwnJobsFailClosed() {
        val good = UpdateDocuments.status()
        listOf("availability" to JsonPrimitive("LATEST"), "routerId" to JsonPrimitive("router"),
            "installedModules" to JsonArray(List(2) { good["installedModules"]!!.jsonArray.single() }),
            "activeOwnJob" to (UpdateDocuments.ref() plusField ("automatic" to JsonPrimitive(true))))
            .forEach { field -> rejected { parseRouterUpdateStatus(good plusField field) } }
        rejected { routerUpdateFailure("update_future_failure") }
    }

    @Test fun manualCheckHasNoPlanAndAnApplyMustHaveOne() {
        assertNull(parseRouterUpdateJob(UpdateDocuments.job("CHECK"), UpdateDocuments.ROUTER).planId)
        rejected { parseRouterUpdateJob(UpdateDocuments.job() plusField ("planId" to JsonNull), UpdateDocuments.ROUTER) }
        rejected { parseRouterUpdateJob(UpdateDocuments.job("CHECK") plusField ("planId" to JsonPrimitive(UpdateDocuments.PLAN)), UpdateDocuments.ROUTER) }
    }

    @Test fun failureAndSuccessCannotContradictTheirProgress() {
        rejected { parseRouterUpdateJob(UpdateDocuments.job() plusField ("state" to JsonPrimitive("FAILED")), UpdateDocuments.ROUTER) }
        rejected { parseRouterUpdateJob(UpdateDocuments.job() plusField ("state" to JsonPrimitive("SUCCEEDED")), UpdateDocuments.ROUTER) }
    }

    @Test fun controlsStayDisabledForUnknownOutcomeOrAutomaticJob() {
        val status = parseRouterUpdateStatus(UpdateDocuments.status())
        val ready = RouterUpdateControlState(true, status, parseRouterUpdatePlan(UpdateDocuments.plan(), status))
        assertTrue(ready.canApply)
        assertFalse(ready.copy(pending = RouterUpdatePending(RouterUpdateOperation.APPLY, UpdateDocuments.PLAN, null)).canApply)
        assertFalse(ready.copy(reconnecting = true).canCheck)
        assertFalse(RouterUpdateControlState(supported = false).canCheck)
    }

    @Test fun updateWithAnotherSelectedProfileStillOffersOnlyTheActiveStop() {
        val snapshot = RouterControlSnapshot(1, JsonObject(emptyMap()), JsonArray(emptyList()), JsonArray(emptyList()),
            JsonArray(emptyList()), emptyList(), emptyList(), "server-000000000001", true)
        val state = RouterControlState(stage = RouterControlStage.CONNECTED,
            preferences = RouterControlPreferences("pair-test", enabled = true), snapshot = snapshot,
            selectedServerId = "server-000000000002", updates = RouterUpdateControlState(supported = true,
                job = parseRouterUpdateJob(UpdateDocuments.job(), UpdateDocuments.ROUTER)))
        assertFalse(state.controlsAvailable)
        assertTrue(state.canStopVpn)
        assertFalse(state.vpnButtonStarts)
        assertFalse(state.copy(stage = RouterControlStage.OFFLINE).canStopVpn)
        assertFalse(state.copy(snapshot = null).canStopVpn)
    }

    @Test fun legacyConnectionBlockIsActionableAndNotAnUnsupportedPlatform() {
        val value = UpdateDocuments.status()
        val auto = value["autoUpdate"]!!.jsonObject plusField ("suspendedReason" to JsonPrimitive("LEGACY_CONNECTION_ACTIVE"))
        val status = parseRouterUpdateStatus(value plusField ("autoUpdate" to auto))
        val plan = parseRouterUpdatePlan(UpdateDocuments.plan(), status.copy(autoUpdate = status.autoUpdate.copy(suspendedReason = null)))
        val state = RouterUpdateControlState(true, status, plan)
        val operational = parseRouterUpdateStatus(value plusField ("failureReason" to JsonPrimitive("LEGACY_CONNECTION_ACTIVE")))
        assertTrue(operational.supported)
        assertFalse(state.copy(status = operational).canApply)
        rejected { parseRouterUpdateStatus(value plusField ("availability" to JsonPrimitive("CURRENT")) plusField
            ("availableVersion" to value["currentVersion"]!!) plusField ("failureReason" to JsonPrimitive("LEGACY_CONNECTION_ACTIVE"))) }
        assertTrue(status.supported)
        assertTrue(state.requiresVpnStop)
        assertFalse(state.canApply)
        assertEquals(RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE, routerUpdateFailure("update_legacy_connection_active").reason)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("invalid update document accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }
}

internal infix fun JsonObject.plusField(field: Pair<String, JsonElement>) = JsonObject(this + field)

internal object UpdateDocuments {
    const val ROUTER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val PLAN = "update-plan-cccccccccccccccccccccccccccccccc"
    const val JOB = "update-job-dddddddddddddddddddddddddddddddd"
    fun capabilities() = Json.parseToJsonElement("""{"schema":1,"coreProtocols":[],"legacyProtocols":[],"inboundProtocols":[],
        "phoneRemoteProvisioning":false,"subscriptionSelection":false,"independentPhoneChoices":false,"phoneRegistration":true,"homeProof":false}""").jsonObject
    fun status(own: JsonObject? = null) = Json.parseToJsonElement("""{
      "schema":1,"supported":true,"routerId":"$ROUTER","revision":4,
      "currentVersion":"1.0.0-r2","availableVersion":"1.0.1-r1","availability":"AVAILABLE",
      "lastCheckedAtUnixMs":1000,"failureReason":null,"busy":false,
      "autoUpdate":{"enabled":false,"intervalSeconds":86400,"lastAttemptAtUnixMs":null,"nextCheckAtUnixMs":null,"suspendedReason":null},
      "installedModules":[{"id":"hysteria2","storage":"FLASH","version":"2.0.0-r1"}],
      "activeOwnJob":null,"lastOwnJob":null,"activeAutomaticJob":null
    }""").jsonObject.let { if (own == null) it else it plusField ("activeOwnJob" to own) }
    fun plan(baseChange: Boolean = true): JsonObject {
        val to = if (baseChange) "1.0.1-r1" else "1.0.0-r2"
        val changes = if (baseChange) """{"id":"foxhole","fromVersion":"1.0.0-r2","toVersion":"$to","storage":null}"""
            else """{"id":"hysteria2","fromVersion":"2.0.0-r1","toVersion":"2.0.1-r1","storage":"FLASH"}"""
        return Json.parseToJsonElement("""{"schema":1,"routerId":"$ROUTER","revision":4,"planId":"$PLAN",
          "planSha256":"${"b".repeat(64)}","fromVersion":"1.0.0-r2","toVersion":"$to","engineIds":["hysteria2"],
          "changes":[$changes],"impact":"RECONNECT_REQUIRED","confirmationRequired":true,"expiresAtUptimeMs":300000,"bootId":"boot-test",
          "budget":{"downloadBytes":1,"stagingBytes":2,"flashRequiredBytes":2,"flashAvailableBytes":100,"ramRequiredBytes":0,"ramAvailableBytes":100,"reservedBytes":10}}""").jsonObject
    }
    fun job(operation: String = "APPLY", state: String = "INSTALLING") = Json.parseToJsonElement("""{
      "schema":1,"routerId":"$ROUTER","jobId":"$JOB","planId":${if (operation == "CHECK") "null" else "\"$PLAN\""},
      "operation":"$operation","automatic":false,"state":"$state","step":"${if (state == "SUCCEEDED") "COMPLETED" else "INSTALLING_PACKAGE"}",
      "progressPercent":${if (state == "SUCCEEDED") 100 else 40},"revision":4,"failureReason":null,"previousConnectionRestored":null}""").jsonObject
    fun ref(operation: String = "APPLY") = JsonObject(job(operation).filterKeys {
        it in setOf("jobId", "planId", "operation", "automatic", "state", "step", "progressPercent") })
}
