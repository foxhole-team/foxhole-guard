package com.foxhole.beta.ui

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.ui.theme.FoxholeTheme
import org.junit.Rule
import org.junit.Test

class HomeProfileLoadingLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingBlockReservesDashboardProfileHeight() {
        composeRule.setContent {
            FoxholeTheme(themeMode = ThemeMode.LIGHT) {
                HomeProfileLoadingBlock()
            }
        }

        composeRule
            .onNodeWithTag(HOME_PROFILE_LOADING_TAG)
            .assertHeightIsEqualTo(HomeDashboardProfileContentHeight)
    }
}
