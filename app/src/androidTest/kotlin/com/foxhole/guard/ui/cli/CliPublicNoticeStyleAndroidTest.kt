package com.foxhole.guard.ui.cli

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class CliPublicNoticeStyleAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disabledTopNoticesKeepScreenContentVisible() {
        compose.setContent {
            CliTopNoticeProvider(
                visible = true,
                noticeContent = { Text("Experimental notice") },
            ) {
                Text("Screen content")
                CliTopNoticeHost()
            }
        }
        compose.onNodeWithText("Screen content").assertIsDisplayed()
        compose.onNodeWithText("Experimental notice").assertDoesNotExist()
    }
}
