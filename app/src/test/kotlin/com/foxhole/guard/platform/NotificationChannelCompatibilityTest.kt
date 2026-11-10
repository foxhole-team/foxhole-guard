package com.foxhole.guard.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelCompatibilityTest {
    @Test
    fun callbackRegistrationUsesOnlyTheSdkSupportedOverload() {
        for (sdk in listOf(24, 25, 26, 37)) {
            val calls = mutableListOf<String>()
            val callback = Any()
            val registered = androidApi26Value(
                legacy = { calls += "default"; callback },
                sdkInt = sdk,
                modern = { calls += "handler"; callback },
            )
            assertSame(callback, registered)
            assertEquals(listOf(if (sdk >= 26) "handler" else "default"), calls)
        }
    }

    @Test
    fun legacyAndroidNeverResolvesOrInvokesAChannelApi() {
        for (sdk in listOf(24, 25)) {
            withNotificationChannels(sdk) { error("Channel creation is unavailable on SDK $sdk") }
            assertTrue(notificationChannelValue(true, sdk) { error("Channel lookup is unavailable") })
        }
    }

    @Test
    fun oreoAndLaterPreserveChannelCreationExactlyOnce() {
        for (sdk in listOf(26, 28, 33, 37)) {
            var calls = 0
            withNotificationChannels(sdk) { calls++ }
            assertEquals(1, calls)
        }
    }

    @Test
    fun legacyAppLevelPermissionDoesNotInventAChannelBlock() {
        assertTrue(notificationChannelValue(true, 24) { false })
        assertFalse(notificationChannelValue(true, 26) { false })
        assertTrue(notificationChannelValue(false, 26) { true })
    }

    @Test
    fun modernMissingChannelAndLookupFailureKeepTheirOriginalMeaning() {
        assertEquals(null, notificationChannelValue<Int?>(null, 26) { null })
        val failure = IllegalStateException("channel service unavailable")
        val actual = runCatching {
            notificationChannelValue(true, 26) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, actual)
    }
}
