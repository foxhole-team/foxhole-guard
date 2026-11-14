package com.foxhole.guard.router

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RouterVpnServerContractTest {
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun plan(action: String = "reuse", bytes: String = "0", available: Boolean = true) = obj("""{
        "schema":1,"action":"$action","available":$available,"revision":17,"planSha256":"${"a".repeat(64)}",
        "downloadBytes":$bytes,"component":${if (available) """{"protocol":"hysteria2","kind":"verified-managed-server","version":"2.9.0-r1","binarySha256":"${"b".repeat(64)}","binaryBytes":21495934}""" else "null"}}
    """)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid server response accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }
    @Test fun verifiedReuseNeedsNoDownload() {
        assertEquals(0L, parseRouterVpnServerPlan(plan()).downloadBytes)
    }
    @Test fun installationUsesRealPositiveDownloadSize() {
        assertEquals(123456L, parseRouterVpnServerPlan(plan("install", "123456")).downloadBytes)
        rejected { parseRouterVpnServerPlan(plan("install", "0")) }
        rejected { parseRouterVpnServerPlan(plan("reuse", "123456")) }
    }
    @Test fun unavailableCannotSupplyAnInstallableComponent() {
        assertFalse(parseRouterVpnServerPlan(plan("unavailable", "null", false)).available)
        rejected { parseRouterVpnServerPlan(JsonObject(plan("unavailable", "null", false) + ("component" to plan()["component"]!!))) }
    }
    @Test fun malformedOrUnverifiedPlanCannotBeConfirmed() {
        for ((key, invalid) in listOf("schema" to JsonPrimitive("1"), "planSha256" to JsonPrimitive("wrong"),
                "available" to JsonPrimitive("true"), "downloadBytes" to JsonPrimitive(-1), "component" to JsonNull)) {
            rejected { parseRouterVpnServerPlan(JsonObject(plan() + (key to invalid))) }
        }
    }
    @Test fun jobProgressAndStateAreBounded() {
        val job = obj("""{"schema":1,"jobId":"vpn-server-job-abc","state":"QUEUED","progressPercent":0,"failureReason":null}""")
        assertTrue(parseRouterVpnServerJob(job).running)
        rejected { parseRouterVpnServerJob(JsonObject(job + ("progressPercent" to JsonPrimitive(101)))) }
        rejected { parseRouterVpnServerJob(JsonObject(job + ("state" to JsonPrimitive("ARBITRARY_SUCCESS")))) }
    }
    @Test fun recoveryIsTerminalAndBlocksNewWrites() {
        val state = RouterControlState(stage = RouterControlStage.CONNECTED,
            vpnServer = RouterVpnServerControlState(job = RouterVpnServerJob("job", "RECOVERY_REQUIRED", 30, "rollback")))
        assertFalse(state.vpnServer.running)
        assertTrue(state.vpnServer.requiresRecovery)
        assertFalse(state.controlsAvailable)
    }
    @Test fun serverJobDisablesManagementToggle() {
        val state = RouterControlState(stage = RouterControlStage.CONNECTED,
            preferences = RouterControlPreferences("pair-a", enabled = true), selectedRouterId = "pair-a",
            savedRouters = listOf(RouterSavedSummary("pair-a", 1, null, "192.168.1.1")),
            vpnServer = RouterVpnServerControlState(job = RouterVpnServerJob("job", "QUEUED", 0, null)))
        assertFalse(state.canToggleManagement)
    }
    @Test fun optionalHardwareMetadataIsSanitizedIndependently() {
        val info = parseRouterHardwareInfo(obj("""{"schema":1,"model":"Cudy WR3000","firmwareVersion":"bad\nvalue","architecture":null}"""))!!
        assertEquals("Cudy WR3000", info.model)
        assertNull(info.firmwareVersion)
        assertNull(info.architecture)
        assertNull(parseRouterHardwareInfo(JsonNull))
    }
    private fun caps(enabled: Boolean, available: Boolean) = obj("""{"schema":1,"coreProtocols":[],"legacyProtocols":[],
        "inboundProtocols":["hysteria2"],"phoneRemoteProvisioning":true,"subscriptionSelection":true,
        "independentPhoneChoices":true,"phoneRegistration":true,"homeProof":true,
        "vpnServer":{"schema":1,"enabled":$enabled,"available":$available}}""")
    @Test fun verifiedManagedServerDoesNotRequireLegacyClientEngine() {
        assertEquals(setOf("hysteria2"), parseRouterPanelCapabilities(caps(true, true)).inboundProtocols)
    }
    @Test fun disabledOrUnavailableServerCannotAdvertiseClientProvisioning() {
        rejected { parseRouterPanelCapabilities(caps(false, true)) }
        rejected { parseRouterPanelCapabilities(caps(true, false)) }
    }
    @Test fun oldPlansNeverPermitComponentDeletionImplicitly() {
        assertFalse(parseRouterVpnServerPlan(plan()).removalAvailable)
    }
    @Test fun onlyAnExplicitOwnedManagedComponentCanBeRemovable() {
        val managed = JsonObject(plan() + ("removalAvailable" to JsonPrimitive(true)))
        assertTrue(parseRouterVpnServerPlan(managed).removalAvailable)
        val legacy = JsonObject(managed + ("component" to JsonObject(managed["component"]!!.jsonObject +
            ("kind" to JsonPrimitive("verified-legacy-server")))))
        rejected { parseRouterVpnServerPlan(legacy) }
        rejected { parseRouterVpnServerPlan(JsonObject(plan("install", "1234") + ("removalAvailable" to JsonPrimitive(true)))) }
        rejected { parseRouterVpnServerPlan(JsonObject(plan("unavailable", "null", false) + ("removalAvailable" to JsonPrimitive(true)))) }
    }
    @Test fun componentRemovalAvailabilityMustBeABoolean() {
        rejected { parseRouterVpnServerPlan(JsonObject(plan() + ("removalAvailable" to JsonPrimitive("true")))) }
    }

}
