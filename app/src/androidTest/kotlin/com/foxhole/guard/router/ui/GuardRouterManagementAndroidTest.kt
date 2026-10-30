package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GuardRouterManagementAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sharingAndRemovalRemainEqualWidthOnOneRow() {
        var shared = 0
        var removed = 0
        compose.setContent { GuardRouterTestTheme {
            GuardRouterProfileActionsFooter(true, { shared++ }, { removed++ })
        } }
        val share = compose.onNodeWithText(context.getString(R.string.guard_router_router_review_share))
        val remove = compose.onNodeWithText(context.getString(R.string.guard_router_vpn_remove))
        share.assertIsDisplayed().performClick()
        remove.assertIsDisplayed().performClick()
        val a = share.fetchSemanticsNode().boundsInRoot
        val b = remove.fetchSemanticsNode().boundsInRoot
        assertTrue(abs(a.center.y - b.center.y) <= 1f)
        assertTrue(a.left < b.left)
        assertTrue(abs(a.width - b.width) <= 1f)
        compose.runOnIdle { assertEquals(1, shared); assertEquals(1, removed) }
    }

    @Test fun remoteProfileShowsInlineImportWithoutActionsOrQrButtons() {
        var imported = 0
        compose.setContent { GuardRouterTestTheme {
            Column { GuardRouterProfileManagementContent("Review profile", "Hysteria2",
                "hysteria2://SyntheticOnly@router.example:443#Review", false, true, true) { imported++ } }
        } }
        compose.onNodeWithText("Review profile").assertIsDisplayed()
        compose.onNodeWithText("Hysteria2").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_client_actions_button)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_show_qr)).assertDoesNotExist()
        val qr = context.getString(R.string.guard_router_router_control_qr_description, "Review profile")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(qr).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_import_phone)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, imported) }
    }

    @Test fun aDeviceCannotSelectAnInactiveGlobalRouterVpnProfile() {
        var selected: Boolean? = null
        compose.setContent { GuardRouterTestTheme { GuardRouterDeviceRouteDropdown(null, true, false) { selected = it } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_router_default)).performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_device_vpn)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_direct)).performClick()
        compose.runOnIdle { assertEquals(false, selected) }
    }

    @Test fun aDeviceUsesTheGlobalRouterVpnChoiceWithoutIndependentProfiles() {
        var selected: Boolean? = null
        compose.setContent { GuardRouterTestTheme { GuardRouterDeviceRouteDropdown("direct", true, true) { selected = it } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_direct)).performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_device_vpn)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, selected) }
    }
}
