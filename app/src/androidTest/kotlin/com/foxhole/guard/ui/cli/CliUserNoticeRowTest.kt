package com.foxhole.guard.ui.cli

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.FoxholeBannerTone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliUserNoticeRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun noticesKeepTheirSemanticColorsInMonochrome() {
        val tone = mutableStateOf(FoxholeBannerTone.SUCCESS)
        compose.setContent {
            CliTheme(pixelArtEnabled = false, monochromeEnabled = true) {
                CliUserNoticeRow(FoxholeBannerEvent("Connection feedback", tone.value)) {}
            }
        }
        FoxholeBannerTone.entries.forEach { current ->
            compose.runOnIdle { tone.value = current }
            val pixels = compose.onNodeWithText("Connection feedback").captureToImage().toPixelMap()
            var found = false
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    found = found || when (current) {
                        FoxholeBannerTone.SUCCESS -> color.green > color.red + 0.15f && color.green > color.blue + 0.15f
                        FoxholeBannerTone.INFO -> color.blue > color.red + 0.15f && color.blue > color.green + 0.15f
                        FoxholeBannerTone.WARNING -> color.red > color.green + 0.15f && color.green > color.blue + 0.15f
                        FoxholeBannerTone.ERROR -> color.red > color.green + 0.15f && color.red > color.blue + 0.15f
                    }
                }
            }
            assertTrue("$current notice was rendered without its semantic color", found)
        }
    }

    @Test
    fun criticalNoticeIsReadableAndCanBeAcknowledgedWithoutConsole() {
        val visible = mutableStateOf(true)
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                if (visible.value) {
                    CliUserNoticeRow(
                        notice = FoxholeBannerEvent(message = "Connection failed", tone = FoxholeBannerTone.ERROR),
                        onDismiss = { visible.value = false },
                    )
                }
            }
        }
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        val ok = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cli_common_btn_ok)
        compose.onNodeWithText(ok, ignoreCase = true).performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
}
