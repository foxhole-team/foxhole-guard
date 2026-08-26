package com.foxhole.guard.ui.cli.home

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.applyRoutingModePreset
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.onboardingCompletedRule
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.ui.cli.CliScreen
import com.foxhole.guard.ui.cli.cliScreenTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import kotlin.math.abs

class CliHomeMotionTest {
    private val composeRule = createAndroidComposeRule<CliMainActivity>()

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain.outerRule(onboardingCompletedRule()).around(composeRule)

    @Before
    fun resetState() {
        updateTorPermission(false)
        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(), refreshLastChangeAt = false)
        }
        waitForHomeReady()
        waitForInitialNetworkRefresh()
        waitForModeButtonCount(0)
    }

    @After
    fun clearState() {
        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(), refreshLastChangeAt = false)
        }
        updateTorPermission(false)
    }

    @Test
    fun primaryButtonMorphsBetweenOneAndTwoColumnsAndSurvivesInterruption() {
        val wide = primaryBounds()

        updateTorPermission(true)
        waitForModeButtonCount(1)
        composeRule.waitForIdle()
        val narrowPrimary = primaryBounds()
        val mode = modeBounds()

        assertTrue("Primary button did not shrink", narrowPrimary.width < wide.width * 0.75f)
        assertTrue(
            "Two-column buttons have different widths",
            abs(narrowPrimary.width - mode.width) <= geometryTolerancePx(),
        )

        updateTorPermission(false)
        SystemClock.sleep(INTERRUPTION_DELAY_MS)
        updateTorPermission(true)
        SystemClock.sleep(INTERRUPTION_DELAY_MS)
        updateTorPermission(false)

        waitForModeButtonCount(0)
        composeRule.onAllNodesWithTag(CLI_HOME_PRIMARY_ACTION_TAG).assertCountEquals(1)
        val recovered = primaryBounds()
        assertTrue(
            "Interrupted morph did not restore the single-column width",
            abs(recovered.width - wide.width) <= geometryTolerancePx(),
        )
    }

    @Test
    fun coldHomeSurfacesSettleAsSingleStableGeometry() {
        val facts = factsBounds()
        val primary = primaryBounds()
        val terminal = terminalBounds()

        SystemClock.sleep(HOME_STABILITY_WINDOW_MS)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(CLI_HOME_FACTS_TAG).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CLI_HOME_PRIMARY_ACTION_TAG).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CLI_HOME_TERMINAL_TAG).assertCountEquals(1)
        assertBoundsStable("facts", facts, factsBounds())
        assertBoundsStable("primary action", primary, primaryBounds())
        assertBoundsStable("terminal", terminal, terminalBounds())
    }

    @Test
    fun torIdentityRowExpandsAndCollapsesTheFactsPanel() {
        val vpn =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = TEST_PROFILE_ID,
                profileName = "Motion profile",
            )
        val vpnInfo = testIpInfo(TEST_VPN_IP, "US")
        val torInfo = testIpInfo(TEST_TOR_IP, "NL")
        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.updateIpInfo(vpnInfo)
            FoxholeVpnRuntimeBridge.update(vpn, refreshLastChangeAt = false)
        }
        waitForText(TEST_VPN_IP, present = true)
        val vpnOnlyHeight = factsBounds().height

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.updateTorRouteIpInfo(torInfo)
            FoxholeVpnRuntimeBridge.update(
                vpn.copy(
                    torActive = true,
                    appliedTorRoute =
                        AppliedTorRoute(
                            scope = PrivacyRouteScope.ALL_APPS,
                            bypassVpnTunnel = false,
                        ),
                ),
                refreshLastChangeAt = false,
            )
        }
        waitForText(torIdentityLabel(), present = true)
        composeRule.waitForIdle()
        val vpnTorHeight = factsBounds().height
        assertTrue("TOR identity row did not expand the facts panel", vpnTorHeight > vpnOnlyHeight)

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.update(vpn, refreshLastChangeAt = false)
            FoxholeVpnRuntimeBridge.updateTorRouteIpInfo(null)
        }
        waitForText(torIdentityLabel(), present = false)
        composeRule.waitForIdle()
        val restoredHeight = factsBounds().height
        assertTrue(
            "TOR identity exit left a permanent layout gap",
            abs(restoredHeight - vpnOnlyHeight) <= geometryTolerancePx(),
        )
    }

    @Test
    fun profileSelectorForwardBackKeepsTheActionRowAnchored() {
        val primaryTop = primaryBounds().top
        composeRule.onNodeWithTag(CLI_HOME_FACTS_TAG).performClick()
        waitForFactsCount(0)
        waitForText(profileSelectorTitle(), present = true, ignoreCase = true)
        val selectorTitle =
            composeRule.onNodeWithText(
                profileSelectorTitle(),
                ignoreCase = true,
                useUnmergedTree = true,
            )
        selectorTitle.assertIsDisplayed()
        val selectorPrimaryTop = primaryBounds().top
        assertTrue(
            "Profile selector shifted the action row",
            abs(selectorPrimaryTop - primaryTop) <= geometryTolerancePx(),
        )

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForFactsCount(1)
        composeRule.onAllNodesWithTag(CLI_HOME_FACTS_TAG).assertCountEquals(1)
        val restoredPrimaryTop = primaryBounds().top
        assertTrue(
            "Profile selector back transition did not restore geometry",
            abs(restoredPrimaryTop - primaryTop) <= geometryTolerancePx(),
        )
    }

    private fun waitForHomeReady() {
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithTag(cliScreenTag(CliScreen.HOME), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                composeRule
                    .onAllNodesWithTag(CLI_HOME_PRIMARY_ACTION_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size == 1
        }
    }

    private fun waitForInitialNetworkRefresh() {
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]
        val settleNotBefore = SystemClock.uptimeMillis() + INITIAL_NETWORK_SETTLE_MS
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            SystemClock.uptimeMillis() >= settleNotBefore &&
                viewModel.activeIpInfoRefreshReason == null &&
                viewModel.ipInfoRefreshJob?.isActive != true
        }
    }

    private fun waitForModeButtonCount(expected: Int) {
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithTag(CLI_HOME_MODE_ACTION_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == expected
        }
    }

    private fun waitForFactsCount(expected: Int) {
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithTag(CLI_HOME_FACTS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == expected
        }
    }

    private fun waitForText(
        text: String,
        present: Boolean,
        ignoreCase: Boolean = false,
    ) {
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(
                    text,
                    substring = true,
                    ignoreCase = ignoreCase,
                    useUnmergedTree = true,
                )
                .fetchSemanticsNodes()
                .isNotEmpty() == present
        }
    }

    private fun primaryBounds(): Rect =
        composeRule
            .onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun modeBounds(): Rect =
        composeRule
            .onNodeWithTag(CLI_HOME_MODE_ACTION_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun factsBounds(): Rect =
        composeRule
            .onNodeWithTag(CLI_HOME_FACTS_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun terminalBounds(): Rect =
        composeRule
            .onNodeWithTag(CLI_HOME_TERMINAL_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun assertBoundsStable(label: String, before: Rect, after: Rect) {
        val tolerance = geometryTolerancePx()
        assertTrue("$label moved horizontally after settling", abs(after.left - before.left) <= tolerance)
        assertTrue("$label moved vertically after settling", abs(after.top - before.top) <= tolerance)
        assertTrue("$label changed width after settling", abs(after.width - before.width) <= tolerance)
        assertTrue("$label changed height after settling", abs(after.height - before.height) <= tolerance)
    }

    private fun updateTorPermission(permitted: Boolean) {
        runBlocking {
            with(app().container.settingsRepository) {
                applyRoutingModePreset(RoutingModePreset.VPN, PrivacyRouteScope.ALL_APPS)
                updatePrivacyRoutePermitted(permitted)
            }
        }
    }

    private fun testIpInfo(ip: String, countryCode: String) =
        IpInfo(
            ip = ip,
            ipv4 = ip,
            countryCode = countryCode,
            countryName = countryCode,
            city = "Instrumentation",
            isp = "FoxHole motion test",
            fetchedAt = System.currentTimeMillis(),
        )

    private fun torIdentityLabel(): String =
        composeRule.activity.getString(R.string.cli_home_status_tor_identity)

    private fun profileSelectorTitle(): String =
        composeRule.activity.getString(R.string.cli_prof_title)

    private fun geometryTolerancePx(): Float =
        composeRule.activity.resources.displayMetrics.density * GEOMETRY_TOLERANCE_DP

    private fun app(): FoxholeApplication =
        composeRule.activity.application as FoxholeApplication

    private companion object {
        const val TEST_PROFILE_ID = 42L
        const val TEST_VPN_IP = "8.8.4.4"
        const val TEST_TOR_IP = "1.1.1.1"
        const val INTERRUPTION_DELAY_MS = 40L
        const val HOME_STABILITY_WINDOW_MS = 300L
        const val INITIAL_NETWORK_SETTLE_MS = 2_000L
        const val GEOMETRY_TOLERANCE_DP = 2f
        const val STATE_TIMEOUT_MS = 10_000L
    }
}
