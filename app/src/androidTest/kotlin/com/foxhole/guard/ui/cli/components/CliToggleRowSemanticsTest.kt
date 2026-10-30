package com.foxhole.guard.ui.cli.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CliToggleRowSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchReportsItsStateAndChangesItThroughTheRowAction() {
        val checked = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliToggleRow(label = "Protection", checked = checked.value, onToggle = {
                    calls++
                    checked.value = it
                })
            }
        }
        val row = compose.onNodeWithText("Protection")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        row.performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun disabledSwitchStillReportsItsCurrentState() {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliToggleRow(label = "Protection", checked = true, enabled = false, onToggle = {})
            }
        }
        compose.onNodeWithText("Protection").assertIsOn().assertIsNotEnabled()
    }
}
