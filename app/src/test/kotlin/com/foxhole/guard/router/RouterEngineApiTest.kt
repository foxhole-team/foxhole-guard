package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouterEngineApiTest {
    @Test fun engineErrorsRetainTypedReasonsWithoutChangingExistingPinErrors() = runTest {
        val fixture = EngineApiFixture()
        val api = fixture.api()
        fixture.reply = { _, _ -> buildJsonObject { put("_foxhole_error", "engine_no_space") } }
        try { api.mobileRequest(RouterMobileAction.ENGINES_STATUS, token()); fail("expected memory failure") }
        catch (error: RouterEngineFailure) { assertEquals(RouterEngineFailureReason.NO_SPACE, error.reason) }
        fixture.reply = { _, _ -> buildJsonObject { put("_foxhole_error", "unauthorized") } }
        try { api.mobileRequest(RouterMobileAction.ENGINES_STATUS, token()); fail("expected auth failure") }
        catch (error: RouterApiFailure) { assertEquals(RouterApiError.PIN, error.kind) }
    }

    @Test fun arbitraryUrlsAndUnexpectedApplyKeysNeverReachTheTransport() = runTest {
        val fixture = EngineApiFixture()
        val api = fixture.api()
        val invalid = buildJsonObject {
            put("token", EngineApiFixture.TOKEN); put("planId", "https://unexpected.invalid/run"); put("planSha256", "c".repeat(64))
            put("expectedRevision", 1); put("confirmed", true)
        }
        try { api.mobileRequest(RouterMobileAction.ENGINES_APPLY, invalid); fail("unsafe id accepted") }
        catch (_: RouterApiFailure) { }
        try { api.mobileRequest(RouterMobileAction.ENGINES_STATUS, buildJsonObject { put("token", EngineApiFixture.TOKEN); put("url", "https://unexpected.invalid") }); fail("unexpected field") }
        catch (_: IllegalArgumentException) { }
        assertTrue(fixture.actions.isEmpty())
    }

    @Test fun onlyTheExistingPairedSessionIsSentForStatus() = runTest {
        val fixture = EngineApiFixture()
        fixture.api().mobileRequest(RouterMobileAction.ENGINES_STATUS, token())
        assertEquals(listOf("engines.status"), fixture.actions)
        assertEquals(setOf("token"), fixture.payloads.single().keys)
        assertEquals(EngineApiFixture.TOKEN, fixture.payloads.single()["token"]!!.jsonPrimitive.content)
    }

    private fun token() = buildJsonObject { put("token", EngineApiFixture.TOKEN) }
}

internal class EngineApiFixture {
    val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    val actions = mutableListOf<String>()
    val payloads = mutableListOf<JsonObject>()
    var hold: String? = null
    var delayed: (() -> Unit)? = null
    var cancellations = 0
    var reply: (String, JsonObject) -> JsonObject = { action, _ -> when (action) {
        "phone.capabilities" -> UpdateDocuments.capabilities()
                            "pair.unlock" -> buildJsonObject { put("token", TOKEN) }
        "state" -> buildJsonObject {
            put("schema", 1); put("revision", 1); put("settings", buildJsonObject { put("localRules", true); put("localScope", "all") })
            listOf("countries", "devices", "sites", "clients").forEach { put(it, kotlinx.serialization.json.JsonArray(emptyList())) }
            put("servers", kotlinx.serialization.json.JsonArray(emptyList()))
            put("runtime", buildJsonObject { put("desiredServerId", kotlinx.serialization.json.JsonNull); put("connected", false) })
        }
        "telemetry" -> buildJsonObject { put("sample", buildJsonObject { put("wan", buildJsonObject { put("country", "NL") }) }) }
        "engines.status" -> EngineTestDocuments.status()
        "engines.plan" -> EngineTestDocuments.plan()
        "engines.apply" -> EngineTestDocuments.job()
        "engines.job" -> EngineTestDocuments.job("SUCCEEDED")
        else -> error("unexpected fixture action " + action)
    } }

    fun api(chosenIdentity: RouterTlsIdentity = identity): RouterPanelApi = RouterPanelApi(chosenIdentity, Call.Factory { request ->
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
        val action = envelope["action"]!!.jsonPrimitive.content
        val payload = envelope["payload"]!!.jsonObject
        actions += action; payloads += payload
        object : Call by OkHttpClient().newCall(request) {
            override fun enqueue(responseCallback: Callback) {
                val respond = {
                    responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                        .body(reply(action, payload).toString().toResponseBody("application/json".toMediaType())).build())
                }
                if (hold == action) delayed = respond else respond()
            }
            override fun cancel() { cancellations++ }
        }
    })
    companion object { val TOKEN = "p1." + "b".repeat(64) }
}
