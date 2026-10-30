package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.FoxholeBannerAction
import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.FoxholeBannerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserNoticePolicyTest {
    @Test
    fun `in app notifications use the top notice host`() {
        assertTrue(CLI_EXPERIMENTAL_TOP_NOTICES_ENABLED)
    }

    @Test
    fun `routine mode selection stays out of top notices`() {
        val selection = FoxholeBannerEvent(
            "Routing mode selected",
            FoxholeBannerTone.INFO,
            showInTopNotice = false,
        )
        assertNull(retainUserNotice(null, selection))
        val saved = FoxholeBannerEvent("Profile saved", FoxholeBannerTone.SUCCESS)
        assertSame(saved, retainUserNotice(saved, selection))
    }

    @Test
    fun `routine message presentation cannot hide warnings or errors`() {
        listOf(FoxholeBannerTone.WARNING, FoxholeBannerTone.ERROR).forEach { tone ->
            val critical = FoxholeBannerEvent("Action required", tone, showInTopNotice = false)
            assertSame(critical, retainUserNotice(null, critical))
        }
    }

    @Test
    fun `an error without an action remains visible until dismissed`() {
        val error = FoxholeBannerEvent("Connection failed", FoxholeBannerTone.ERROR)

        assertSame(error, retainUserNotice(null, error))
        assertNull(userNoticeDurationMillis(error))
    }

    @Test
    fun `background success does not erase an unacknowledged error`() {
        val error = FoxholeBannerEvent("Connection failed", FoxholeBannerTone.ERROR)
        val update = FoxholeBannerEvent("List refreshed", FoxholeBannerTone.SUCCESS)

        assertSame(error, retainUserNotice(error, update))
    }

    @Test
    fun `action requests remain owned by the separate action surface`() {
        val action = FoxholeBannerEvent(
            "Switch profile",
            FoxholeBannerTone.WARNING,
            action = FoxholeBannerAction.ACCEPT_NETWORK_RULE_PROFILE,
        )
        assertNull(retainUserNotice(null, action))
    }

    @Test
    fun `ordinary notices have a bounded display duration`() {
        assertEquals(4_000L, userNoticeDurationMillis(FoxholeBannerEvent("Saved", FoxholeBannerTone.SUCCESS)))
        assertEquals(
            1_000L,
            userNoticeDurationMillis(FoxholeBannerEvent("Saved", FoxholeBannerTone.INFO, durationMillis = 1))
        )
        assertEquals(
            10_000L,
            userNoticeDurationMillis(
                FoxholeBannerEvent("Saved", FoxholeBannerTone.INFO, durationMillis = Long.MAX_VALUE)
            )
        )
    }
}
