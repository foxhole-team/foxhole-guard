package com.foxhole.guard.router.ui

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardRouterNoticesTest {
    @Test fun newNoticeReplacesQueuedAndVisibleMessages() = runTest {
        val snackbar = SnackbarHostState()
        var now = 1_000L
        val notices = GuardRouterNotices(backgroundScope, snackbar, clock = { now })
        notices.show("Old")
        runCurrent()
        val old = requireNotNull(snackbar.currentSnackbarData)
        now = 2_000L
        notices.show("New")
        runCurrent()
        val current = requireNotNull(snackbar.currentSnackbarData)
        assertEquals("New", current.visuals.message)
        val visuals = current.visuals as GuardRouterNoticeVisuals
        assertEquals(2_000L, visuals.createdAtElapsedMs)
        assertEquals(10_000L, visuals.lifetime?.remainingMillis(7_000L))
        old.dismiss()
        assertSame(current, snackbar.currentSnackbarData)
        current.dismiss()
        runCurrent()
        assertNull(snackbar.currentSnackbarData)
    }

    @Test fun routerUsesTheSameAppWideNotificationSinkWithoutAQueue() = runTest {
        val snackbar = SnackbarHostState()
        val messages = mutableListOf<String>()
        val notices = GuardRouterNotices(backgroundScope, snackbar,
            forward = { message, _ -> messages.add(message) })
        notices.show("First")
        notices.show("Second")
        assertEquals(listOf("First", "Second"), messages)
        assertNull(snackbar.currentSnackbarData)
    }
}
