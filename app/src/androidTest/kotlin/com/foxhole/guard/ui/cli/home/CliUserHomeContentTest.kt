package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.LocalCliColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliUserHomeContentTest {
    @get:Rule
    val compose = createComposeRule()
    private var renderedBackground = Color.Unspecified

    @Test
    fun emptyProfileCardAddsVpnWhileStartStaysDisabled() {
        var profiles = 0
        var starts = 0
        render(onProfiles = { profiles++ }, onPrimary = { starts++ })
        compose.onNodeWithTag(CLI_HOME_TERMINAL_TAG).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.user_home_add_profile)).performScrollTo().performClick()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, profiles)
            assertEquals(0, starts)
        }
    }

    @Test
    fun savedProfilesWithoutSelectionOpenSelectorAndKeepStartDisabled() {
        var additions = 0
        var selections = 0
        render(hasProfiles = true, onProfiles = { additions++ }, onSelector = { selections++ })

        compose.onNodeWithText(text(R.string.user_home_add_profile)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.user_home_choose_profile)).performScrollTo().performClick()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, additions)
            assertEquals(1, selections)
        }
    }

    @Test
    fun selectedProfileOpensInlineSelectorAndStartUsesPrimaryAction() {
        var selections = 0
        var additions = 0
        var starts = 0
        render(
            home = readyHome.copy(activeProfile = profile),
            hasProfiles = true,
            onProfiles = { additions++ },
            onSelector = { selections++ },
            onPrimary = { starts++ },
        )

        compose.onNodeWithText(profile.name).performScrollTo().performClick()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(0, additions)
            assertEquals(1, starts)
        }
    }

    @Test
    fun stopRemainsAvailableWhileConnectingAndRefreshingSubscription() {
        var stops = 0
        render(
            home = readyHome.copy(connection = ConnectionSnapshot(state = ConnectionState.CONNECTING)),
            refreshing = true,
            onPrimary = { stops++ },
        )
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG)
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun connectedSessionCanStopBeforeSettingsAndProfilesFinishLoading() {
        var stops = 0
        render(
            home = HomeRouteUiState(connection = ConnectionSnapshot(state = ConnectionState.CONNECTED)),
            refreshing = true,
            onPrimary = { stops++ },
        )
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun profileStartIsDisabledDuringSubscriptionRefresh() {
        render(home = readyHome.copy(activeProfile = profile), hasProfiles = true, refreshing = true)
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun bothModuleButtonsShareTheRowAboveFullWidthStart() {
        var modes = 0
        var i2p = 0
        render(
            home = withModules(tor = true, i2p = true),
            onMode = { modes++ },
            onI2p = { i2p++ },
        )
        compose.onNodeWithTag(CLI_HOME_MODE_ACTION_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(CLI_HOME_I2P_ACTION_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo()
        val mode = compose.onNodeWithTag(CLI_HOME_MODE_ACTION_TAG).fetchSemanticsNode().boundsInRoot
        val i2pBounds = compose.onNodeWithTag(CLI_HOME_I2P_ACTION_TAG).fetchSemanticsNode().boundsInRoot
        val primary = compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(mode.width, i2pBounds.width, 1f)
        assertEquals(mode.top, i2pBounds.top, 1f)
        assertEquals(mode.left, primary.left, 1f)
        assertEquals(i2pBounds.right, primary.right, 1f)
        assertTrue(primary.top >= maxOf(mode.bottom, i2pBounds.bottom))
        compose.runOnIdle {
            assertEquals(1, modes)
            assertEquals(1, i2p)
        }
    }

    @Test
    fun torOnlyModuleShowsModeAcrossTheUpperRow() {
        render(home = withModules(tor = true, i2p = false))
        assertOnlyModuleFillsRow(CLI_HOME_MODE_ACTION_TAG, CLI_HOME_I2P_ACTION_TAG)
    }

    @Test
    fun i2pOnlyModuleShowsI2pAcrossTheUpperRow() {
        render(home = withModules(tor = false, i2p = true))
        assertOnlyModuleFillsRow(CLI_HOME_I2P_ACTION_TAG, CLI_HOME_MODE_ACTION_TAG)
    }

    @Test
    fun disabledModulesDoNotLeaveExtraButtons() {
        render()
        compose.onNodeWithTag(CLI_HOME_MODE_ACTION_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CLI_HOME_I2P_ACTION_TAG).assertDoesNotExist()
        listOf("Refresh network", "Copy IP address", "Обновить сеть", "Скопировать IP").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun trafficRemainsVisibleDuringIpRefresh() {
        val traffic = TrafficSnapshot(available = true, rxBytesPerSec = 4096, txBytesPerSec = 2048)
        render(home = readyHome.copy(
            connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
            ipInfoLoading = true,
            traffic = traffic,
        ))
        compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).performScrollTo().assertTextEquals(
            "↓ ${CliFormat.rate(traffic.rxBytesPerSec)}  ↑ ${CliFormat.rate(traffic.txBytesPerSec)}",
        )
        compose.onNodeWithTag(CLI_HOME_IP_TAG).assertIsDisplayed()
    }

    @Test
    fun trafficHidesAfterDisconnectEvenWhenTheLastSnapshotRemainsAvailable() {
        val home = mutableStateOf(readyHome.copy(
            connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
            traffic = TrafficSnapshot(available = true, rxBytesPerSec = 4096, txBytesPerSec = 2048),
        ))
        render(homeState = { home.value })
        compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).performScrollTo().assertIsDisplayed()

        compose.runOnIdle { home.value = home.value.copy(connection = ConnectionSnapshot()) }
        compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).assertDoesNotExist()
        compose.runOnIdle {
            home.value = home.value.copy(connection = ConnectionSnapshot(state = ConnectionState.ERROR))
        }
        compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).assertDoesNotExist()
    }

    @Test
    fun connectingWithoutAnActiveConnectionHidesTheTrafficPlaceholder() {
        render(home = readyHome.copy(connection = ConnectionSnapshot(state = ConnectionState.CONNECTING)))
        compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).assertDoesNotExist()
        compose.onNodeWithText("↓ —  ↑ —").assertDoesNotExist()
    }

    @Test
    fun connectedHomeKeepsUnavailableTrafficHonest() {
        render(home = readyHome.copy(connection = ConnectionSnapshot(state = ConnectionState.CONNECTED)))
        compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).performScrollTo().assertTextEquals("↓ —  ↑ —")
    }

    @Test
    fun addressBoundsStayFixedThroughLoadingCityArrivalAndFailure() {
        val info = IpInfo("203.0.113.8", countryCode = null, countryName = null, city = null, isp = null, fetchedAt = 1L)
        val home = mutableStateOf(readyHome.copy(ipInfo = info))
        render(viewportHeight = 720.dp, homeState = { home.value })
        val before = compose.onNodeWithTag(CLI_HOME_IP_TAG).getUnclippedBoundsInRoot()
        compose.onNodeWithText(text(R.string.user_home_city_unavailable)).assertIsDisplayed()
        compose.runOnIdle { home.value = home.value.copy(ipInfoLoading = true) }
        compose.onNodeWithText(info.ip).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.user_home_city_unavailable)).assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag(CLI_HOME_IP_TAG).getUnclippedBoundsInRoot())
        compose.runOnIdle {
            home.value = home.value.copy(
                ipInfoLoading = false,
                ipInfo = info.copy(city = "Amsterdam", countryName = "Netherlands", countryCode = "NL"),
            )
        }
        compose.onNodeWithText("Amsterdam").assertIsDisplayed()
        compose.onNodeWithText("Netherlands").assertIsDisplayed()
        compose.onNodeWithTag(CLI_HOME_IP_TAG).assertTextEquals(info.ip)
        assertEquals(before, compose.onNodeWithTag(CLI_HOME_IP_TAG).getUnclippedBoundsInRoot())
        compose.runOnIdle {
            home.value = home.value.copy(
                ipInfo = null,
                ipInfoLoading = true,
                connection = ConnectionSnapshot(state = ConnectionState.ERROR),
            )
        }
        compose.onNodeWithTag(CLI_HOME_IP_TAG).assertTextEquals("—")
        compose.onNodeWithText(text(R.string.user_home_city_unavailable)).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(CLI_HOME_IP_TAG).getUnclippedBoundsInRoot())
    }

    @Test
    fun networkStaysNearMapWhileProfileAndControlsStayBottomAnchored() {
        render(home = readyHome.copy(activeProfile = profile), hasProfiles = true, viewportHeight = 720.dp)
        val viewport = compose.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
        val map = compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).getUnclippedBoundsInRoot()
        val profileBounds = compose.onNodeWithTag(CLI_HOME_FACTS_TAG).getUnclippedBoundsInRoot()
        val bottom = compose.onNodeWithTag(CLI_USER_HOME_BOTTOM_TAG).getUnclippedBoundsInRoot()
        val ip = compose.onNodeWithTag(CLI_HOME_IP_TAG).getUnclippedBoundsInRoot()
        val primary = compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).getUnclippedBoundsInRoot()

        assertTrue(ip.top >= map.bottom && ip.top - map.bottom <= 16.dp)
        assertTrue(ip.bottom < bottom.top)
        assertEquals(bottom.top.value, profileBounds.top.value, 1f)
        assertEquals(viewport.bottom.value, bottom.bottom.value, 1f)
        assertTrue(primary.top > profileBounds.bottom)
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).assertIsDisplayed()
    }

    @Test
    fun networkIdentityAndTrafficAreCenteredBelowTheOverview() {
        val info = IpInfo(
            ip = "203.0.113.8", countryCode = "NL", countryName = "Netherlands",
            city = "Amsterdam", isp = null, fetchedAt = 1L,
        )
        val traffic = TrafficSnapshot(available = true, rxBytesPerSec = 4096, txBytesPerSec = 2048)
        render(
            home = readyHome.copy(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                ipInfo = info,
                traffic = traffic,
            ),
            viewportHeight = 720.dp,
        )
        val viewport = compose.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
        val center = (viewport.left.value + viewport.right.value) / 2f
        val ip = compose.onNodeWithText(info.ip, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val flag = compose.onNodeWithContentDescription("NL", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val city = compose.onNodeWithText("Amsterdam", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val cityIcon = compose.onNodeWithContentDescription(text(R.string.user_home_city), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val country = compose.onNodeWithText("Netherlands", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val rate = compose.onNodeWithTag(CLI_HOME_TRAFFIC_TAG).getUnclippedBoundsInRoot()

        assertEquals(center, (flag.left.value + ip.right.value) / 2f, 1f)
        assertEquals(center, (cityIcon.left.value + city.right.value) / 2f, 1f)
        assertEquals(center, (country.left.value + country.right.value) / 2f, 1f)
        assertEquals(center, (rate.left.value + rate.right.value) / 2f, 1f)
    }

    @Test
    fun routeSelectionWorksWithoutMapMonitoringAndKeepsProfileAndStartReachable() {
        var profiles = 0
        var starts = 0
        val routeHome = readyHome.copy(
            activeProfile = profile,
            settings = readyHome.settings.copy(
                ui = readyHome.settings.ui.copy(
                    interfaceMode = InterfaceMode.USER,
                    homeAdditionalInfoCategory = HomeAdditionalInfoCategory.ROUTE,
                    showHomeAdditionalInfo = false,
                    trafficMapEnabled = false,
                ),
                expert = readyHome.settings.expert.copy(firewallEnabled = false),
            ),
        )
        render(
            home = routeHome,
            hasProfiles = true,
            viewportHeight = 360.dp,
            fontScale = 1.3f,
            onSelector = { profiles++ },
            onPrimary = { starts++ },
        )

        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.user_home_map_disabled)).assertDoesNotExist()
        compose.onNodeWithTag(CLI_USER_HOME_ROUTE_TAG).performScrollTo().assertIsDisplayed()
            .assert(hasClickAction().not())
        compose.onNodeWithContentDescription(text(R.string.cli_rt_device)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cli_rt_internet)).assertIsDisplayed()
        compose.onNodeWithTag(CLI_HOME_FACTS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, profiles)
            assertEquals(1, starts)
            assertEquals(readyHome.settings.connection.safeModeEnabled, routeHome.settings.connection.safeModeEnabled)
            assertEquals(readyHome.settings.privacyRoute, routeHome.settings.privacyRoute)
        }
    }

    @Test
    fun liveMapRouteSelectionReplacesThePreviewWithoutChangingOtherHomeState() {
        val initial = readyHome.copy(
            activeProfile = profile,
            settings = readyHome.settings.copy(
                ui = readyHome.settings.ui.copy(interfaceMode = InterfaceMode.USER),
            ),
        )
        val home = mutableStateOf(initial)
        var maps = 0
        render(hasProfiles = true, homeState = { home.value }, onMap = { maps++ })

        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            home.value = initial.copy(
                settings = initial.settings.copy(
                    ui = initial.settings.ui.copy(homeAdditionalInfoCategory = HomeAdditionalInfoCategory.ROUTE),
                ),
            )
        }
        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CLI_USER_HOME_ROUTE_TAG).performScrollTo().assertIsDisplayed()
            .assert(hasClickAction().not())
        compose.onNodeWithContentDescription(text(R.string.cli_rt_device)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(initial.activeProfile, home.value.activeProfile)
            assertEquals(initial.connection, home.value.connection)
            assertEquals(initial.settings.expert, home.value.settings.expert)
            assertEquals(1, maps)
            home.value = initial
        }
        compose.onNodeWithTag(CLI_USER_HOME_ROUTE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, maps) }
    }

    @Test
    fun profilePickerLeadsNameWithAnIconWithoutADuplicateTitle() {
        render(home = readyHome.copy(activeProfile = profile), hasProfiles = true)
        val icon = compose.onNodeWithTag(CLI_USER_HOME_PROFILE_ICON_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag(CLI_USER_HOME_PROFILE_TEXT_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(icon.right < name.left)
        compose.onNodeWithText(text(R.string.user_home_choose_profile)).assertDoesNotExist()
    }

    @Test
    fun disconnectedHomeHidesTheLatencyPlaceholder() {
        render(home = readyHome.copy(selectedProtocolLatencyMs = 42L))
        compose.onNodeWithText(text(R.string.user_home_latency, "—")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.user_home_latency, CliFormat.latency(42L))).assertDoesNotExist()
    }

    @Test
    fun connectedHomeRetainsTheMeasuredLatency() {
        render(home = readyHome.copy(
            connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
            selectedProtocolLatencyMs = 42L,
        ))
        compose.onNodeWithText(text(R.string.user_home_latency, CliFormat.latency(42L)))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun countryFlagSharesTheIpRowAndCityHasALeadingIcon() {
        render(home = readyHome.copy(ipInfo = IpInfo(
            ip = "203.0.113.8", countryCode = "NL", countryName = "Netherlands",
            city = "Amsterdam", isp = null, fetchedAt = 1L,
        )))
        compose.onNodeWithText("203.0.113.8", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val ip = compose.onNodeWithText("203.0.113.8", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val flag = compose.onNodeWithContentDescription("NL", useUnmergedTree = true).assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(flag.right < ip.left)
        assertTrue(flag.top >= ip.top - 4.dp && flag.bottom <= ip.bottom + 4.dp)
        compose.onNodeWithText("Amsterdam", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val city = compose.onNodeWithText("Amsterdam", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val icon = compose.onNodeWithContentDescription(text(R.string.user_home_city), useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(icon.right < city.left)
    }

    @Test
    fun selectedProfilePickerHasCompactHeightAndAnUnfilledInterior() {
        render(home = readyHome.copy(activeProfile = profile), hasProfiles = true)
        val picker = compose.onNodeWithTag(CLI_HOME_FACTS_TAG).performScrollTo()
        val bounds = picker.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 64.dp)
        val image = picker.captureToImage().toPixelMap()
        assertEquals(renderedBackground, image[image.width * 3 / 4, image.height / 2])
    }

    @Test
    fun selectedProtocolBadgeUsesTheOptionAndSitsRaisedBesideTheName() {
        val selected = profile.copy(
            name = "Provider named Trojan",
            protocolOptions = listOf(
                ProfileProtocolOption("vless", "Named route", ProtocolHint.VLESS, isSelected = true),
                ProfileProtocolOption("hy2", "Other named route", ProtocolHint.HYSTERIA2),
            ),
            selectedProtocolOptionId = "hy2",
        )
        val home = mutableStateOf(readyHome.copy(activeProfile = selected))
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliUserProfileCard(home.value, hasProfiles = true, onClick = {})
            }
        }
        val name = compose.onNodeWithText(selected.name, useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithText("Hysteria2", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(badge.left > name.right)
        assertTrue(badge.top < name.top)
        compose.onNodeWithText("VLESS", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            home.value = home.value.copy(activeProfile = selected.copy(selectedProtocolOptionId = "vless"))
        }
        compose.onNodeWithText("VLESS", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Hysteria2", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            home.value = home.value.copy(
                settings = home.value.settings.copy(
                    privacyRoute = home.value.settings.privacyRoute.copy(
                        permitted = true,
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        bypassVpnTunnel = true,
                    ),
                ),
            )
        }
        compose.onNodeWithText(selected.name, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("VLESS", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun customRoutingKeepsItsSettingsAndSecurityConfirmationWithoutTheCaption() {
        var starts = 0
        val settings = readyHome.settings.copy(
            expert = readyHome.settings.expert.copy(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = mapOf("owned.app" to AppTunnelLane.VPN),
            ),
            networkRules = readyHome.settings.networkRules.copy(wifiRulesEnabled = true),
        )
        val home = readyHome.copy(settings = settings, activeProfile = profile)
        render(
            home = home,
            hasProfiles = true,
            onPrimary = { starts++ },
            confirmationContent = { Text("Owned required security confirmation") },
        )
        compose.onNodeWithText("Custom app or network rules are configured.").assertDoesNotExist()
        compose.onNodeWithText("Owned required security confirmation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, home.settings.expert.perAppRoutingMode)
            assertEquals(mapOf("owned.app" to AppTunnelLane.VPN), home.settings.expert.appAssignments)
            assertTrue(home.settings.networkRules.wifiRulesEnabled)
        }
    }

    @Test
    fun shortScreenAndLargeFontKeepMapProfileAndActionsReachable() {
        var starts = 0
        render(
            home = withModules(tor = true, i2p = true).copy(activeProfile = profile.copy(name = "A longer saved VPN profile name")),
            hasProfiles = true,
            viewportHeight = 360.dp,
            fontScale = 1.3f,
            onPrimary = { starts++ },
        )
        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CLI_HOME_FACTS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CLI_HOME_IP_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo().assertIsDisplayed().performClick()
        val mode = compose.onNodeWithTag(CLI_HOME_MODE_ACTION_TAG).getUnclippedBoundsInRoot()
        val i2p = compose.onNodeWithTag(CLI_HOME_I2P_ACTION_TAG).getUnclippedBoundsInRoot()
        val primary = compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).getUnclippedBoundsInRoot()
        assertEquals(mode.top.value, i2p.top.value, 1f)
        assertTrue(primary.top >= maxOf(mode.bottom, i2p.bottom))
        compose.runOnIdle { assertEquals(1, starts) }
    }

    private fun render(
        home: HomeRouteUiState = readyHome,
        hasProfiles: Boolean = false,
        refreshing: Boolean = false,
        viewportHeight: Dp? = null,
        fontScale: Float = 1f,
        onProfiles: () -> Unit = {},
        onSelector: () -> Unit = {},
        onPrimary: () -> Unit = {},
        onMode: () -> Unit = {},
        onI2p: () -> Unit = {},
        onMap: () -> Unit = {},
        confirmationContent: @Composable () -> Unit = {},
        homeState: () -> HomeRouteUiState = { home },
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CliTheme(pixelArtEnabled = false) {
                    val currentHome = homeState()
                    val background = LocalCliColors.current.bg
                    SideEffect { renderedBackground = background }
                    CliUserHomeContent(
                        home = currentHome,
                        map = TrafficMapUiState(),
                        modifier = (if (viewportHeight != null) Modifier.fillMaxWidth().height(viewportHeight) else Modifier.fillMaxSize())
                            .background(background)
                            .testTag(VIEWPORT_TAG),
                        subscriptionRefreshInProgress = refreshing,
                        onOpenMap = onMap,
                        onPrimaryAction = onPrimary,
                        onCycleMode = onMode,
                        onToggleI2p = onI2p,
                        onStopProxy = {},
                        header = {},
                        confirmation = confirmationContent,
                        profileContent = {
                            CliUserProfileCard(
                                home = currentHome,
                                hasProfiles = hasProfiles,
                                onClick = if (hasProfiles) onSelector else onProfiles,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun assertOnlyModuleFillsRow(visibleTag: String, hiddenTag: String) {
        compose.onNodeWithTag(hiddenTag).assertDoesNotExist()
        compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).performScrollTo()
        val module = compose.onNodeWithTag(visibleTag).fetchSemanticsNode().boundsInRoot
        val primary = compose.onNodeWithTag(CLI_HOME_PRIMARY_ACTION_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(primary.width, module.width, 1f)
        assertTrue(primary.top >= module.bottom)
    }

    private fun withModules(tor: Boolean, i2p: Boolean): HomeRouteUiState = readyHome.copy(
        settings = readyHome.settings.copy(
            privacyRoute = readyHome.settings.privacyRoute.copy(permitted = tor),
            i2p = readyHome.settings.i2p.copy(enabled = i2p),
        ),
    )

    private fun text(id: Int, vararg arguments: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *arguments)

    private companion object {
        const val VIEWPORT_TAG = "user_home_test_viewport"
        val readyHome = HomeRouteUiState(profilesLoaded = true, settingsHydrated = true)
        val profile = Profile(
            id = 42L,
            name = "Saved VPN",
            sourceType = ProfileSourceType.SHARE_URI,
            secretRef = "user-home-test",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            isActive = true,
        )
    }
}
