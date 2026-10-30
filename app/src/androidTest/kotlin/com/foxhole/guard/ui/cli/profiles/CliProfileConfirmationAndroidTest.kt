package com.foxhole.guard.ui.cli.profiles

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.components.CliButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CliProfileConfirmationAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun protocolSwitchWaitsForContinueAndCancelChangesNothing() {
        val open = mutableStateOf(true)
        var changes = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.cli_protocol_switch_confirmation_title)
        val body = context.getString(R.string.cli_protocol_switch_confirmation_body)
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliButton("Open confirmation", onClick = { open.value = true })
                if (open.value) {
                    CliProtocolSwitchConfirmation(
                        onContinue = {
                            changes++
                            open.value = false
                        },
                        onDismiss = { open.value = false },
                    )
                }
            }
        }
        compose.onNodeWithText(title, ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText(context.getString(R.string.cli_profile_confirmation_cancel), ignoreCase = true).performClick()
        compose.onNodeWithText(title, ignoreCase = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText("Open confirmation", ignoreCase = true).performClick()
        compose.onNodeWithText(context.getString(R.string.cli_profile_confirmation_continue), ignoreCase = true).performClick()
        compose.onNodeWithText(title, ignoreCase = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test
    fun deletionNamesTheProfileAndRequiresContinue() {
        val open = mutableStateOf(true)
        var deletions = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.cli_profile_delete_confirmation_title)
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliButton("Open confirmation", onClick = { open.value = true })
                if (open.value) {
                    CliProfileDeleteConfirmation(
                        profileName = "Owned test profile",
                        onContinue = {
                            deletions++
                            open.value = false
                        },
                        onDismiss = { open.value = false },
                    )
                }
            }
        }
        compose.onNodeWithText(title, ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cli_prof_delete_confirm, "Owned test profile")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cli_profile_confirmation_cancel), ignoreCase = true).performClick()
        compose.runOnIdle { assertEquals(0, deletions) }
        compose.onNodeWithText("Open confirmation", ignoreCase = true).performClick()
        compose.onNodeWithText(context.getString(R.string.cli_profile_confirmation_continue), ignoreCase = true).performClick()
        compose.onNodeWithText(title, ignoreCase = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, deletions) }
    }
}
