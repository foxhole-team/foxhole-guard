package com.foxhole.guard.ui.cli

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliTopNoticeHostTest {
    @Test
    fun parentIsReadyBeforeClosingSheetDisappears() {
        val windows = CliNoticeWindowRegistry()
        val activity = Any()
        val sheet = Any()
        windows.add(activity)
        windows.add(sheet)
        assertFalse(windows.draws(activity))
        windows.handoff(sheet, true)
        assertTrue(windows.draws(activity))
        windows.remove(sheet)
        assertTrue(windows.draws(activity))
    }

    @Test
    fun interruptedCloseDoesNotRepeatTheNotice() {
        val windows = CliNoticeWindowRegistry()
        val activity = Any()
        val sheet = Any()
        windows.add(activity)
        windows.add(sheet)
        windows.handoff(sheet, true)
        windows.handoff(sheet, false)
        assertFalse(windows.draws(activity))
        assertTrue(windows.draws(sheet))
        val motion = NoticeDismissState()
        motion.offsetY = -12f
        motion.dismissing = true
        assertEquals(-12f, motion.offsetY, 0f)
        assertTrue(motion.dismissing)
    }

    @Test
    fun `the newest window owns notices until it is removed`() {
        val windows = CliNoticeWindowRegistry()
        val activity = Any()
        val sheet = Any()
        val editor = Any()
        windows.add(activity)
        windows.add(sheet)
        windows.add(editor)

        assertFalse(windows.isForemost(activity))
        assertFalse(windows.isForemost(sheet))
        assertTrue(windows.isForemost(editor))
        windows.remove(editor)
        assertTrue(windows.isForemost(sheet))
        windows.remove(sheet)
        assertTrue(windows.isForemost(activity))
        windows.remove(activity)
        assertFalse(windows.isForemost(activity))
    }

    @Test
    fun `removing an older window never displaces a newer window`() {
        val windows = CliNoticeWindowRegistry()
        val activity = Any()
        val first = Any()
        val second = Any()
        windows.add(activity)
        windows.add(first)
        windows.add(second)
        windows.add(activity)
        windows.remove(first)

        assertTrue(windows.isForemost(second))
    }

    @Test
    fun `floating dialog coordinates still position the notice at screen top`() {
        val position = cliTopNoticePosition(
            frame = IntRect(0, 24, 400, 760),
            windowOrigin = IntOffset(40, 300),
            contentSize = IntSize(360, 60),
            systemTop = 24,
            margin = 8,
        )

        assertEquals(IntOffset(-20, -268), position)
        assertEquals(IntOffset(20, 32), position + IntOffset(40, 300))
    }

    @Test
    fun `edge to edge placement never moves above the safe status bar boundary`() {
        assertEquals(
            IntOffset(20, 32),
            cliTopNoticePosition(IntRect(0, 0, 400, 760), IntOffset.Zero, IntSize(360, 60), 24, 8),
        )
        assertEquals(
            IntOffset(20, 24),
            cliTopNoticePosition(IntRect(0, 24, 400, 80), IntOffset.Zero, IntSize(360, 70), 24, 8),
        )
    }
}
