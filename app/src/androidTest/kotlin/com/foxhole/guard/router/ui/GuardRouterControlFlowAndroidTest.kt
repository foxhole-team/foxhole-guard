package com.foxhole.guard.router.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterControlCoordinator
import com.foxhole.guard.router.RouterControlStage
import com.foxhole.guard.router.RouterPairingStore
import com.foxhole.guard.router.RouterProfileSelection
import com.foxhole.guard.router.RouterWizardCoordinator
import com.foxhole.guard.router.RouterWizardPath
import com.foxhole.guard.router.RouterWizardStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import java.net.URI
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GuardRouterControlFlowAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var fixture: ReviewControlFixture? = null

    @After fun closeFixture() { fixture?.close() }

    private fun show(publicIp: Boolean, routers: Int = 1, client: Boolean = false): ReviewControlFixture {
        val value = ReviewControlFixture(publicIp, routers, client)
        fixture = value
        compose.setContent { GuardRouterTestTheme { GuardRouterNoticeScope {
            GuardRouterControlSheet(value.control, onPair = {}, onUnpaired = {},
                requestAccess = { it() }, requestHomeEnable = { it() }, onClose = {}, onAddRouter = { value.added++ })
        } } }
        compose.waitUntil(10_000) { value.control.state.value.stage == RouterControlStage.CONNECTED && !value.control.state.value.busy }
        compose.waitForIdle()
        return value
    }

    private fun switch(id: Int): SemanticsNodeInteraction {
        val label = context.getString(id)
        // ControlSwitch's Text is a direct layout child of its Row; semantics siblings flatten that Row.
        val row = requireNotNull(
            compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.parentInfo,
        )
        return compose.onNode(
            isToggleable() and SemanticsMatcher("toggle in the layout row labelled '$label'") { node ->
                generateSequence(node.layoutInfo.parentInfo) { it.parentInfo }.any { it === row }
            },
            useUnmergedTree = true,
        )
    }

    @Test fun noPublicIpStartsAtRouterVpnWithOneSettingsEntryAndNoClientCards() {
        show(false)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_management)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_vpn_clients)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.guard_router_router_review_add_router)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_direct_ip)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_settings)).performScrollTo().performClick()
        switch(R.string.guard_router_router_review_direct_ip).performScrollTo().assertIsDisplayed().assertIsOff()
    }

    @Test fun homeDevicesFollowVpnProfilesAndSettingsFollowTheExpandedDevices() {
        val value = show(false)
        val profiles = compose.onNodeWithText(context.getString(R.string.guard_router_router_review_profiles)).fetchSemanticsNode().boundsInRoot
        val devices = compose.onNodeWithText(context.getString(R.string.guard_router_router_control_lan_clients))
        assertTrue(profiles.bottom <= devices.fetchSemanticsNode().boundsInRoot.top)
        devices.performClick()
        compose.waitUntil(10_000) { value.control.state.value.lanDevices.isNotEmpty() && !value.control.state.value.busy }
        val settings = compose.onNodeWithText(context.getString(R.string.guard_router_router_review_settings)).performScrollTo()
        val device = compose.onNodeWithText("Demo laptop").fetchSemanticsNode().boundsInRoot
        assertTrue(device.bottom <= settings.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun enablingPublicIpKeepsTheCurrentSettingsAndMakesClientsReachableThroughBack() {
        val value = show(false)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_settings)).performScrollTo().performClick()
        switch(R.string.guard_router_router_review_direct_ip).performScrollTo().performClick()
        compose.waitUntil(10_000) { value.control.state.value.preferences?.publicIpConfirmed == true }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_vpn_clients)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.guard_router_router_review_unpair)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_settings)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_vpn_clients)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_router_vpn)).assertIsDisplayed()
    }

    @Test fun nestedVpnPageHasNoForgetOrAddRouterIcons() {
        show(true)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_router_vpn)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.guard_router_router_review_unpair)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.guard_router_router_review_add_router)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_settings)).performScrollTo().performClick()
        switch(R.string.guard_router_router_review_direct_ip).performScrollTo().assertIsDisplayed()
    }

    @Test fun aHomeDeviceOpensOnTapAndRoutesWithoutPublicIp() {
        val value = show(false)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_lan_clients)).performScrollTo().performClick()
        compose.waitUntil(10_000) { value.control.state.value.lanDevices.isNotEmpty() && !value.control.state.value.busy }
        compose.onNodeWithText("Demo laptop").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_device_routing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_use_direct)).assertDoesNotExist()
        val currentRoute = when (value.control.state.value.lanDevices.single { it.name == "Demo laptop" }.route) {
            "vpn" -> R.string.guard_router_router_review_device_vpn
            "direct" -> R.string.guard_router_router_review_direct
            else -> R.string.guard_router_router_review_router_default
        }
        compose.onNodeWithText(context.getString(currentRoute)).performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_direct)).performClick()
        compose.waitUntil(10_000) { value.control.state.value.lanDevices.any { it.name == "Demo laptop" && it.route == "direct" } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_device_routing)).assertIsDisplayed()
    }

    @Test fun aRemoteProfileLoadsQrImmediatelyAndKeepsShareRemoveInOneFooterRow() {
        val value = show(true, client = true)
        assertNull(value.control.state.value.snapshot?.desiredServerId)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_vpn_clients)).performClick()
        compose.waitUntil(10_000) { !value.control.state.value.busy }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_create_profile)).assertIsEnabled()
        compose.onNodeWithText("Review client").performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_client_details)).assertIsDisplayed()
        val qr = context.getString(R.string.guard_router_router_control_qr_description, "Review client")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(qr).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_client_actions_button)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_show_qr)).assertDoesNotExist()
        val share = compose.onNodeWithText(context.getString(R.string.guard_router_router_review_share)).fetchSemanticsNode().boundsInRoot
        val remove = compose.onNodeWithText(context.getString(R.string.guard_router_vpn_remove)).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(share.center.y - remove.center.y) <= 1f)
        assertTrue(abs(share.width - remove.width) <= 1f)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_import_phone)).performScrollTo().performClick()
        compose.waitUntil(10_000) { value.imported == 1 }
        compose.runOnIdle { assertEquals("Review client", URI(value.importedProfile).fragment) }
    }

    @Test fun theRouterVpnButtonSwitchesTheSelectedProfileThenDisconnectsWithoutASecondToggle() {
        val value = show(false)
        val id = (requireNotNull(value.control.state.value.snapshot).servers.single()["id"] as JsonPrimitive).content
        compose.runOnIdle { value.control.selectServer(id) }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_start_selected)).performClick()
        compose.waitUntil(10_000) { value.control.state.value.snapshot?.desiredServerId == id && !value.control.state.value.busy }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_control_vpn)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_disconnect)).assertIsDisplayed()
        runBlocking {
            value.control.sendProfile("Review second", "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443" +
                "?encryption=none&security=tls&sni=1.1.1.1&type=grpc&serviceName=Demo&mode=gun#ReviewSecond")
        }
        val secondId = requireNotNull(value.control.state.value.selectedServerId)
        assertTrue(secondId != id)
        assertNull(value.control.state.value.error)
        assertEquals(id, value.control.state.value.snapshot?.desiredServerId)
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_switch_profile)).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { value.control.state.value.snapshot?.desiredServerId == secondId && !value.control.state.value.busy }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_disconnect)).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { value.control.state.value.snapshot?.desiredServerId == null && !value.control.state.value.busy }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_start_selected)).assertIsDisplayed()
    }

    @Test fun routerManagementDoesNotDisplayTheRouterAddress() {
        show(true)
        compose.onNodeWithText("192.168.1.1").assertDoesNotExist()
    }

    @Test fun threeSavedRoutersDisableAddingAndPutTheSelectorAboveTheVector() {
        val value = show(true, routers = 3)
        compose.onNodeWithContentDescription(context.getString(R.string.guard_router_router_review_add_router)).assertIsNotEnabled()
        val first = context.getString(R.string.guard_router_router_review_saved_number, 1)
        val chooser = compose.onNodeWithText(first).fetchSemanticsNode().boundsInRoot
        val vector = compose.onNodeWithTag("router-management-vector").fetchSemanticsNode().boundsInRoot
        assertTrue(chooser.bottom <= vector.top)
        compose.onNodeWithText(first).performClick()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_review_saved_number, 2)).performClick()
        compose.waitUntil(10_000) { value.control.state.value.selectedRouterId == value.ids[1] && !value.control.state.value.busy }
        compose.runOnIdle { assertEquals(0, value.added) }
    }
}

private class ReviewControlFixture(publicIp: Boolean, routers: Int, client: Boolean) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val type = Class.forName("com.foxhole.guard.router.review.RouterReviewBackend")
    private val suspendFunctionType = Class.forName("kotlin.jvm.functions.Function2")
    private val backend = type.getDeclaredConstructor(CoroutineScope::class.java, CoroutineDispatcher::class.java,
        java.lang.Long.TYPE, java.lang.Boolean.TYPE).newInstance(scope, Dispatchers.Default, 0L, true)
    private val store = type.getMethod("getPairingStore").invoke(backend) as RouterPairingStore
    @Volatile var imported = 0
    @Volatile var importedProfile = ""
    var added = 0
    val ids = mutableListOf<String>()
    val control: RouterControlCoordinator

    init {
        val noSelection: suspend (String) -> RouterProfileSelection? = { null }
        val importRemote: suspend (String) -> Boolean = { true }
        runBlocking {
            repeat(routers) { index ->
                val wizard = type.getMethod("wizard", String::class.java, suspendFunctionType,
                    suspendFunctionType).invoke(backend, "Review phone", noSelection, importRemote) as RouterWizardCoordinator
                wizard.begin(RouterWizardPath.PAIR, null, addingNew = index > 0)
                wizard.inspectAddress("192.168.1.1")
                wizard.acceptIdentity()
                wizard.login("1234".toCharArray())
                check(wizard.state.value.stage == RouterWizardStage.NEW_PIN)
                wizard.changePin("5678".toCharArray())
                check(wizard.state.value.stage == RouterWizardStage.OPTIONS)
                wizard.options(wizard.state.value.options.copy(publicIp = publicIp, registerPhone = false))
                wizard.finish()
                check(wizard.state.value.stage == RouterWizardStage.COMPLETE)
                ids += requireNotNull(wizard.state.value.pairedId)
            }
            val saved = store.readAll()
            try {
                assertEquals(routers, saved.size)
                assertEquals(routers, ids.distinct().size)
                assertEquals(ids.toSet(), saved.map { it.id }.toSet())
                assertEquals(routers, saved.map { it.identity.certificateSha256 }.distinct().size)
                assertTrue(saved.all { it.active })
            } finally {
                saved.forEach { it.clear() }
            }
            store.selectSaved(ids.first())
        }
        val copy: suspend (String) -> Unit = {}
        val onImport: suspend (String) -> Boolean = { importedProfile = it; imported++; true }
        control = type.getMethod("controls", suspendFunctionType, suspendFunctionType)
            .invoke(backend, copy, onImport) as RouterControlCoordinator
        if (client) runBlocking { control.open(); control.createRemoteClient("Review client"); check(control.state.value.error == null) }
    }

    override fun close() { control.close(); (backend as AutoCloseable).close(); scope.cancel() }
}
