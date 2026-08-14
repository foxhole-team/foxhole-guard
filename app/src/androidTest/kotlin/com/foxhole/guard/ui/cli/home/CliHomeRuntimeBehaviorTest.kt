package com.foxhole.guard.ui.cli.home

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.onboardingCompletedRule
import com.foxhole.guard.core.settings.applyRoutingModePreset
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.invalidateIpInfoRefreshes
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.ui.cli.CliScreen
import com.foxhole.guard.ui.cli.cliScreenTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test

class CliHomeRuntimeBehaviorTest {
    private val composeRule = createAndroidComposeRule<CliMainActivity>()

    // Onboarding first, and outside the compose rule: it launches the activity as it evaluates, so
    // a @Before would run after the wizard was already on screen. See onboardingCompletedRule().
    @get:Rule
    val ruleChain: RuleChain =
        RuleChain.outerRule(onboardingCompletedRule()).around(composeRule)

    @Before
    fun resetRuntimeBridge() {
        resetModeSettings()
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(), refreshLastChangeAt = false)
        waitForHome()
        // resetModeSettings switches the TOR module off, and without it the mode cycler is absent
        // from the row rather than dimmed — see cliHomeButtonLayout.
        waitForModeAbsent()
    }

    @After
    fun clearRuntimeBridge() {
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(), refreshLastChangeAt = false)
        resetModeSettings()
    }

    @Test
    fun homeShowsCurrentIpAndProtectedPrimaryAction() {
        seedIp(TEST_IP)

        composeRule.onNodeWithTag(CLI_HOME_FACTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CLI_HOME_IP_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).assertHasClickAction()
        composeRule.onNodeWithTag(CLI_HOME_TERMINAL_TAG).assertIsDisplayed()
    }

    @Test
    fun connectingKeepsThePreviouslyValidatedIpVisible() {
        seedIp(TEST_IP)

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(
                    TEST_IP,
                    substring = true,
                    useUnmergedTree = true,
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun liveTrafficUpdatesTheCurrentCliFactsPanel() {
        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.updateTraffic(
                TrafficSnapshot(
                    available = true,
                    rxBytesPerSec = 2_048L,
                    txBytesPerSec = 4_096L,
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("↓2KB/s ↑4KB/s", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(CLI_HOME_TRAFFIC_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theModeCyclerFollowsTheTorModuleAndGrantingItRoutesNothing() {
        // Module off: nothing to cycle to, so the slot is gone and START holds the row alone.
        waitForModeAbsent()

        runBlocking {
            app().container.settingsRepository.updatePrivacyRoutePermitted(true)
        }
        composeRule.waitForIdle()

        // Granting the module hands the cycler back on VPN — turning TOR on is not routing through
        // it, so the privacy route stays disabled until the user cycles the mode.
        waitForMode(CliConnectMode.VPN)
        assertFalse(app().container.settingsRepository.settings.value.privacyRoute.enabled)
    }

    private fun seedIp(ip: String) {
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]
        val info =
            IpInfo(
                ip = ip,
                ipv4 = ip,
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Instrumentation ISP",
                fetchedAt = System.currentTimeMillis(),
            )
        composeRule.runOnUiThread {
            viewModel.invalidateIpInfoRefreshes()
            // This is a test fixture reset, not a real route transition. Advancing lastChangeAt
            // here would correctly make the just-created identity stale and hide it.
            FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(), refreshLastChangeAt = false)
            FoxholeVpnRuntimeBridge.updateIpInfo(info)
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(info)
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            viewModel.invalidateIpInfoRefreshes()
            FoxholeVpnRuntimeBridge.updateIpInfo(info)
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(info)
        }
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(
                    ip,
                    substring = true,
                    useUnmergedTree = true,
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForHome() {
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            runCatching {
                composeRule
                    .onNodeWithTag(cliScreenTag(CliScreen.HOME), useUnmergedTree = true)
                    .assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForMode(mode: CliConnectMode) {
        val matcher =
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                mode.label,
            )
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            runCatching {
                composeRule.onNodeWithTag(CLI_HOME_MODE_ACTION_TAG).assert(matcher)
            }.isSuccess
        }
    }

    private fun waitForModeAbsent() {
        composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithTag(CLI_HOME_MODE_ACTION_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun resetModeSettings() {
        runBlocking {
            with(app().container.settingsRepository) {
                applyRoutingModePreset(RoutingModePreset.VPN, PrivacyRouteScope.ALL_APPS)
                updatePrivacyRoutePermitted(false)
            }
        }
    }

    private fun app(): FoxholeApplication =
        composeRule.activity.application as FoxholeApplication

    private companion object {
        // The dashboard intentionally rejects documentation/private ranges as public identity.
        const val TEST_IP = "8.8.4.4"
        const val STATE_TIMEOUT_MS = 10_000L
    }
}
