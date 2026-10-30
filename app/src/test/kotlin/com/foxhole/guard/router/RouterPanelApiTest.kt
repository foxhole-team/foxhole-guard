package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterPanelApiTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val token = "b".repeat(64)
    private class FixtureCall(private val value: Request, private val response: String?) : Call by OkHttpClient().newCall(value) {
        var cancelled = false
        override fun execute(): Response = error("blocking call forbidden")
        override fun enqueue(callback: Callback) {
            response?.let { callback.onResponse(this, Response.Builder().request(value).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(it.toResponseBody("application/json".toMediaType())).build()) }
        }
        override fun cancel() { cancelled = true }
    }
    private class PendingCall(private val request: Request) : Call by OkHttpClient().newCall(request) {
        private lateinit var callback: Callback
        @Volatile var cancelled = false
        @Volatile private var activeBody: TrackedBody? = null
        override fun enqueue(callback: Callback) { this.callback = callback }
        override fun cancel() { cancelled = true; activeBody?.cancelTransportRead() }
        fun respond(body: ResponseBody) {
            activeBody = body as? TrackedBody
            if (cancelled) activeBody?.cancelTransportRead()
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body).build())
        }
    }
    private class TrackedBody(value: String? = null) : ResponseBody() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean()
        val bytesRead = AtomicLong()
        private val released = CountDownLatch(1)
        private val contents = value?.let { Buffer().writeUtf8(it) }
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                if (contents == null) {
                    if (!released.await(5, TimeUnit.SECONDS)) throw IOException("fixture_read_timeout")
                    throw IOException("fixture_closed")
                }
                return contents.read(sink, byteCount).also { if (it > 0) bytesRead.addAndGet(it) }
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed.set(true); released.countDown() }
        }.buffer()
        fun cancelTransportRead() { released.countDown() }
        override fun contentType(): MediaType = "application/json".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = stream
    }
    private fun api(response: String, requests: MutableList<String> = mutableListOf()) = RouterPanelApi(identity, Call.Factory { request ->
        assertEquals("https://192.168.1.1:8443/cgi-bin/foxhole", request.url.toString())
        assertEquals(null, request.header("Authorization"))
        requests += Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
        FixtureCall(request, response)
    })

    @Test fun discoveryRequiresAnExplicitTypedFoxHolePackageAttestation() = runTest {
        assertEquals(RouterPackageStatus(false, true), api("""{"schema":1,"product":"foxhole","installed":true,"pinConfigured":false,"resetSupported":true}""").packageStatus())
        for (response in listOf(
            "{}",
            """{"schema":1,"product":"other","installed":true,"pinConfigured":true,"resetSupported":true}""",
            """{"schema":1,"product":"foxhole","installed":false,"pinConfigured":true,"resetSupported":true}""",
            """{"schema":1,"product":"foxhole","installed":true,"pinConfigured":"true","resetSupported":true}""",
        )) {
            try { api(response).packageStatus(); error("invalid discovery metadata accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }

    @Test fun resetRequiresAnAdvancedOwnedScopeAckAndRestrictedNewPinSession() = runTest {
        val valid = """{"schema":1,"scope":"foxhole","reset":true,"revision":8,"token":"${"d".repeat(64)}","pinChangeRequired":true}"""
        val requests = mutableListOf<String>()
        fun resetApi(response: String) = RouterPanelApi(identity, Call.Factory { request ->
            val raw = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            requests += raw
            FixtureCall(request, if (raw.contains("\"action\":\"state\"")) """{"revision":7}""" else response)
        })
        val old = RouterPanelSession(token, false)
        val replacement = resetApi(valid).resetFoxHole(old)
        assertTrue(replacement.changePinRequired)
        assertTrue(requests.last().contains("\"confirm\":true"))
        assertTrue(requests.last().contains("\"revision\":7"))
        try { old.token(); error("old session remained usable") } catch (_: IllegalStateException) { }
        replacement.close()
        for (invalid in listOf(
            valid.replace("\"schema\":1", "\"schema\":2"),
            valid.replace("\"scope\":\"foxhole\"", "\"scope\":\"router\""),
            valid.replace("\"reset\":true", "\"reset\":false"),
            valid.replace("\"revision\":8", "\"revision\":7"),
            valid.replace("\"pinChangeRequired\":true", "\"pinChangeRequired\":false"),
        )) {
            val retained = RouterPanelSession(token, false)
            try { resetApi(invalid).resetFoxHole(retained); error("invalid reset acknowledgment accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
            assertEquals(token, retained.token())
            retained.close()
        }
    }

    @Test fun missingFirstPinIsReportedAsSetupRatherThanInvalidCredentialsOrNetwork() = runTest {
        try { api("""{"_foxhole_error":"pin_setup_required"}""").unlock("1234".toCharArray()); error("missing setup accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.PIN_SETUP, failure.kind) }
    }

    @Test fun enteringPinCreatesOnlyAnEphemeralSessionAndClearsTheInput() = runTest {
        val pin = "1234".toCharArray()
        val requests = mutableListOf<String>()
        val session = api("""{"token":"$token","pinChangeRequired":true}""", requests).unlock(pin)
        assertTrue(session.changePinRequired)
        assertArrayEquals(CharArray(4), pin)
        assertTrue(requests.single().contains("\"pin\":\"1234\""))
        assertFalse(session.toString().contains(token))
        session.close()
    }

    @Test fun shortPinIsNeverUsedAsThePersistentPairingCredential() = runTest {
        val pairing = RouterPairing.create(identity, "Phone")
        val requests = mutableListOf<String>()
        api("""{"id":"${pairing.id}"}""", requests).createPairing(RouterPanelSession(token, false), pairing)
        assertEquals(32, pairing.credential.size)
        assertTrue(requests.single().contains("\"credential\":\""))
        assertFalse(requests.single().contains("\"pin\""))
        assertFalse(pairing.toString().contains(pairing.credential.joinToString()))
        pairing.clear()
    }

    @Test fun aDefaultPinSessionCannotCreateOrDelegateAPairing() = runTest {
        val requests = mutableListOf<String>()
        try {
            api("{}", requests).createPairing(RouterPanelSession(token, true), RouterPairing.create(identity, "Phone"))
            error("default PIN session allowed")
        } catch (_: IllegalArgumentException) { assertTrue(requests.isEmpty()) }
    }

    @Test fun pairingUnlockRequiresTheTlsFencedSessionPrefix() = runTest {
        val pairing = RouterPairing.create(identity, "Phone")
        try { api("""{"token":"$token"}""").unlockPairing(pairing); error("unfenced token accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        api("""{"token":"p1.$token"}""").unlockPairing(pairing).close()
    }

    @Test fun routerErrorsAreMappedWithoutLeakingTheirResponse() = runTest {
        val pin = "1234".toCharArray()
        try { api("""{"_foxhole_error":"locked"}""").unlock(pin); error("lock ignored") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.LOCKED, failure.kind) }
        assertArrayEquals(CharArray(4), pin)
        try { api("{" + "x".repeat(300001)).unlock("1234".toCharArray()); error("oversized response accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }

    @Test fun cancellingPairingCancelsTheExactRequestAndClearsPin() = runTest {
        lateinit var call: FixtureCall
        val client = RouterPanelApi(identity, Call.Factory { FixtureCall(it, null).also { value -> call = value } })
        val pin = "1234".toCharArray()
        var ownerCancellationObserved = false
        val job = launch {
            try { client.unlock(pin) }
            catch (cancelled: CancellationException) { ownerCancellationObserved = true; throw cancelled }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(call.cancelled)
        assertTrue(ownerCancellationObserved)
        assertArrayEquals(CharArray(4), pin)
    }

    @Test fun aRequestDeadlineSettlesAsNetworkFailureWithoutCancellingItsOwner() = runTest {
        lateinit var call: PendingCall
        val client = RouterPanelApi(identity, Call.Factory { PendingCall(it).also { value -> call = value } })
        val pin = "1234".toCharArray()
        val result = async { runCatching { client.unlock(pin) } }
        try {
            runCurrent()
            advanceTimeBy(30001)
            runCurrent()
            assertTrue(result.isCompleted)
            assertEquals(RouterApiError.NETWORK, (result.await().exceptionOrNull() as? RouterApiFailure)?.kind)
            assertTrue(call.cancelled && isActive)
            assertArrayEquals(CharArray(4), pin)
        } finally { result.cancel() }
    }

    @Test fun anEnclosingOwnerDeadlineRemainsCancellationInsteadOfNetworkFailure() = runTest {
        lateinit var call: PendingCall
        val client = RouterPanelApi(identity, Call.Factory { PendingCall(it).also { value -> call = value } })
        val pin = "1234".toCharArray()
        val result = async { runCatching { withTimeout(5000) { client.unlock(pin) } } }
        try {
            runCurrent()
            advanceTimeBy(5001)
            runCurrent()
            assertTrue(result.isCompleted)
            assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(call.cancelled && isActive)
            assertArrayEquals(CharArray(4), pin)
        } finally { result.cancel() }
    }

    @Test fun cancellingAnAlreadyReceivedBodyClosesItWhileItsReadIsBlocked() = runTest {
        lateinit var call: PendingCall
        val client = RouterPanelApi(identity, Call.Factory { PendingCall(it).also { value -> call = value } })
        val pin = "1234".toCharArray()
        val job = launch { client.unlock(pin) }
        val body = TrackedBody()
        runCurrent()
        val worker = thread(isDaemon = true) { call.respond(body) }
        try {
            assertTrue(body.entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            runCurrent()
            assertTrue(call.cancelled)
            worker.join(1000)
            assertFalse(worker.isAlive)
            assertTrue(body.closed.get())
            assertArrayEquals(CharArray(4), pin)
        } finally { job.cancel(); body.close(); worker.join(1000) }
    }

    @Test fun cancellingARealDelayedBodyLeavesClosingToItsCallbackThread() = runBlocking {
        RouterDelayedHttpBodyFixture().use { fixture ->
            val failures = ConcurrentLinkedQueue<Throwable>()
            val owner = SupervisorJob()
            val scope = CoroutineScope(owner + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> failures += failure })
            val pin = "1234".toCharArray()
            val client = RouterPanelApi(identity, fixture)
            val job = scope.launch { client.unlock(pin).close(); error("delayed body unexpectedly completed") }
            try {
                assertTrue(fixture.readEntered.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5000) { job.join() }
                assertTrue(fixture.transportReadExited.await(5, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
                assertTrue(fixture.callCancelled.get())
                assertTrue("cancellation must not close a router response while its callback reader owns it", fixture.closeThreads.isEmpty())
                assertTrue(failures.isEmpty())
                assertArrayEquals(CharArray(4), pin)
            } finally {
                job.cancel()
                fixture.releaseBodyRead()
                owner.cancel()
                assertTrue(fixture.callbackFinished.await(5, TimeUnit.SECONDS))
            }
            assertTrue(fixture.closeThreads.isNotEmpty())
            assertTrue(fixture.closeThreads.all { it === fixture.callbackThread.get() })
            assertTrue(fixture.readThread.get() === fixture.callbackThread.get())
            assertEquals(null, fixture.callbackFailure.get())
            assertEquals(null, fixture.serverFailure.get())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aLateBodyAfterOwnerCancellationIsClosedWithoutReadingCredentials() = runTest {
        lateinit var call: PendingCall
        val client = RouterPanelApi(identity, Call.Factory { PendingCall(it).also { value -> call = value } })
        val job = launch { client.unlock("1234".toCharArray()) }
        runCurrent()
        job.cancel()
        runCurrent()
        val body = TrackedBody("""{"token":"$token","pinChangeRequired":false}""")
        call.respond(body)
        assertTrue(body.closed.get())
        assertEquals(0L, body.bytesRead.get())
    }

    @Test fun theOverallDeadlineIncludesBlockedBodyReadingAfterHeaders() = runTest {
        lateinit var call: PendingCall
        val client = RouterPanelApi(identity, Call.Factory { PendingCall(it).also { value -> call = value } })
        val pin = "1234".toCharArray()
        val result = async { runCatching { client.unlock(pin) } }
        val body = TrackedBody()
        runCurrent()
        val worker = thread(isDaemon = true) { call.respond(body) }
        try {
            assertTrue(body.entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(30001)
            runCurrent()
            assertTrue(result.isCompleted)
            assertEquals(RouterApiError.NETWORK, (result.await().exceptionOrNull() as? RouterApiFailure)?.kind)
            worker.join(1000)
            assertFalse(worker.isAlive)
            assertTrue(call.cancelled && body.closed.get() && isActive)
            assertArrayEquals(CharArray(4), pin)
        } finally { result.cancel(); body.close(); worker.join(1000) }
    }

    @Test fun anUnknownLengthBodyCannotExceedTheResponseLimit() = runTest {
        lateinit var call: PendingCall
        val client = RouterPanelApi(identity, Call.Factory { PendingCall(it).also { value -> call = value } })
        val result = async { runCatching { client.unlock("1234".toCharArray()) } }
        runCurrent()
        val body = TrackedBody("x".repeat(1000000))
        call.respond(body)
        runCurrent()
        assertEquals(RouterApiError.INVALID_RESPONSE, (result.await().exceptionOrNull() as? RouterApiFailure)?.kind)
        assertTrue(body.closed.get())
        assertTrue(body.bytesRead.get() <= 300000 + 8192)
    }

    @Test fun depthAndNodeBoundsRunBeforeParsingAnOtherwiseSuccessfulResponse() = runTest {
        val deep = "[".repeat(40) + "0" + "]".repeat(40)
        val response = """{"token":"$token","pinChangeRequired":false,"extra":$deep}"""
        try { api(response).unlock("1234".toCharArray()); error("deep response accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        val broad = "[" + List(17000) { "0" }.joinToString(",") + "]"
        try { api("""{"token":"$token","pinChangeRequired":false,"extra":$broad}""").unlock("1234".toCharArray()); error("large response tree accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        api("""{"token":"$token","pinChangeRequired":false,"extra":"[{{\\\""}""").unlock("1234".toCharArray()).close()
    }

    @Test fun closingTheOwnedTransportIsIdempotentAndPreventsFurtherCredentials() = runTest {
        var closed = 0
        var requests = 0
        val client = RouterPanelApi(identity, Call.Factory { requests++; FixtureCall(it, "{}") }, { closed++ })
        client.close(); client.close()
        val pin = "1234".toCharArray()
        try { client.unlock(pin); error("closed transport reused") } catch (_: IllegalStateException) {}
        assertEquals(1, closed)
        assertEquals(0, requests)
        assertArrayEquals(CharArray(4), pin)
    }

    @Test fun providerResolutionAndFreshHandoffKeepThePairedTokenAndExactFieldWhitelist() = runTest {
        for (action in listOf(RouterMobileAction.PHONE_RESOLVE, RouterMobileAction.PHONE_HANDOFF)) {
            val requests = mutableListOf<String>()
            val client = api("{}", requests)
            val accepted = buildJsonObject {
                put("token", "p1.$token"); put("address", "192.168.1.100")
                if (action == RouterMobileAction.PHONE_RESOLVE) put("host", "example.com")
                else put("requestUptimeMs", 10000)
            }
            client.mobileRequest(action, accepted)
            assertEquals(1, requests.size)
            try {
                client.mobileRequest(action, buildJsonObject {
                    put("token", "p1.$token"); put("address", "192.168.1.100"); put("admin", true)
                })
                error("extra fields allowed")
            } catch (_: IllegalArgumentException) { assertEquals(1, requests.size) }
            try {
                client.mobileRequest(action, buildJsonObject { put("token", token) })
                error("unpaired token allowed")
            } catch (_: IllegalArgumentException) { assertEquals(1, requests.size) }
        }
    }

    @Test fun remoteClientReadAndDeleteRequirePairedTokenAndExactAllowedFields() = runTest {
        for (action in listOf(RouterMobileAction.CLIENT_GET, RouterMobileAction.CLIENT_DELETE)) {
            val requests = mutableListOf<String>()
            val client = api("{}", requests)
            val accepted = buildJsonObject {
                put("token", "p1.$token"); put("id", "client-1")
                if (action == RouterMobileAction.CLIENT_DELETE) put("revision", 2)
            }
            client.mobileRequest(action, accepted)
            assertEquals(1, requests.size)
            val disallowed = if (action == RouterMobileAction.CLIENT_GET) "revision" else "config"
            try {
                client.mobileRequest(action, buildJsonObject {
                    accepted.forEach { (key, value) -> put(key, value) }; put(disallowed, 3)
                })
                error("extra remote-client fields allowed")
            } catch (_: IllegalArgumentException) { assertEquals(1, requests.size) }
            try {
                client.mobileRequest(action, buildJsonObject { put("token", token); put("id", "client-1") })
                error("unpaired remote-client token allowed")
            } catch (_: IllegalArgumentException) { assertEquals(1, requests.size) }
        }
    }
}
