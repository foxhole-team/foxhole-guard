package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.router.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardRouterPackageUpdatesAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unsupportedNeverSaysUpToDateOrOffersAnUpdate() {
        compose.setContent { GuardRouterTestTheme { Column {
            GuardRouterPackageUpdates(RouterUpdateControlState(supported = false), true, {})
            GuardRouterPackageUpdateFooter(RouterUpdateControlState(supported = false), true, {}, {}, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_unsupported)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_current)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_apply)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_check)).assertIsNotEnabled()
    }

    @Test fun uncertainOutcomeOffersOnlyStatusAndNeverApplyOrAnotherCheck() {
        var refresh = 0
        val state = RouterUpdateControlState(supported = true,
            pending = RouterUpdatePending(RouterUpdateOperation.APPLY, "update-plan-" + "a".repeat(32), null))
        compose.setContent { GuardRouterTestTheme { Column {
            GuardRouterPackageUpdates(state, true, {})
            GuardRouterPackageUpdateFooter(state, true, { error("must not check") }, { error("must not apply") }, { refresh++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_uncertain)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_apply)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_check)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_status)).performClick()
        compose.runOnIdle { assertEquals(1, refresh) }
    }

    @Test fun autoCheckboxSendsOneRequestAndWaitsForAuthoritativeReadback() {
        var requests = 0
        val status = RouterUpdateStatus(true, "a".repeat(64), 1, "1.0.0-r2", null, RouterUpdateAvailability.NOT_CHECKED,
            null, null, false, RouterUpdateAuto(false, 86400, null, null, null), emptyList(), null, null, null)
        compose.setContent { GuardRouterTestTheme { Column {
            GuardRouterPackageUpdates(RouterUpdateControlState(true, status), true, { requests++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_auto)).performClick().assertIsOff()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun legacyConnectionShowsDisconnectGuidanceWithoutCallingThePlatformUnsupported() {
        val status = RouterUpdateStatus(true, "a".repeat(64), 1, "1.0.0-r2", "1.0.1-r1", RouterUpdateAvailability.AVAILABLE,
            null, null, false, RouterUpdateAuto(false, 86400, null, null, RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE),
            emptyList(), null, null, null)
        compose.setContent { GuardRouterTestTheme { Column {
            GuardRouterPackageUpdates(RouterUpdateControlState(true, status), true, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_disconnect_before_update)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_unsupported)).assertDoesNotExist()
    }

}
