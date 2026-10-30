package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RouterPackageUpdateApiTest {
    @Test fun unexpectedUrlVersionAndNonPairedTokenAreRejectedBeforeTransport() = runTest {
        val fixture = EngineApiFixture(); val api = fixture.api()
        for (key in listOf("url", "version", "storage", "engineIds")) {
            try { api.mobileRequest(RouterMobileAction.UPDATES_CHECK, buildJsonObject {
                put("token", EngineApiFixture.TOKEN); put("expectedRevision", 4); put(key, "unexpected")
            }); fail("extra update parameter accepted") } catch (_: IllegalArgumentException) { }
        }
        try { api.mobileRequest(RouterMobileAction.UPDATES_STATUS, buildJsonObject { put("token", "b".repeat(64)) }); fail("unpaired token") }
        catch (_: IllegalArgumentException) { }
        assertTrue(fixture.actions.isEmpty())
    }

    @Test fun updateErrorsAreTypedAndUnknownCodesFailClosed() = runTest {
        val f = EngineApiFixture(); val api = f.api()
        f.reply = { _, _ -> buildJsonObject { put("_foxhole_error", "update_no_space") } }
        try { api.mobileRequest(RouterMobileAction.UPDATES_STATUS, buildJsonObject { put("token", EngineApiFixture.TOKEN) }); fail("failure expected") }
        catch (e: RouterUpdateFailure) { assertEquals(RouterUpdateFailureReason.NO_SPACE, e.reason) }
        f.reply = { _, _ -> buildJsonObject { put("_foxhole_error", "update_future_code") } }
        try { api.mobileRequest(RouterMobileAction.UPDATES_STATUS, buildJsonObject { put("token", EngineApiFixture.TOKEN) }); fail("unknown error accepted") }
        catch (e: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, e.kind) }
    }
}
