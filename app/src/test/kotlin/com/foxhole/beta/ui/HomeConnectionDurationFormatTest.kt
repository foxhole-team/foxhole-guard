package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConnectionDurationFormatTest {
    @Test
    fun `english duration keeps minutes and seconds readable`() {
        assertEquals("1 m 05 s", formatConnectionDuration(elapsedMs = 65_000L, locale = Locale.ENGLISH))
    }

    @Test
    fun `russian duration keeps hours and minutes readable`() {
        assertEquals(
            "1 ч 02 мин",
            formatConnectionDuration(elapsedMs = 3_726_000L, locale = Locale.forLanguageTag("ru")),
        )
    }

    @Test
    fun `connection duration only appears for active states`() {
        assertTrue(shouldShowConnectionDuration(ConnectionSnapshot(state = ConnectionState.CONNECTED)))
        assertTrue(shouldShowConnectionDuration(ConnectionSnapshot(state = ConnectionState.CONNECTING)))
        assertFalse(shouldShowConnectionDuration(ConnectionSnapshot(state = ConnectionState.IDLE)))
    }
}
