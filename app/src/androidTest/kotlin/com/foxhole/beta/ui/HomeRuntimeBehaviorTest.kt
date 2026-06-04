package com.foxhole.beta.ui

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                        app.container.settingsRepository.updateTrafficMapEnabled(true)
                        prewarmTrafficMapCountryShapes(app)
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
    fun coldStartWhileDisconnectedRefreshesIpInBackgroundWithoutUnavailableState() {
        scrollToNetworkBlock()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "ip" &&
                    it.message.contains("reason=foreground") &&
                    it.message.contains("mode=entry_quick") &&
                    it.message.contains("showLoading=false") &&
                    it.message.contains("clearExistingIp=false")
            }
        }
        composeRule.onAllNodesWithTag("home_network_loading").assertCountEquals(0)
        composeRule
            .onAllNodesWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home_network_unavailable),
            ).assertCountEquals(0)
    }

    @Test
    fun foregroundResumeWhileConnectedTriggersSilentIpRefreshWithoutClearingCurrentIp() {
        waitUntilNetworkBlockSettles()

        val expectedIp = "198.51.100.42"
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]
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
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "ip" &&
                    it.message.contains("dashboard refresh started") &&
                    it.message.contains("reason=post_connect")
            }
        }
        composeRule.runOnUiThread {
            viewModel.invalidateIpInfoRefreshes()
            app().container.diagnosticsLogger.clear()
        }

        scrollToNetworkBlock()
        composeRule.waitForIdle()
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
                    it.message.contains("reason=foreground") &&
                    it.message.contains("mode=entry_quick") &&
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
                    it.message.contains("reason=foreground") &&
                    it.message.contains("mode=entry_quick") &&
                    it.message.contains("clearExistingIp=true")
            },
        )
    }

    @Test
    fun connectedWithoutResolvedIpKeepsNetworkLoadingUntilValidIpArrives() {
        waitUntilNetworkBlockSettles()

        composeRule.runOnUiThread {
            FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = true)
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    profileName = "Instrumentation",
                ),
            )
            FoxholeVpnRuntimeBridge.markIpInfoRefreshPending()
        }

        scrollToNetworkBlock()
        composeRule.waitUntil(timeoutMillis = 5_000) { networkLoadingVisible() }
        composeRule.onNodeWithTag("home_network_loading").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_network_primary_ip", useUnmergedTree = true).assertCountEquals(0)
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
            FoxholeVpnRuntimeBridge.markIpInfoRefreshPending()
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("home_network_loading", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithTag("home_network_primary_ip", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag("home_network_loading", useUnmergedTree = true).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("home_connection_status_loading").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home_connection_status_loading").assertIsDisplayed()
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

    @Test
    fun dashboardSettingsRoundTripKeepsWarmMapAndNetworkResponsive() {
        val expectedIp = "198.51.100.88"
        seedNetworkBlock(expectedIp)
        scrollToTrafficMapCard()
        waitUntilTagExists("home_traffic_world_map", timeoutMs = INITIAL_TRAFFIC_MAP_READY_TIMEOUT_MS)

        val settingsOpenMs =
            measureUntil("settings bottom nav opens settings") {
                composeRule.onNodeWithTag("bottom_nav_settings").performClick()
                waitUntilTagExists("settings_screen", timeoutMs = NAVIGATION_RESPONSIVENESS_TIMEOUT_MS)
            }
        assertUnderBudget("settings bottom nav", settingsOpenMs, NAVIGATION_RESPONSIVENESS_TIMEOUT_MS)

        val dashboardMapReturnMs =
            measureUntil("dashboard bottom nav restores warm traffic map") {
                composeRule.onNodeWithTag("bottom_nav_dashboard").performClick()
                waitUntilTagExists("home_traffic_world_map", timeoutMs = WARM_DASHBOARD_RETURN_TIMEOUT_MS)
            }
        assertUnderBudget("dashboard warm map return", dashboardMapReturnMs, WARM_DASHBOARD_RETURN_TIMEOUT_MS)

        val networkRevealMs =
            measureUntil("dashboard network card reveal") {
                scrollToNetworkBlock()
                composeRule.waitUntil(timeoutMillis = NETWORK_WIDGET_RESPONSIVENESS_TIMEOUT_MS) {
                    composeRule.onAllNodesWithTag("home_network_card").fetchSemanticsNodes().isNotEmpty() &&
                        composeRule.onAllNodesWithTag("home_network_loading").fetchSemanticsNodes().isEmpty() &&
                        !networkPrimaryIpText().isNullOrBlank()
                }
            }
        assertUnderBudget("dashboard network card reveal", networkRevealMs, NETWORK_WIDGET_RESPONSIVENESS_TIMEOUT_MS)

        val roundTripMs =
            measureUntil("dashboard settings repeated round-trip") {
                repeat(DASHBOARD_SETTINGS_ROUND_TRIP_COUNT) {
                    composeRule.onNodeWithTag("bottom_nav_settings").performClick()
                    waitUntilTagExists("settings_screen", timeoutMs = NAVIGATION_RESPONSIVENESS_TIMEOUT_MS)
                    composeRule.onNodeWithTag("bottom_nav_dashboard").performClick()
                    waitUntilTagExists("home_dashboard_list", timeoutMs = NAVIGATION_RESPONSIVENESS_TIMEOUT_MS)
                }
            }
        assertUnderBudget("dashboard/settings repeated round-trip", roundTripMs, ROUND_TRIP_RESPONSIVENESS_TIMEOUT_MS)
    }

    @Test
    fun rootNavigationComposesOnlyActiveSection() {
        waitUntilOnlyRootSectionVisible(activeTag = "home_dashboard_list", inactiveTag = "settings_screen")
        composeRule.onAllNodesWithTag("settings_screen", useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        waitUntilOnlyRootSectionVisible(activeTag = "settings_screen", inactiveTag = "home_dashboard_list")
        composeRule.onAllNodesWithTag("home_dashboard_list", useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag("bottom_nav_dashboard").performClick()
        waitUntilOnlyRootSectionVisible(activeTag = "home_dashboard_list", inactiveTag = "settings_screen")
        composeRule.onAllNodesWithTag("settings_screen", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun manualNetworkRefreshButtonStartsFullDashboardRefresh() {
        waitUntilNetworkBlockSettles()
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]
        app().container.diagnosticsLogger.clear()

        try {
            composeRule.onNodeWithTag("home_refresh_ip_icon").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                app().container.diagnosticsLogger.entries.value.any {
                    it.tag == "ip" &&
                        it.message.contains("dashboard refresh started") &&
                        it.message.contains("reason=manual") &&
                        it.message.contains("mode=full") &&
                        it.message.contains("showLoading=true") &&
                        it.message.contains("clearExistingIp=false")
                }
            }
        } finally {
            composeRule.runOnUiThread {
                viewModel.invalidateIpInfoRefreshes()
            }
        }
    }

    @Test
    fun staleVpnPermissionResultWithoutPendingRequestOnlyRecordsDiagnostic() {
        val deniedMessage =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(R.string.vpn_permission_denied)
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]

        composeRule.runOnUiThread {
            app().container.diagnosticsLogger.clear()
            viewModel.onVpnPermissionResult(granted = false)
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "permissions" &&
                    it.message == "ignored vpn permission result without active request granted=false"
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(deniedMessage).assertCountEquals(0)
    }

    @Test
    fun vpnPermissionRequestWhilePendingDoesNotSupersedeActiveRequest() {
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]

        composeRule.runOnUiThread {
            app().container.diagnosticsLogger.clear()
            viewModel.pendingConnectRequest =
                PendingConnectRequest(
                    profileId = 10L,
                    action = PendingConnectAction.MANUAL,
                )

            val accepted =
                viewModel.enqueueVpnPermissionRequest(
                    PendingConnectRequest(
                        profileId = 20L,
                        action = PendingConnectAction.RECONNECT,
                    ),
                )

            assertFalse(accepted)
            assertEquals(10L, viewModel.pendingConnectRequest?.profileId)
            assertEquals(PendingConnectAction.MANUAL, viewModel.pendingConnectRequest?.action)
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "permissions" &&
                    it.message.contains("ignored vpn permission request while active request is pending") &&
                    it.message.contains("active=manual") &&
                    it.message.contains("requested=reconnect")
            }
        }
    }

    @Test
    fun deferredLocalGuardPermissionSyncRetriesAfterActivePermissionResult() {
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]

        composeRule.runOnUiThread {
            app().container.diagnosticsLogger.clear()
            viewModel.pendingConnectRequest =
                PendingConnectRequest(
                    profileId = 10L,
                    action = PendingConnectAction.MANUAL,
                )
            viewModel.pendingLocalGuardPermissionSync = true

            viewModel.onVpnPermissionResult(granted = false)

            assertFalse(viewModel.pendingLocalGuardPermissionSync)
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            app().container.diagnosticsLogger.entries.value.any {
                it.tag == "connection" &&
                    it.message == "local guard permission sync retrying after vpn permission result"
            }
        }
    }

    private fun waitUntilNetworkBlockSettles() {
        val expectedIp = "198.51.100.11"
        seedNetworkBlock(expectedIp)
        scrollToNetworkBlock()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("home_network_loading").fetchSemanticsNodes().isEmpty() &&
                textOfOrNull("home_network_primary_ip") == expectedIp
        }
        scrollToNetworkBlock()
    }

    private fun seedNetworkBlock(expectedIp: String) {
        val viewModel =
            ViewModelProvider(
                composeRule.activity,
                HomeViewModel.factory(app()),
            )[HomeViewModel::class.java]
        composeRule.runOnUiThread {
            viewModel.invalidateIpInfoRefreshes()
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                ),
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(testIpInfo(expectedIp))
        }
        composeRule.waitForIdle()
    }

    private fun testIpInfo(ip: String): IpInfo =
        IpInfo(
            ip = ip,
            ipv4 = ip,
            countryCode = "US",
            countryName = "United States",
            city = "New York",
            isp = "Instrumentation ISP",
            fetchedAt = System.currentTimeMillis(),
        )

    private fun scrollToNetworkBlock() {
        runCatching {
            composeRule
                .onNodeWithTag("home_dashboard_list")
                .performScrollToNode(hasTestTag("home_network_card"))
        }
        composeRule.waitForIdle()
    }

    private fun scrollToTrafficMapCard() {
        val deadline = SystemClock.elapsedRealtime() + TRAFFIC_MAP_CARD_SCROLL_TIMEOUT_MS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val scrolled =
                runCatching {
                    composeRule
                        .onNodeWithTag("home_dashboard_list")
                        .performScrollToNode(hasTestTag("home_traffic_map_card"))
                }.onFailure { failure ->
                    lastFailure = failure
                }.isSuccess
            composeRule.waitForIdle()
            if (
                scrolled &&
                composeRule
                    .onAllNodesWithTag("home_traffic_map_card", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Traffic map card did not become visible", lastFailure)
    }

    private fun bytesString(value: Long): String =
        Formatter.formatShortFileSize(InstrumentationRegistry.getInstrumentation().targetContext, value)

    private fun rateString(bytesPerSecond: Long): String = "${bytesString(bytesPerSecond)}/s"

    private fun networkLoadingVisible(): Boolean =
        composeRule.onAllNodesWithTag("home_network_loading").fetchSemanticsNodes().isNotEmpty()

    private fun networkPrimaryIpText(): String? = textOfOrNull("home_network_primary_ip")

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

    private fun waitUntilTagExists(
        tag: String,
        timeoutMs: Long,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitUntilOnlyRootSectionVisible(
        activeTag: String,
        inactiveTag: String,
    ) {
        composeRule.waitUntil(timeoutMillis = NAVIGATION_RESPONSIVENESS_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(activeTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(inactiveTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun measureUntil(
        label: String,
        block: () -> Unit,
    ): Long {
        val startedAtMs = SystemClock.elapsedRealtime()
        block()
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        app().container.diagnosticsLogger.record("ui-perf", "$label elapsedMs=$elapsedMs")
        return elapsedMs
    }

    private fun assertUnderBudget(
        label: String,
        elapsedMs: Long,
        budgetMs: Long,
    ) {
        assertTrue(
            "$label took ${elapsedMs}ms, budget=${budgetMs}ms",
            elapsedMs <= budgetMs,
        )
    }

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

    private companion object {
        private const val INITIAL_TRAFFIC_MAP_READY_TIMEOUT_MS = 10_000L
        private const val TRAFFIC_MAP_CARD_SCROLL_TIMEOUT_MS = 5_000L
        private const val NAVIGATION_RESPONSIVENESS_TIMEOUT_MS = 1_500L
        private const val WARM_DASHBOARD_RETURN_TIMEOUT_MS = 1_200L
        private const val NETWORK_WIDGET_RESPONSIVENESS_TIMEOUT_MS = 1_500L
        private const val DASHBOARD_SETTINGS_ROUND_TRIP_COUNT = 3
        private const val ROUND_TRIP_RESPONSIVENESS_TIMEOUT_MS = 4_500L
    }
}
