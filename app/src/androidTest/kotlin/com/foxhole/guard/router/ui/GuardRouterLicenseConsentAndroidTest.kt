package com.foxhole.guard.router.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterStorageMode
import com.foxhole.guard.router.RouterWizardPath
import com.foxhole.guard.router.RouterWizardStage
import com.foxhole.guard.router.RouterWizardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardRouterLicenseConsentAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freshInstallationWaitsForAnExplicitAgreementWithoutChangingAutoUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val agreement = context.getString(R.string.guard_router_setup_license_body)
        assertEquals(5, agreement.split("\n\n").size)
        assertFalse(agreement.contains("\\n"))
        val state = mutableStateOf(RouterWizardState(stage = RouterWizardStage.INSTALL, path = RouterWizardPath.SETUP,
            openWrtVersion = "25.12.5", architecture = "x86_64", storageMode = RouterStorageMode.FLASH))
        var installed = 0
        val actions = RouterWizardActions(inspect = {}, trust = {}, installProtocols = {}, confirmInstallProtocols = {},
            login = {}, newPin = {}, install = { installed++ }, options = { state.value = state.value.copy(options = it) },
            finish = {}, failure = {})
        compose.setContent {
            GuardRouterTestTheme {
                GuardRouterWizardSheet(state.value, emptyList(), false, false, null, actions)
            }
        }
        val install = compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_install))
        install.assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.guard_router_setup_license_accept))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        install.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, installed)
            assertTrue(state.value.options.licenseAccepted)
            assertTrue(state.value.options.autoUpdateEnabled)
        }
    }
}
