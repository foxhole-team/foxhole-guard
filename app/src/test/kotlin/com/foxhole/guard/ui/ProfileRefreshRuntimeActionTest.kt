package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRefreshRuntimeActionTest {
    @Test
    fun `replacement profile id forces reconnect onto replacement`() {
        assertEquals(
            ProfileRefreshRuntimeAction.RECONNECT,
            profileRefreshRuntimeAction(
                activeRuntime = true,
                previousProfileId = 7L,
                refreshedProfileId = 9L,
            ),
        )
    }

    @Test
    fun `stable active id can hot reload and idle runtime stays untouched`() {
        assertEquals(
            ProfileRefreshRuntimeAction.RELOAD,
            profileRefreshRuntimeAction(true, previousProfileId = 7L, refreshedProfileId = 7L),
        )
        assertEquals(
            ProfileRefreshRuntimeAction.NONE,
            profileRefreshRuntimeAction(false, previousProfileId = 7L, refreshedProfileId = 9L),
        )
    }
}
