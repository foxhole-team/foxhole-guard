package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.R
import com.foxhole.guard.core.security.PasswordSetupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliPinPanelStateTest {

    @Test
    fun `enable flow starts at the new pin`() {
        assertEquals(CliPinStep.NEW, CliPinPanelState(CliPinFlow.ENABLE).step)
    }

    @Test
    fun `change and disable flows start at the current pin`() {
        assertEquals(CliPinStep.CURRENT, CliPinPanelState(CliPinFlow.CHANGE).step)
        assertEquals(CliPinStep.CURRENT, CliPinPanelState(CliPinFlow.DISABLE).step)
    }

    @Test
    fun `change flow asks for the new pin after the current one`() {
        val state = CliPinPanelState(CliPinFlow.CHANGE)
        var submitted = false
        state.advanceFrom(CliPinStep.CURRENT) { submitted = true }
        assertEquals(CliPinStep.NEW, state.step)
        assertFalse(submitted)
    }

    @Test
    fun `disable flow submits straight from the current pin`() {
        val state = CliPinPanelState(CliPinFlow.DISABLE)
        var submitted = false
        state.advanceFrom(CliPinStep.CURRENT) { submitted = true }
        assertTrue(submitted)
    }

    @Test
    fun `confirm mismatch resets to the new pin with an error`() {
        val state = CliPinPanelState(CliPinFlow.ENABLE)
        state.newPin = "111111"
        state.confirmPin = "222222"
        var submitted = false
        state.advanceFrom(CliPinStep.CONFIRM) { submitted = true }
        assertFalse(submitted)
        assertEquals(CliPinStep.NEW, state.step)
        assertEquals(R.string.cli_lock_err_mismatch, state.errorRes)
        assertEquals("", state.newPin)
        assertEquals("", state.confirmPin)
    }

    @Test
    fun `confirm match submits`() {
        val state = CliPinPanelState(CliPinFlow.ENABLE)
        state.newPin = "111111"
        state.confirmPin = "111111"
        var submitted = false
        state.advanceFrom(CliPinStep.CONFIRM) { submitted = true }
        assertTrue(submitted)
    }

    @Test
    fun `success closes the panel through DONE`() {
        val state = CliPinPanelState(CliPinFlow.ENABLE)
        assertTrue(state.applyResult(PasswordSetupResult.Success))
        assertEquals(CliPinStep.DONE, state.step)
    }

    @Test
    fun `wrong password returns to the current pin`() {
        val state = CliPinPanelState(CliPinFlow.CHANGE)
        state.currentPin = "111111"
        assertFalse(state.applyResult(PasswordSetupResult.WrongPassword))
        assertEquals(CliPinStep.CURRENT, state.step)
        assertEquals("", state.currentPin)
        assertEquals(R.string.cli_lock_err_wrong_pin, state.errorRes)
    }

    @Test
    fun `failure retries the pin entry of its flow`() {
        val enable = CliPinPanelState(CliPinFlow.ENABLE)
        assertFalse(enable.applyResult(PasswordSetupResult.Failed))
        assertEquals(CliPinStep.NEW, enable.step)

        val disable = CliPinPanelState(CliPinFlow.DISABLE)
        assertFalse(disable.applyResult(PasswordSetupResult.Failed))
        assertEquals(CliPinStep.CURRENT, disable.step)
    }

    @Test
    fun `advancing clears the previous error`() {
        val state = CliPinPanelState(CliPinFlow.CHANGE)
        state.applyResult(PasswordSetupResult.WrongPassword)
        state.advanceFrom(CliPinStep.CURRENT) {}
        assertNull(state.errorRes)
    }
}
