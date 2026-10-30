package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouterEngineContractsTest {
    @Test fun onlyStatusAvailabilityEnablesMissingProtocols() {
        val status = parseRouterEngineStatus(EngineTestDocuments.status())
        assertEquals(12, status.entries.size)
        assertEquals(setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD), status.entries.filter { it.canAdd }.map { it.protocol }.toSet())
        assertFalse(status.entries.single { it.protocol == RouterInstallProtocol.TUIC }.canAdd)
    }

    @Test fun unknownAndDuplicateProtocolsCannotEnterTheStatus() {
        val base = EngineTestDocuments.status()
        val rows = base["entries"] as JsonArray
        invalid { parseRouterEngineStatus(JsonObject(base + ("entries" to JsonArray(rows + rows.first())))) }
        val changed = JsonObject((rows.first() as JsonObject) + ("id" to JsonPrimitive("vless://example")))
        invalid { parseRouterEngineStatus(JsonObject(base + ("entries" to JsonArray(listOf(changed) + rows.drop(1))))) }
    }

    @Test fun plansMustMatchTheRequestedRouterRevisionAndProtocols() {
        val status = parseRouterEngineStatus(EngineTestDocuments.status())
        val plan = EngineTestDocuments.plan()
        invalid { parseRouterEnginePlan(JsonObject(plan + ("routerId" to JsonPrimitive("other-router"))), status, RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS)) }
        invalid { parseRouterEnginePlan(JsonObject(plan + ("revision" to JsonPrimitive(2))), status, RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS)) }
        invalid { parseRouterEnginePlan(plan, status, RouterEngineOperation.ADD, setOf(RouterInstallProtocol.WIREGUARD)) }
        val admitted = parseRouterEnginePlan(plan, status, RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        assertEquals("boot-test", admitted.bootId)
        assertEquals(status.catalogSha256, admitted.catalogSha256)
        assertEquals(RouterEngineImpact.KEEP_ACTIVE, admitted.impact)
    }

    @Test fun wallClockIsNotRequiredForABootBoundPlan() {
        val status = parseRouterEngineStatus(EngineTestDocuments.status())
        val plan = parseRouterEnginePlan(EngineTestDocuments.plan(), status, RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        assertEquals(300_000L, plan.expiresAtUptimeMs)
    }

    @Test fun anActiveUpdateCannotHideItsReconnectConfirmation() {
        val status = parseRouterEngineStatus(EngineTestDocuments.status())
        invalid { parseRouterEnginePlan(EngineTestDocuments.plan("UPDATE", "hysteria2", "KEEP_ACTIVE"), status,
            RouterEngineOperation.UPDATE, setOf(RouterInstallProtocol.HYSTERIA2)) }
        val admitted = parseRouterEnginePlan(EngineTestDocuments.plan("UPDATE", "hysteria2", "RECONNECT_REQUIRED"), status,
            RouterEngineOperation.UPDATE, setOf(RouterInstallProtocol.HYSTERIA2))
        assertEquals(RouterEngineImpact.RECONNECT_REQUIRED, admitted.impact)
    }

    @Test fun badJobIdentityProgressAndUnknownStepsAreRejected() {
        val job = EngineTestDocuments.job()
        invalid { parseRouterEngineJob(job, "another-router") }
        invalid { parseRouterEngineJob(JsonObject(job + ("progressPercent" to JsonPrimitive(101))), "router-test") }
        invalid { parseRouterEngineJob(JsonObject(job + ("progressPercent" to JsonPrimitive("50"))), "router-test") }
        invalid { parseRouterEngineJob(JsonObject(job + ("step" to JsonPrimitive("RUN_COMMAND"))), "router-test") }
        invalid { parseRouterEngineJob(job, "router-test", jobId = "other-job") }
    }

    @Test fun failedJobsDoNotImplyRestoredConnections() {
        val job = parseRouterEngineJob(EngineTestDocuments.job("FAILED", "RECONNECT_FAILED"), "router-test")
        assertFalse(job.running)
        assertEquals(null, job.previousConnectionRestored)
        assertEquals(RouterEngineFailureReason.RECONNECT_FAILED, job.failureReason)
        assertTrue(parseRouterEngineJob(EngineTestDocuments.job(), "router-test").running)
    }

    private fun invalid(action: () -> Unit) {
        try { action(); fail("malformed engine metadata was admitted") }
        catch (error: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, error.kind) }
    }
}

internal object EngineTestDocuments {
    fun status(active: String? = "hysteria2", installed: Set<String> = setOf("hysteria2"), ownJob: JsonObject? = null) = buildJsonObject {
        put("schema", 1); put("routerId", "router-test"); put("revision", 1); put("catalogSha256", "a".repeat(64))
        put("activeEngineId", active?.let(::JsonPrimitive) ?: JsonNull)
        put("activeProfileId", if (active == null) JsonNull else JsonPrimitive("server-test"))
        put("busy", ownJob != null); put("activeOwnJob", ownJob?.let { ref(it) } ?: JsonNull); put("lastOwnJob", ownJob?.let { ref(it) } ?: JsonNull)
        put("entries", JsonArray(RouterInstallProtocol.entries.map { protocol -> buildJsonObject {
            val present = protocol.canonical in installed
            put("id", protocol.canonical); put("availability", if (protocol.canonical in setOf("hysteria2", "vless", "wireguard")) "AVAILABLE" else "NOT_RELEASED")
            put("installed", present); put("active", present && protocol.canonical == active); put("updateAvailable", present)
            put("storage", if (present) JsonPrimitive("FLASH") else JsonNull); put("version", if (present) JsonPrimitive("1.0.0") else JsonNull)
            put("binarySha256", if (present) JsonPrimitive("b".repeat(64)) else JsonNull)
            put("archiveBytes", 1024); put("installedBytes", if (present) JsonPrimitive(2048) else JsonNull)
        } }))
    }
    fun plan(operation: String = "ADD", id: String = "vless", impact: String = "KEEP_ACTIVE") = buildJsonObject {
        put("schema", 1); put("routerId", "router-test"); put("planId", "plan-test"); put("planSha256", "c".repeat(64))
        put("revision", 1); put("bootId", "boot-test"); put("expiresAtUptimeMs", 300_000); put("expiresAtEpochMs", JsonNull)
        put("operation", operation); put("engineIds", JsonArray(listOf(JsonPrimitive(id))))
        put("confirmationRequired", true); put("impact", impact); put("warnings", JsonArray(emptyList()))
        put("changes", JsonArray(listOf(buildJsonObject {
            put("id", id); put("fromVersion", if (operation == "ADD") JsonNull else JsonPrimitive("1.0.0"))
            put("toVersion", if (operation == "REMOVE") JsonNull else JsonPrimitive("1.0.1")); put("storage", "FLASH")
            put("archiveBytes", 1024); put("installedBytes", 2048)
        })))
        put("budget", buildJsonObject {
            put("downloadBytes", 1024); put("stagingBytes", 4096); put("flashRequiredBytes", 4096); put("flashAvailableBytes", 8192)
            put("ramRequiredBytes", 1024); put("ramAvailableBytes", 8192); put("reservedBytes", 1024)
        })
    }
    fun job(state: String = "DOWNLOADING", reason: String? = null) = buildJsonObject {
        put("schema", 1); put("routerId", "router-test"); put("jobId", "job-test"); put("planId", "plan-test")
        put("state", state); put("progressPercent", if (state == "SUCCEEDED") 100 else 25)
        put("step", when (state) { "SUCCEEDED" -> "COMPLETED"; "FAILED" -> "FAILED"; else -> "DOWNLOADING_PACKAGE" })
        put("revision", 1); put("failureReason", reason?.let(::JsonPrimitive) ?: JsonNull); put("previousConnectionRestored", JsonNull)
    }
    private fun ref(job: JsonObject) = JsonObject(job.filterKeys { it in setOf("jobId", "planId", "state", "progressPercent", "step") })
}
