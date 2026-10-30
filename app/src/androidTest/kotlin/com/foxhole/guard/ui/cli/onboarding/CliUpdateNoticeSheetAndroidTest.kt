package com.foxhole.guard.ui.cli.onboarding

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class CliUpdateNoticeSheetAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianRouterBulletWrapsWithoutTruncationInTheActualNoticeSheet() = assertRouterText(
        "ru", "Поддержка установки и управления VPN на роутере с установленным пакетом FoxHole OpenWrt Client прямо из приложения.",
    )

    @Test fun englishRouterBulletWrapsWithoutTruncationInTheActualNoticeSheet() = assertRouterText(
        "en", "Install and manage VPN on a router running FoxHole OpenWrt Client, directly from the app.",
    )

    private fun assertRouterText(locale: String, expected: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
            ) {
                CliTheme { CliUpdateNoticeSheetContent(showMonochromeChoice = false, onDismiss = {}) }
            }
        }
        val text = compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get -> assertTrue(get(layouts)) }
        compose.runOnIdle {
            val layout = layouts.single()
            assertEquals(expected, layout.layoutInput.text.text)
            assertFalse(layout.hasVisualOverflow)
            assertEquals(expected.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }
}
