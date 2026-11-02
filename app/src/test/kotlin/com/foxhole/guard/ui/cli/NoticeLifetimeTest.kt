package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeLifetimeTest {
    @Test fun ordinaryNoticeExpiresAfterFifteenSeconds() {
        val lifetime = NoticeLifetime(1_000L)
        assertEquals(15_000L, lifetime.remainingMillis(1_000L))
        assertEquals(1L, lifetime.remainingMillis(15_999L))
        assertEquals(0L, lifetime.remainingMillis(16_000L))
    }

    @Test fun readingTheSameNoticeDoesNotRestartItsDeadline() {
        val lifetime = NoticeLifetime(1_000L)
        assertEquals(10_000L, lifetime.remainingMillis(6_000L))
        assertEquals(10_000L, lifetime.remainingMillis(6_000L))
        assertEquals(5_000L, lifetime.remainingMillis(11_000L))
    }

    @Test fun returningFromBackgroundUsesElapsedTime() {
        val lifetime = NoticeLifetime(1_000L)
        assertEquals(0.5f, lifetime.remainingFraction(8_500L), 0f)
        assertEquals(0f, lifetime.remainingFraction(60_000L), 0f)
    }

    @Test fun aNewNoticeGetsItsOwnFullLifetime() {
        val old = NoticeLifetime(1_000L)
        val next = NoticeLifetime(10_000L)
        assertEquals(6_000L, old.remainingMillis(10_000L))
        assertEquals(15_000L, next.remainingMillis(10_000L))
    }

    @Test fun aSavedNoticeDoesNotRestartAfterTheDeviceClockResets() {
        assertEquals(0L, NoticeLifetime(60_000L).remainingMillis(1_000L))
    }

    @Test fun onlyAnUpwardDragPastTheCardThresholdDismisses() {
        assertFalse(shouldDismissNoticeUp(60f, 100f, 48f))
        assertFalse(shouldDismissNoticeUp(-39f, 100f, 48f))
        assertTrue(shouldDismissNoticeUp(-40f, 100f, 48f))
    }

    @Test fun aTallNoticeDoesNotRequireAnExcessivelyLongSwipe() {
        assertTrue(shouldDismissNoticeUp(-48f, 400f, 48f))
        assertFalse(shouldDismissNoticeUp(-47f, 400f, 48f))
    }
}
