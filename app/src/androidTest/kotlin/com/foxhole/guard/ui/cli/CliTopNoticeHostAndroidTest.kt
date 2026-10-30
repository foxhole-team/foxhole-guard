package com.foxhole.guard.ui.cli

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.FoxholeBannerTone
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliInputModal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class CliTopNoticeHostAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun copyFeedbackAppearsAtScreenTopAboveASheetWithoutDismissingIt() {
        val sheet = mutableStateOf(true)
        val shown = mutableStateOf(false)
        var actions = 0
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliTopNoticeProvider(shown.value, noticeContent = {
                    CliUserNoticeRow(FoxholeBannerEvent("Profile copied", FoxholeBannerTone.INFO)) { shown.value = false }
                }) {
                    CliTopNoticeHost()
                    if (sheet.value) {
                        CliBottomSheet(onDismiss = { sheet.value = false }, modifier = Modifier.testTag("owned-sheet")) {
                            Text("Owned sheet")
                            CliButton("Copy profile", onClick = { shown.value = true })
                            CliButton("Do work", onClick = { actions++ })
                        }
                    }
                }
            }
        }
        val before = sheetBounds()
        compose.onNodeWithText("Copy profile", ignoreCase = true).performClick()
        assertSingleTopNotice("Profile copied")
        assertEquals("Feedback changed the sheet size or screen position", before, sheetBounds())
        compose.onNodeWithText("Owned sheet").assertIsDisplayed()
        compose.onNodeWithText("Do work", ignoreCase = true).performClick()
        compose.runOnIdle {
            assertTrue(sheet.value)
            assertTrue(actions == 1)
        }
    }

    @Test
    fun routingDropdownFeedbackDoesNotResizeOrMoveTheOpenSheet() {
        val notice = mutableStateOf<FoxholeBannerEvent?>(null)
        val selected = mutableStateOf("VPN")
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliTopNoticeProvider(notice.value != null, noticeContent = {
                    notice.value?.let { CliUserNoticeRow(it) { notice.value = null } }
                }) {
                    CliTopNoticeHost()
                    CliBottomSheet(onDismiss = {}, modifier = Modifier.testTag("owned-sheet")) {
                        Text("Owned routing sheet")
                        CliDropdownRow(
                            label = "Routing", value = selected.value,
                            options = listOf(CliDropdownOption("VPN", "VPN"), CliDropdownOption("Tor", "Tor")),
                            selectedId = selected.value,
                            onSelect = { id ->
                                selected.value = id
                                notice.value = FoxholeBannerEvent("Selected $id", FoxholeBannerTone.SUCCESS)
                            },
                        )
                    }
                }
            }
        }
        val before = sheetBounds()
        compose.onNodeWithText("Routing", ignoreCase = true).performClick()
        compose.onNodeWithText("Tor", ignoreCase = true).performClick()
        assertSingleTopNotice("Selected Tor")
        assertEquals(before, sheetBounds())
        compose.onNodeWithText("Routing", ignoreCase = true).performClick()
        compose.onNodeWithText("VPN", ignoreCase = true).performClick()
        assertSingleTopNotice("Selected VPN")
        assertEquals(before, sheetBounds())
        compose.runOnIdle { notice.value = null }
        compose.waitForIdle()
        assertEquals(before, sheetBounds())
        compose.onNodeWithText("Owned routing sheet").assertIsDisplayed()
    }

    @Test
    fun anExistingErrorMovesToAFloatingDialogAndReturnsToTheActivityOnlyOnce() {
        val dialog = mutableStateOf(false)
        val shown = mutableStateOf(true)
        val value = mutableStateOf("Owned value")
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliTopNoticeProvider(shown.value, noticeContent = {
                    CliUserNoticeRow(FoxholeBannerEvent("Connection failed", FoxholeBannerTone.ERROR)) { shown.value = false }
                }) {
                    CliTopNoticeHost()
                    Text("Owned activity", Modifier.fillMaxSize())
                    if (dialog.value) {
                        CliInputModal(
                            title = "Owned input", prompt = "value", value = value.value,
                            onValueChange = { value.value = it }, onSubmit = {}, onDismiss = { dialog.value = false },
                            belowInput = { CliButton("Close input", onClick = { dialog.value = false }) },
                        )
                    }
                }
            }
        }
        assertSingleTopNotice("Connection failed")
        compose.runOnIdle { dialog.value = true }
        compose.onNodeWithText("Owned value").assertIsDisplayed()
        assertSingleTopNotice("Connection failed")
        compose.onNodeWithText("Close input", ignoreCase = true).performClick()
        assertSingleTopNotice("Connection failed")
        compose.runOnIdle {
            assertFalse(dialog.value)
            assertTrue(shown.value)
        }
    }

    @Test
    fun aHiddenPresentationDoesNotExposeAnErrorInAnyWindow() {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliTopNoticeProvider(false, noticeContent = {
                    CliUserNoticeRow(FoxholeBannerEvent("Private pending message", FoxholeBannerTone.ERROR)) {}
                }) {
                    CliTopNoticeHost()
                    CliBottomSheet(onDismiss = {}) { Text("Owned sheet") }
                }
            }
        }
        compose.onNodeWithText("Owned sheet").assertIsDisplayed()
        compose.onAllNodesWithText("Private pending message").assertCountEquals(0)
    }

    private fun assertSingleTopNotice(message: String) {
        compose.waitForIdle()
        compose.onAllNodesWithText(message).assertCountEquals(1)
        val notice = compose.onNodeWithText(message).assertIsDisplayed().fetchSemanticsNode()
        val screenHeight = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
        assertTrue("Notice is inside the floating dialog instead of the screen top", notice.positionOnScreen.y < screenHeight / 4f)
        assertTrue(notice.positionOnScreen.y >= 0f)
    }

    private fun sheetBounds(): Rect {
        compose.waitForIdle()
        val node = compose.onNodeWithTag("owned-sheet").fetchSemanticsNode()
        return Rect(
            node.positionOnScreen.x, node.positionOnScreen.y,
            node.positionOnScreen.x + node.size.width, node.positionOnScreen.y + node.size.height,
        )
    }
}
