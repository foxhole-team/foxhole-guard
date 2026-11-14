package com.foxhole.guard.router

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTransportGenerationTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.8.1"), "a".repeat(64))

    private fun api(factory: Call.Factory, check: () -> Unit): RouterPanelApi =
        RouterPanelApi(identity, factory).also {
            RouterPanelApi::class.java.getDeclaredField("transportCheck")
                .apply { isAccessible = true }.set(it, check)
        }

    private fun reply(call: Call, callback: Callback) {
        callback.onResponse(call, Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("Synthetic state")
            .body("{}".toResponseBody("application/json".toMediaType())).build())
    }

    @Test fun aLateReplyCannotPublishStateAfterTheVpnGenerationChanges() = runTest {
        val pending = CompletableDeferred<Pair<Call, Callback>>()
        val factory = Call.Factory { request ->
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(responseCallback: Callback) { pending.complete(this to responseCallback) }
            }
        }
        var current = true
        val api = api(factory) { if (!current) throw RouterHomeVpnRequired() }
        val session = RouterPanelSession("c".repeat(64), false)
        try {
            val result = async { runCatching { api.state(session) } }
            val (call, callback) = pending.await()
            current = false
            reply(call, callback)
            assertTrue("The expired transport must be reported explicitly", result.await().exceptionOrNull() is RouterHomeVpnRequired)
        } finally { session.close(); api.close() }
    }

    @Test fun anExpiredTransportDoesNotStartAnotherRequest() = runTest {
        var requests = 0
        val factory = Call.Factory { request ->
            requests++
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(responseCallback: Callback) { reply(this, responseCallback) }
            }
        }
        val api = api(factory) { throw RouterHomeVpnRequired() }
        val session = RouterPanelSession("c".repeat(64), false)
        try {
            val result = runCatching { api.state(session) }
            assertTrue(result.exceptionOrNull() is RouterHomeVpnRequired)
            assertEquals(0, requests)
        } finally { session.close(); api.close() }
    }
}
