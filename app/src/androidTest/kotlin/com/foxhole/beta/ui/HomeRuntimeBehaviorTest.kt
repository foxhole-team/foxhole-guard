package com.foxhole.beta.ui

import android.text.format.Formatter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.MainActivity
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.vpn.FoxholeConnectionServiceContract
import com.foxhole.beta.vpn.FoxholeVpnRuntimeBridge
import java.io.FileInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class HomeRuntimeBehaviorTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val stateResetRule =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val app = app()
                    grantNotificationsPermission(app.packageName)
                    runBlocking {
                        app.container.settingsRepository.updateKillSwitchEnabled(false)
                        app.container.settingsRepository.updateFirewallEnabled(false)
                        app.container.settingsRepository.updateNetworkActivityLogging(false)
                        app.container.settingsRepository.updateNetworkActivityPersistentLogging(false)
                        if (hasActiveFoxholeVpnNetwork(app.packageName)) {
                            FoxholeVpnRuntimeBridge.update(
                                ConnectionSnapshot(
                                    state = ConnectionState.CONNECTED,
                                    trafficMode = TrafficMode.TUNNEL,
                                ),
                            )
                        }
                        app.container.connectionController.disconnect()
                        waitForRuntimeShutdown(app.packageName)
                    }
                    FoxholeConnectionServiceContract.stopAllServices(app)
                    FoxholeVpnRuntimeBridge.clearTransientState()
                    FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
                    app.container.diagnosticsLogger.clear()
                    base.evaluate()
                }
            }
        }

    private val notificationsPermissionRule =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    grantNotificationsPermission(InstrumentationRegistry.getInstrumentation().targetContext.packageName)
                    base.evaluate()
                }
            }
        }

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain
            .outerRule(stateResetRule)
            .around(notificationsPermissionRule)
            .around(composeRule)

    @Test
    fun coldStartWhileDisconnectedRefreshesIpSilentlyWithoutLoading() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("home_network_loading").fetchSemanticsNodes().isEmpty()
        }

        assertTrue(
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "ip" &&
                    it.message.contains("mode=entry_quick") &&
                    it.message.contains("showLoading=false") &&
                    it.message.contains("clearExistingIp=false")
            },
        )
        composeRule.onNodeWithTag("home_network_primary_ip", useUnmergedTree = true).assertTextEquals("-")
        composeRule
            .onAllNodesWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home_network_unavailable),
            ).assertCountEquals(0)
    }

    @Test
    fun foregroundResumeWhileConnectedTriggersSilentIpRefreshWithoutClearingCurrentIp() {
        waitUntilNetworkBlockSettles()

        val expectedIp = "198.51.100.42"
        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.PROXY,
                    profileId = 7L,
                    profileName = "Instrumentation",
                ),
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(
                IpInfo(
                    ip = expectedIp,
                    ipv4 = expectedIp,
                    countryCode = "US",
                    countryName = "United States",
                    city = "New York",
                    isp = "Instrumentation ISP",
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            app().container.diagnosticsLogger.clear()
        }

        scrollToNetworkBlock()
        composeRule.waitUntil(timeoutMillis = 10_000) { textOfOrNull("home_network_primary_ip") == expectedIp }
        Thread.sleep(HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS + 400L)
        composeRule.waitForIdle()
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]
        composeRule.runOnUiThread {
            viewModel.invalidateIpInfoRefreshes()
            FoxholeVpnRuntimeBridge.updateIpInfo(
                IpInfo(
                    ip = expectedIp,
                    ipv4 = expectedIp,
                    countryCode = "US",
                    countryName = "United States",
                    city = "New York",
                    isp = "Instrumentation ISP",
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            app().container.diagnosticsLogger.clear()
        }
        composeRule.runOnUiThread {
            viewModel.onAppForegrounded()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "ip" &&
                    it.message.contains("mode=full") &&
                    it.message.contains("showLoading=false") &&
                    it.message.contains("clearExistingIp=false")
            }
        }
        composeRule.runOnUiThread {
            viewModel.invalidateIpInfoRefreshes()
        }

        composeRule.onAllNodesWithTag("home_network_loading").assertCountEquals(0)
        assertTrue(
            app().container.diagnosticsLogger.entries.value.none {
                it.tag == "ip" &&
                    it.message.contains("mode=entry_quick") &&
                    it.message.contains("clearExistingIp=true")
            },
        )
    }

    @Test
    fun connectedWithoutResolvedIpKeepsNetworkLoadingUntilVpnIpResolves() {
        waitUntilNetworkBlockSettles()

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    profileName = "Instrumentation",
                ),
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(null)
        }

        scrollToNetworkBlock()
        composeRule.onNodeWithTag("home_network_loading").assertIsDisplayed()
        composeRule
            .onAllNodesWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home_network_unavailable),
            ).assertCountEquals(0)
    }

    @Test
    fun connectingShowsNetworkLoadingInsteadOfSwitchingToUnavailableState() {
        waitUntilNetworkBlockSettles()

        val previousIp = "198.51.100.42"
        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                ),
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(
                IpInfo(
                    ip = previousIp,
                    ipv4 = previousIp,
                    countryCode = "US",
                    countryName = "United States",
                    city = "New York",
                    isp = "Instrumentation ISP",
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = 3_000) { textOfOrNull("home_network_primary_ip") == previousIp }

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    profileName = "Instrumentation",
                ),
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(null)
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_network_loading").assertIsDisplayed()
        composeRule
            .onAllNodesWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home_network_unavailable),
            ).assertCountEquals(0)
    }

    @Test
    fun returningHomeTriggersImmediateTrafficSampleAndUpdatesTrafficCard() {
        waitUntilNetworkBlockSettles()

        val updatedTraffic =
            TrafficSnapshot(
                available = true,
                rxBytesPerSec = 2_048,
                txBytesPerSec = 1_024,
                rxTotalBytes = 8_192,
                txTotalBytes = 4_096,
                sampledAt = System.currentTimeMillis(),
            )

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.updateTraffic(TrafficSnapshot(available = true))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        val sampleObserved = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectorJob =
            scope.launch {
                FoxholeVpnRuntimeBridge.immediateTrafficSampleRequests.first()
                FoxholeVpnRuntimeBridge.updateTraffic(updatedTraffic)
                sampleObserved.complete(Unit)
            }

        try {
            composeRule.onNodeWithTag("bottom_nav_dashboard").performClick()
            composeRule.waitUntil(timeoutMillis = 3_000) { sampleObserved.isCompleted }
            composeRule.waitUntil(timeoutMillis = 3_000) {
                textOfOrNull("home_traffic_rx_rate") == rateString(updatedTraffic.rxBytesPerSec)
            }

            composeRule.onNodeWithTag("home_traffic_rx_value").assertTextEquals(bytesString(updatedTraffic.rxTotalBytes))
            composeRule.onNodeWithTag("home_traffic_rx_rate").assertTextEquals(rateString(updatedTraffic.rxBytesPerSec))
            composeRule.onNodeWithTag("home_traffic_tx_value").assertTextEquals(bytesString(updatedTraffic.txTotalBytes))
            composeRule.onNodeWithTag("home_traffic_tx_rate").assertTextEquals(rateString(updatedTraffic.txBytesPerSec))
            composeRule.onNodeWithTag("home_traffic_total_value").assertTextEquals(
                bytesString(updatedTraffic.rxTotalBytes + updatedTraffic.txTotalBytes),
            )
            composeRule.onNodeWithTag("home_traffic_total_rate").assertTextEquals(
                rateString(updatedTraffic.rxBytesPerSec + updatedTraffic.txBytesPerSec),
            )
        } finally {
            collectorJob.cancel()
            scope.cancel()
        }
    }

    private fun waitUntilNetworkBlockSettles() {
        scrollToNetworkBlock()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("home_network_loading").fetchSemanticsNodes().isEmpty()
        }
        scrollToNetworkBlock()
    }

    private fun scrollToNetworkBlock() {
        runCatching {
            composeRule
                .onNodeWithTag("home_dashboard_list")
                .performScrollToNode(hasTestTag("home_network_card"))
        }
        composeRule.waitForIdle()
    }

    private fun bytesString(value: Long): String =
        Formatter.formatShortFileSize(InstrumentationRegistry.getInstrumentation().targetContext, value)

    private fun rateString(bytesPerSecond: Long): String = "${bytesString(bytesPerSecond)}/s"

    private fun textOf(tag: String): String =
        textOfOrNull(tag) ?: error("no node text for tag=$tag")

    private fun textOfOrNull(tag: String): String? =
        textValuesOf(tag).firstOrNull()

    private fun textValuesOf(tag: String): List<String> =
        runCatching {
            composeRule
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { node ->
                    if (node.config.contains(SemanticsProperties.Text)) {
                        node.config[SemanticsProperties.Text].joinToString(separator = "") { it.text }
                    } else {
                        null
                    }
                }
        }.getOrDefault(emptyList())

    private fun grantNotificationsPermission(packageName: String) {
        shell("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    }

    private fun hasActiveFoxholeVpnNetwork(packageName: String): Boolean =
        shell("dumpsys connectivity").contains("VPN CONNECTED extra: VPN:$packageName")

    private suspend fun waitForRuntimeShutdown(packageName: String) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val connectivity = shell("dumpsys connectivity")
            val services = shell("dumpsys activity services $packageName")
            if (
                !connectivity.contains("VPN CONNECTED extra: VPN:$packageName") &&
                !services.contains("FoxholeVpnService") &&
                !services.contains("FoxholeProxyService")
            ) {
                return
            }
            delay(250)
        }
    }

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }

    private fun app(): FoxholeApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
}
