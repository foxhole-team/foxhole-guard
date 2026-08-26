package com.foxhole.guard.ui.cli.profiles

import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.ui.HomeProtocolMetricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliSmartProfileTestActionTest {
    @Test
    fun `idle test starts and running test stops`() {
        assertEquals(SmartProfileTestAction.START, smartProfileTestAction(testing = false))
        assertEquals(SmartProfileTestAction.STOP, smartProfileTestAction(testing = true))
    }

    @Test
    fun `vpn plus tor asks before entering fail closed vpn protocol test`() {
        assertTrue(
            protocolTestRequiresVpnOnlyConfirmation(
                privacyRouteEnabled = true,
                trafficMode = TrafficMode.TUNNEL,
                perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
            ),
        )
        assertTrue(
            protocolTestRequiresVpnOnlyConfirmation(
                privacyRouteEnabled = false,
                trafficMode = TrafficMode.PROXY,
                perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
            ),
        )
        assertTrue(
            protocolTestRequiresVpnOnlyConfirmation(
                privacyRouteEnabled = false,
                trafficMode = TrafficMode.TUNNEL,
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            ),
        )
        assertFalse(
            protocolTestRequiresVpnOnlyConfirmation(
                privacyRouteEnabled = false,
                trafficMode = TrafficMode.TUNNEL,
                perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
            ),
        )
    }

    @Test
    fun `cancel presentation clears immediately without waiting for probe job`() {
        val state = HomeProtocolMetricsState()
        state.markRefreshStarted(profileId = 42L)
        state.protocolMetricsRefreshingOptionIdByProfileIdMutable.value = mapOf(42L to "trojan")
        state.dashboardConnectionMetricsLoadingMutable.value = true
        state.dashboardConnectionMetricsLoadingStartedAtMs = 123L

        state.clearRefreshPresentation()

        assertTrue(state.protocolMetricsRefreshingProfileIdsMutable.value.isEmpty())
        assertTrue(state.protocolMetricsRefreshingOptionIdByProfileIdMutable.value.isEmpty())
        assertFalse(state.dashboardConnectionMetricsLoadingMutable.value)
        assertEquals(0L, state.dashboardConnectionMetricsLoadingStartedAtMs)
    }

    @Test
    fun `test labels are canonical caps without idle dots`() {
        val english = findFile("src/main/res/values/strings.xml").readText()
        val russian = findFile("src/main/res/values-ru/strings.xml").readText()
        val source =
            findFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliSmartProfileSheet.kt",
            ).readText()

        assertTrue(english.contains("<string name=\"cli_prof_test_button\">TEST</string>"))
        assertTrue(english.contains("<string name=\"cli_prof_stop_test_button\">STOP TEST</string>"))
        assertTrue(russian.contains("<string name=\"cli_prof_test_button\">ТЕСТ</string>"))
        assertTrue(russian.contains("<string name=\"cli_prof_stop_test_button\">ЗАВЕРШИТЬ ТЕСТ</string>"))
        assertFalse(source.contains("+ \"…\""))
        assertTrue(source.contains("SmartProfileTestAction.STOP -> viewModel.cancelSmartProfileMetricsRefresh()"))
        assertTrue(source.contains("viewModel.refreshSmartProfileMetricsInVpnMode(profileId)"))
    }

    @Test
    fun `smart profile sheet shares framed modal chrome and pins the profile table header`() {
        val sheet = findFile(
            "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliSmartProfileSheet.kt",
        ).readText()
        val protocolTable = findFile(
            "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProtocolDropdown.kt",
        ).readText()
        val dropdown = protocolTable
            .substringAfter("internal fun CliProtocolDropdown(")
            .substringBefore("@Composable\ninternal fun CliProtocolTableHeader(")
        val sharedSheet = findFile(
            "src/main/kotlin/com/foxhole/guard/ui/cli/components/CliBottomSheet.kt",
        ).readText()
        val english = findFile("src/main/res/values/strings.xml").readText()
        val russian = findFile("src/main/res/values-ru/strings.xml").readText()

        assertTrue(sheet.contains("CliBottomSheet("))
        assertTrue(sheet.contains("sheetGesturesEnabled = false"))
        assertTrue(sheet.contains("contentScrollEnabled = false"))
        assertTrue(sheet.contains("CliIconTextItems("))
        assertTrue(sheet.contains("framed = true"))
        assertTrue(sheet.contains("iconColor = colors.info"))
        assertTrue(sheet.contains("CliPanel("))
        assertTrue(sheet.contains("contentPadding = CliPanelEdgeToEdgeContentPadding"))
        assertTrue(sheet.contains("Spacer(modifier = Modifier.height(CliSpacing.sm))"))
        assertTrue(sheet.contains("marquee = true"))
        assertTrue(protocolTable.contains("basicMarquee"))
        assertFalse(protocolTable.contains("CLI_PROTOCOL_MARQUEE"))
        assertFalse(dropdown.contains("animateItem"))
        assertTrue(dropdown.contains("LazyColumn("))
        assertTrue(dropdown.contains("itemsIndexed("))
        assertTrue(
            dropdown.contains(
                "nameLabel = stringResource(R.string.cli_prof_table_profile_name)",
            ),
        )
        assertTrue(
            dropdown.indexOf("CliProtocolTableHeader(") < dropdown.indexOf("LazyColumn("),
        )
        assertTrue(protocolTable.contains("CLI_PROTO_HEADER_CONNECT = \"T\""))
        assertTrue(protocolTable.contains("CLI_PROTO_HEADER_PING = \"P\""))
        assertTrue(protocolTable.contains("CLI_PROTO_HEADER_LATENCY = \"L\""))
        assertTrue(english.contains("name=\"cli_prof_table_profile_name\">Profile name</string>"))
        assertTrue(russian.contains("name=\"cli_prof_table_profile_name\">Имя профиля</string>"))
        assertTrue(sheet.contains("onOptionSelected = requestSheetDismiss"))
        assertTrue(sheet.contains("viewModel.setSmartProfileProtocolEnabled"))
        assertTrue(sharedSheet.contains("contentScrollEnabled: Boolean = true"))
        assertFalse(sharedSheet.contains(".cliModalContentEnter()"))
        assertTrue(sharedSheet.contains("sheetState.hide()"))
    }

    private fun findFile(relative: String): File =
        listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .first(File::isFile)
}
