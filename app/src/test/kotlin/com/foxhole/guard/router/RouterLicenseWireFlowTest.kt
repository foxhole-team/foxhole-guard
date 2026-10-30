package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouterLicenseWireFlowTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val token = "b".repeat(64)

    private fun api(unlock: String, payloads: MutableList<JsonObject>): RouterPanelApi = RouterPanelApi(identity, Call.Factory { request ->
        val value = Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
        val action = value.getValue("action").jsonPrimitive.content
        payloads += value.getValue("payload").jsonObject
        val response = if (action == "unlock") unlock else """{"token":"$token"}"""
        object : Call by OkHttpClient().newCall(request) {
            override fun enqueue(callback: Callback) {
                callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(response.toResponseBody("application/json".toMediaType())).build())
            }
        }
    })

    @Test fun firstPinWithoutConsentDoesNotSendAnHttpMutationAndClearsTheSecret() = runTest {
        val requests = mutableListOf<JsonObject>()
        val client = api("""{"token":"$token","pinChangeRequired":true,"licenseRequired":true,"licenseVersion":1}""", requests)
        val session = client.unlock("1234".toCharArray())
        val pin = "5678".toCharArray()
        try { client.changePin(session, pin); error("missing license accepted") }
        catch (_: RouterLicenseRequired) { }
        assertEquals(1, requests.size)
        assertArrayEquals(CharArray(4), pin)
        session.close(); client.close()
    }

    @Test fun anAcceptedFirstPinUsesTypedAcknowledgmentAndRetiresTheRestrictedSession() = runTest {
        val requests = mutableListOf<JsonObject>()
        val client = api("""{"token":"$token","pinChangeRequired":true,"licenseRequired":true,"licenseVersion":1}""", requests)
        val session = client.unlock("1234".toCharArray())
        val pin = "5678".toCharArray()
        val changed = client.changePin(session, pin, licenseAccepted = true, licenseVersion = 1)
        val fields = requests.last()
        assertEquals(2, requests.size)
        assertEquals(true, (fields.getValue("licenseAccepted") as JsonPrimitive).booleanOrNull)
        assertEquals(1, (fields.getValue("licenseVersion") as JsonPrimitive).intOrNull)
        assertFalse((fields.getValue("licenseAccepted") as JsonPrimitive).isString)
        assertFalse((fields.getValue("licenseVersion") as JsonPrimitive).isString)
        assertArrayEquals(CharArray(4), pin)
        assertFalse(changed.changePinRequired)
        try { session.token(); error("old PIN session survived") } catch (_: IllegalStateException) { }
        changed.close(); client.close()
    }

    @Test fun legacyPairedSessionDoesNotInventAnAgreementOrChangeItsVersion() = runTest {
        val requests = mutableListOf<JsonObject>()
        val client = api("""{"token":"$token","pinChangeRequired":false}""", requests)
        val session = client.unlock("1234".toCharArray())
        assertFalse(session.licenseRequired)
        assertEquals(0, session.licenseVersion)
        val changed = client.changePin(session, "5678".toCharArray())
        assertEquals(false, (requests.last().getValue("licenseAccepted") as JsonPrimitive).booleanOrNull)
        assertEquals(0, (requests.last().getValue("licenseVersion") as JsonPrimitive).intOrNull)
        changed.close(); client.close()
    }
}
