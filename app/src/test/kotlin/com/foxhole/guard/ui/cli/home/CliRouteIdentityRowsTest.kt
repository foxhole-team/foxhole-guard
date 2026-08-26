package com.foxhole.guard.ui.cli.home

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.TorIdentityProbePhase
import com.foxhole.guard.ui.cli.cliPixelFontSizeForMonoSp
import com.foxhole.guard.ui.cli.cliTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliRouteIdentityRowsTest {
    @Test
    fun `vpn plus tor never labels the confirmed tor exit as vpn identity`() {
        val torExit = ipInfo(ip = "9.9.9.9", countryCode = "DE")
        val vpnExit = ipInfo(ip = "1.1.1.1", countryCode = "NL")
        val vpnTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = true),
                ipInfo = torExit,
                torIpInfo = torExit,
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )

        val facts =
            cliRouteIdentityFactStates(
                home = vpnTor,
                runtimes = activeRuntimes(vpnTor, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.CONFIRMED,
            )

        assertNull(facts.single { it.identity.kind == CliRouteIdentityKind.VPN }.identity.info)
        assertEquals(torExit, facts.single { it.identity.kind == CliRouteIdentityKind.TOR }.identity.info)
        assertEquals(
            vpnExit,
            distinctVpnRouteIdentity(vpnExit, torExit, torRouteObserved = true),
        )
    }

    @Test
    fun `identity loads inline while dns stays visible and latency determines after connect`() {
        val rows = source("home/CliRouteIdentityRows.kt")
        val facts = source("home/CliHomeFacts.kt")
        val identity = rows
            .substringAfter("private fun CliRouteIdentityRow(")
            .substringBefore("internal fun cliRightAlignedLoadingContent")
        val dns = facts
            .substringAfter("private fun CliDnsServerFact")
            .substringBefore("internal fun cliPublicDnsIdentityValue")
        val latency = facts
            .substringAfter("val latencyMs = cliHomeLatencyMs(home, connected)")
            .substringBefore("CliSpeedFact(")

        assertTrue(identity.contains("CliKeyValue("))
        assertTrue(identity.contains("cliRightAlignedLoadingContent("))
        assertTrue(identity.contains("CliRightAlignedRefreshTrailing("))
        assertTrue(identity.contains("value = if (loading) \"\" else value"))
        assertTrue(identity.contains("animateValue = animateValue && !loading"))
        assertTrue(identity.contains("valueMaxLines = IDENTITY_VALUE_MAX_LINES"))
        assertTrue(dns.contains("home.publicDnsIdentity"))
        assertTrue(dns.contains("cli_home_dns_not_determined"))
        assertTrue(dns.contains("CliShimmerText("))
        assertFalse(dns.contains("loading: Boolean"))
        assertFalse(dns.contains("cliRightAlignedLoadingContent("))
        assertTrue(latency.contains("latencySpinnerVisible || latencyDetermining -> \"\""))
        assertTrue(latency.contains("CliMetricSpinner()"))
        assertTrue(latency.contains("CliShimmerText("))
        assertFalse(latency.contains("cliRightAlignedLoadingContent("))
        assertTrue(cliRightAlignedLoadingUsesText(loading = true))
        assertFalse(cliRightAlignedLoadingUsesText(loading = false))

        assertFalse(rows.contains("targetState = loading,"))
        assertTrue(rows.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue(rows.contains("CliSpinner()"))
        assertTrue(rows.contains("Spacer(modifier = Modifier.width(CliSpacing.xs))"))
        assertTrue(rows.contains("plainLoadingIndicator = false"))
        assertTrue(rows.contains("loading = identityLoading || torLoading"))
        assertTrue(rows.contains("if (cliRefreshTrailingVisible(loading, countryCode))"))
        assertTrue(dns.contains("if (cliFlagCode(dnsCountryCode) != null)"))
        assertTrue(rows.contains("visible = torFact != null"))
        assertTrue(rows.contains("enter = cliVerticalEnter()"))
        assertTrue(rows.contains("exit = cliVerticalExit()"))

        val factsSurface = source("home/CliHomeScreen.kt")
            .substringAfter("CliProfileAreaSurface.FACTS ->")
            .substringBefore("\n        }")
        assertFalse(
            "the parent must not animate the same TOR height a second time",
            factsSurface.contains("animateContentSize"),
        )
    }

    @Test
    fun `the whole facts panel opens profiles on tap and refreshes only on hold`() {
        val screen = source("home/CliHomeScreen.kt")
        val facts = source("home/CliHomeFacts.kt")
        val rows = source("home/CliRouteIdentityRows.kt")
        val networkBlock = facts
            .substringAfter("val identityLoading = cliHomeIdentityLoading(home)")
            .substringBefore("val latencyMs = cliHomeLatencyMs(home, connected)")
        val dns = facts
            .substringAfter("private fun CliDnsServerFact")
            .substringBefore("internal fun cliPublicDnsIdentityValue")
        val refresh = File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelIpRefreshSupport.kt").readText()
        val manualRefresh = refresh
            .substringAfter("internal fun HomeViewModel.refreshIpInfo()")
            .substringBefore("internal fun HomeViewModel.refreshIpInfoSilently()")
        val geoRefreshAction = screen
            .substringAfter("return CliProfileGeoRefreshActions(")
            .substringBefore("private fun CliClearTerminalSheet(")

        assertTrue(screen.contains("viewModel.refreshIpInfo()"))
        assertTrue(screen.contains("R.string.cli_cmd_update_geodata"))
        assertTrue(geoRefreshAction.contains("terminal.command(refreshCommand)"))
        assertTrue(screen.contains("terminal.beginGeoRefresh(refreshingMessage)"))
        assertTrue(screen.contains("terminal.finishGeoRefresh()"))
        assertTrue(screen.contains("R.string.cli_home_network_geo_refreshing"))
        assertTrue(
            geoRefreshAction.indexOf("terminal.command(refreshCommand)") <
                geoRefreshAction.indexOf("terminal.beginGeoRefresh(refreshingMessage)"),
        )
        assertFalse(networkBlock.contains("cliCombinedPressable"))
        assertTrue(facts.contains("onLongClick = onProfileHold"))
        assertTrue(facts.contains("onClick = onProfileTap"))
        assertTrue(networkBlock.contains("CliRouteIdentityFacts("))
        assertTrue(networkBlock.contains("CliDnsServerFact("))
        assertFalse(rows.contains("onRefresh"))
        assertFalse(rows.contains("cliCombinedPressable"))
        assertFalse(dns.contains("onRefresh"))
        assertFalse(dns.contains("cliCombinedPressable"))
        assertTrue(facts.contains("home.ipInfoLoading ||"))
        assertTrue(manualRefresh.contains("showLoading = true"))
        assertTrue(manualRefresh.contains("minimumLoadingDurationMs = HomeViewModel.MANUAL_IP_REFRESH_MIN_LOADING_MS"))
        assertTrue(rows.contains("value = if (loading) \"\" else value"))
        assertTrue(rows.contains("loading = identityLoading || torLoading"))
    }

    @Test
    fun `identity rows reuse the shared key value edge alignment`() {
        val rows = source("home/CliRouteIdentityRows.kt")
        val identity = rows
            .substringAfter("private fun CliRouteIdentityRow(")
            .substringBefore("internal fun cliRightAlignedLoadingContent")

        assertTrue(identity.contains("CliKeyValue("))
        assertTrue(identity.contains("keyColumnWeight = ROUTE_IDENTITY_KEY_WEIGHT"))
        assertTrue(identity.contains("valueColumnWeight = ROUTE_IDENTITY_VALUE_WEIGHT"))
        assertTrue(rows.contains("ROUTE_IDENTITY_KEY_WEIGHT = 0.65f"))
        assertTrue(rows.contains("ROUTE_IDENTITY_VALUE_WEIGHT = 1.35f"))
        assertTrue(rows.contains("private fun CliRouteIdentityRow("))
        assertFalse(rows.contains("basicMarquee"))
    }

    @Test
    fun `identity values stay on one line while retaining the full source text`() {
        val longest = cliRouteIdentityValue(
            ipInfo(
                ip = "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
                countryCode = "nl",
                city = "'s-Hertogenbosch",
            ),
        )

        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334\u2009·\u2009's-Hertogenbosch\u2009·\u2009NL", longest)
        assertEquals(
            "NL",
            cliRouteIdentityCountryLabel(
                ipInfo(
                    ip = "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
                    countryCode = "nl",
                    city = "'s-Hertogenbosch",
                ),
            ),
        )
        val rows = source("home/CliRouteIdentityRows.kt")
        assertTrue(rows.contains("valueMaxLines = IDENTITY_VALUE_MAX_LINES"))
        assertTrue(rows.contains("IDENTITY_VALUE_MAX_LINES = 1"))
    }

    @Test
    fun `Home status rows match the console text metrics`() {
        val facts = source("home/CliHomeFacts.kt")
        val sectionStyle = source("home/CliHomeSectionStyle.kt")
        val home = source("home/CliHomeScreen.kt")
        val terminal = source("home/CliHomeTerminal.kt")
        val type = cliTypography()
        val factsTypography = cliHomeFactsTypographyFor(type)
        val headingTypography = cliHomeSectionTypographyFor(type)
        val pixelHeaderMetrics = cliHomeHeaderMetrics(pixelArtEnabled = true)
        val monoHeaderMetrics = cliHomeHeaderMetrics(pixelArtEnabled = false)

        assertTrue(facts.contains("CliHomeFactsMetrics {"))
        assertTrue(facts.contains("LocalCliType provides factsTypography"))
        assertTrue(sectionStyle.contains("x = (-4).dp"))
        assertTrue(sectionStyle.contains("CliHomeSectionTypography"))
        assertTrue(sectionStyle.contains("LocalCliPixelArtEnabled.current"))
        assertTrue(sectionStyle.contains("LocalCliIconMetricOverrides provides homeHeaderMetrics"))
        assertTrue(sectionStyle.contains("PlatformTextStyle(includeFontPadding = false)"))
        assertTrue(facts.contains("cliHomeConsoleTextStyleFor(typography)"))
        assertEquals(2, facts.split("CliHomeSectionTypography {").size - 1)
        assertTrue(home.contains("CliHomeSectionTypography {"))
        assertTrue(terminal.contains("CliHomeSectionTypography {"))
        assertEquals((-1).dp, CLI_HOME_SECTION_HEADING_OFFSET)
        assertEquals(type.title.fontSize, headingTypography.title.fontSize)
        assertEquals(type.title.lineHeight, headingTypography.title.lineHeight)
        assertEquals(CLI_HOME_PLATFORM_STYLE, headingTypography.title.platformStyle)
        assertEquals(14.sp, headingTypography.body.fontSize)
        assertEquals(CLI_HOME_PLATFORM_STYLE, headingTypography.body.platformStyle)
        assertEquals(12.sp, headingTypography.small.fontSize)
        assertEquals(CLI_HOME_PLATFORM_STYLE, headingTypography.small.platformStyle)
        assertEquals(type.title.fontFamily, headingTypography.title.fontFamily)
        assertEquals(type.title.fontWeight, headingTypography.title.fontWeight)
        assertEquals(16.dp, pixelHeaderMetrics.panelHeaderIconSize)
        assertEquals(16.dp, monoHeaderMetrics.panelHeaderIconSize)
        assertEquals(0.dp, pixelHeaderMetrics.panelHeaderLeadingIconLiftAdjustment)
        assertEquals(0.dp, monoHeaderMetrics.panelHeaderLeadingIconLiftAdjustment)
        assertFalse(pixelHeaderMetrics.panelHeaderInfoAtEnd)
        assertFalse(monoHeaderMetrics.panelHeaderInfoAtEnd)
        assertEquals(1.dp, CLI_HOME_STATUS_HEADER_DROP)
        assertEquals(cliPixelFontSizeForMonoSp(14f), pixelHeaderMetrics.panelHeaderFontSize)
        assertEquals(14.sp, monoHeaderMetrics.panelHeaderFontSize)
        assertEquals(19.sp, pixelHeaderMetrics.panelHeaderLineHeight)
        assertEquals(19.sp, monoHeaderMetrics.panelHeaderLineHeight)
        assertEquals(headingTypography.small, factsTypography.body)
        assertEquals(headingTypography.small, factsTypography.small)
        assertEquals((-1).dp, CLI_HOME_VPN_DISCLOSURE_LIFT)
    }

    @Test
    fun `identity values are typed in rather than snapped`() {
        val rows = source("home/CliRouteIdentityRows.kt")
        assertEquals(2, rows.split("animateValue = true").size - 1)

        val typewriter = source("components/CliTypewriterText.kt")
        assertTrue(typewriter.contains("internal fun rememberCliTypedText("))
        assertTrue(typewriter.contains("LaunchedEffect(text, enabled)"))
        assertTrue(typewriter.contains("displayed.commonPrefixWith(text).length"))

        val rowComponent = source("components/CliText.kt")
        assertTrue(rowComponent.contains("animateValue: Boolean = false"))
        assertTrue(
            rowComponent.contains("rememberCliTypedText(cliLabelText(value), enabled = animateValue)"),
        )
    }

    @Test
    fun `vpn and tor identities stay separate in a combined route`() {
        val vpn = ipInfo(ip = "198.51.100.10", countryCode = "us", city = "New York")
        val tor = ipInfo(ip = "203.0.113.20", countryCode = "de")
        val identities =
            cliRouteIdentities(
                home = HomeRouteUiState(ipInfo = vpn, torIpInfo = tor),
                runtimes = CliActiveRuntimes(vpn = true, proxy = false, tor = true, torBesideVpn = false, i2p = false),
            )

        assertEquals(
            listOf(
                CliRouteIdentity(CliRouteIdentityKind.VPN, vpn),
                CliRouteIdentity(CliRouteIdentityKind.TOR, tor.copy(countryCode = "DE")),
            ),
            identities,
        )
        assertEquals("198.51.100.10\u2009·\u2009New York\u2009·\u2009US", cliRouteIdentityValue(vpn))
        assertEquals("US", cliRouteIdentityCountryLabel(vpn))
        assertEquals("203.0.113.20\u2009·\u2009DE", cliRouteIdentityValue(tor, includeCity = false))
    }

    @Test
    fun `missing identity remains an explicit placeholder`() {
        assertEquals("—", cliRouteIdentityValue(null))
    }

    @Test
    fun `dns identity keeps address protocol and country order`() {
        assertEquals("1.1.1.1\u2009·\u2009DoH\u2009·\u2009US", cliDnsIdentityValue("DoH", "us", "1.1.1.1"))
        assertEquals("dns.example\u2009·\u2009DoT", cliDnsIdentityValue("DoT", null, "dns.example"))
        assertEquals("192.0.2.53\u2009·\u2009FI", cliPublicDnsIdentityValue("192.0.2.53", "fi"))
        assertEquals("—", cliPublicDnsIdentityValue(null, "fi"))
        assertTrue(cliRefreshTrailingVisible(loading = true, countryCode = null))
        assertTrue(cliRefreshTrailingVisible(loading = false, countryCode = "us"))
        assertFalse(cliRefreshTrailingVisible(loading = false, countryCode = null))
        assertFalse(cliRefreshTrailingVisible(loading = false, countryCode = "unknown"))
    }

    @Test
    fun `dns hides a stale resolved identity throughout connection transitions`() {
        listOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING).forEach { state ->
            assertTrue(cliHomeDnsUpdating(state, com.foxhole.guard.ui.PublicDnsIdentityPhase.RESOLVED))
        }
        assertFalse(
            cliHomeDnsUpdating(
                ConnectionState.CONNECTED,
                com.foxhole.guard.ui.PublicDnsIdentityPhase.RESOLVED,
            ),
        )
        assertTrue(
            cliHomeDnsUpdating(
                ConnectionState.CONNECTED,
                com.foxhole.guard.ui.PublicDnsIdentityPhase.LOADING,
            ),
        )
    }

    @Test
    fun `tor identity activity follows runtime and probe truth only`() {
        val absent: IpInfo? = null
        assertFalse(
            cliTorIdentityLoading(TorPhaseSnapshot(), TorIdentityProbePhase.IDLE, absent),
        )
        assertTrue(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTING),
                TorIdentityProbePhase.IDLE,
                absent,
            ),
        )
        assertTrue(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.BUILDING_CIRCUITS),
                TorIdentityProbePhase.LOOKING_UP,
                absent,
            ),
        )
        assertTrue(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.LOOKING_UP,
                absent,
            ),
        )
        assertFalse(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.FAILED,
                absent,
            ),
        )
        assertFalse(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.CANCELLED,
                absent,
            ),
        )
        assertFalse(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.CONFIRMED,
                ipInfo(ip = "203.0.113.20", countryCode = "de"),
            ),
        )
    }

    @Test
    fun `vpn tor tunnel and proxy keep separate live spinners until each identity arrives`() {
        listOf(TrafficMode.TUNNEL, TrafficMode.PROXY).forEach { trafficMode ->
            val connecting =
                HomeRouteUiState(
                    connection = route(ConnectionState.CONNECTING, trafficMode, torActive = true),
                    torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTING),
                )
            val connectingFacts =
                cliRouteIdentityFactStates(
                    home = connecting,
                    runtimes = activeRuntimes(connecting, torOnlyLive = false),
                    identityLoading = true,
                    torIdentityProbePhase = TorIdentityProbePhase.IDLE,
                )

            assertEquals(
                "$trafficMode connecting slots",
                listOf(CliRouteIdentityKind.VPN, CliRouteIdentityKind.TOR),
                connectingFacts.map { it.identity.kind },
            )
            assertTrue("$trafficMode VPN identity must be pending", connectingFacts[0].loading)
            assertTrue("$trafficMode TOR phase must be pending", connectingFacts[1].loading)

            val vpnProven =
                connecting.copy(
                    connection = route(ConnectionState.CONNECTED, trafficMode, torActive = true),
                    ipInfo = ipInfo(ip = "198.51.100.10", countryCode = "us"),
                    torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                )
            val vpnProvenFacts =
                cliRouteIdentityFactStates(
                    home = vpnProven,
                    runtimes = activeRuntimes(vpnProven, torOnlyLive = false),
                    identityLoading = false,
                    torIdentityProbePhase = TorIdentityProbePhase.LOOKING_UP,
                )

            assertFalse("$trafficMode VPN spinner must end with identity", vpnProvenFacts[0].loading)
            assertTrue("$trafficMode TOR spinner must wait for exit identity", vpnProvenFacts[1].loading)

            val bothProven =
                vpnProven.copy(torIpInfo = ipInfo(ip = "203.0.113.20", countryCode = "de"))
            val bothProvenFacts =
                cliRouteIdentityFactStates(
                    home = bothProven,
                    runtimes = activeRuntimes(bothProven, torOnlyLive = false),
                    identityLoading = false,
                    torIdentityProbePhase = TorIdentityProbePhase.CONFIRMED,
                )
            assertFalse(bothProvenFacts.any(CliRouteIdentityFactState::loading))
        }
    }

    @Test
    fun `desired tor setting does not expose an unapplied tor row`() {
        val starting =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTING, TrafficMode.TUNNEL, torActive = false),
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN),
                ),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )

        val facts =
            cliRouteIdentityFactStates(
                home = starting,
                runtimes = activeRuntimes(starting, torOnlyLive = false),
                identityLoading = true,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )

        assertEquals(
            listOf(CliRouteIdentityKind.VPN),
            facts.map { it.identity.kind },
        )
    }

    @Test
    fun `stale tor flag without an applied route cannot keep the vpn tor row alive`() {
        val vpnOnly =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = true)
                    .copy(appliedTorRoute = null),
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN),
                ),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )

        val facts =
            cliRouteIdentityFactStates(
                home = vpnOnly,
                runtimes = activeRuntimes(vpnOnly, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.CONFIRMED,
            )

        assertEquals(listOf(CliRouteIdentityKind.VPN), facts.map { it.identity.kind })
    }

    @Test
    fun `live vpn tor to vpn transition removes the tor row while permission stays granted`() {
        val vpnTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = true),
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        permitted = true,
                    ),
                ),
            )
        val vpnOnly =
            vpnTor.copy(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = false),
                settings = vpnTor.settings.copy(
                    privacyRoute = vpnTor.settings.privacyRoute.copy(mode = PrivacyRouteMode.OFF),
                ),
            )

        val kindsByAppliedSnapshot = listOf(vpnTor, vpnOnly).map { home ->
            cliRouteIdentityFactStates(
                home = home,
                runtimes = activeRuntimes(home, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            ).map { fact -> fact.identity.kind }
        }

        assertEquals(
            listOf(
                listOf(CliRouteIdentityKind.VPN, CliRouteIdentityKind.TOR),
                listOf(CliRouteIdentityKind.VPN),
            ),
            kindsByAppliedSnapshot,
        )
        val rows = source("home/CliRouteIdentityRows.kt")
        assertTrue(rows.contains("visible = torFact != null"))
        assertTrue(rows.contains("exit = cliVerticalExit()"))
    }

    @Test
    fun `tor only starts loading before its phase and configured idle mode stays generic`() {
        val torOnly =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.OFFLINE),
            )
        val torOnlyFacts =
            cliRouteIdentityFactStates(
                home = torOnly,
                runtimes = activeRuntimes(torOnly, torOnlyLive = false),
                identityLoading = true,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )
        assertEquals(listOf(CliRouteIdentityKind.TOR), torOnlyFacts.map { it.identity.kind })
        assertTrue(torOnlyFacts.single().loading)

        val configuredOnly =
            HomeRouteUiState(
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        permitted = true,
                    ),
                ),
            )
        val configuredFacts =
            cliRouteIdentityFactStates(
                home = configuredOnly,
                runtimes = activeRuntimes(configuredOnly, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.LOOKING_UP,
            )
        assertTrue(configuredFacts.isEmpty())
    }

    @Test
    fun `permitted tor module hides its row in vpn mode and keeps it in tor routes`() {
        val vpnMode =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = false),
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.OFF,
                        permitted = true,
                    ),
                ),
            )
        val torMode =
            vpnMode.copy(
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
            )
        val desiredCombinedMode =
            vpnMode.copy(
                connection = route(ConnectionState.CONNECTING, TrafficMode.TUNNEL, torActive = false),
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        permitted = true,
                    ),
                ),
            )

        val kindsByMode =
            listOf(vpnMode, torMode, desiredCombinedMode).map { home ->
                cliRouteIdentityFactStates(
                    home = home,
                    runtimes = activeRuntimes(home, torOnlyLive = false),
                    identityLoading = false,
                    torIdentityProbePhase = TorIdentityProbePhase.IDLE,
                ).map { fact -> fact.identity.kind }
            }

        assertEquals(
            listOf(
                listOf(CliRouteIdentityKind.VPN),
                listOf(CliRouteIdentityKind.TOR),
                listOf(CliRouteIdentityKind.VPN),
            ),
            kindsByMode,
        )
    }

    @Test
    fun `disconnected desired routes keep the same generic identity row`() {
        val vpnDesired =
            HomeRouteUiState(
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.OFF,
                        permitted = true,
                    ),
                ),
            )
        val torDesired =
            vpnDesired.copy(
                settings = vpnDesired.settings.copy(
                    privacyRoute = vpnDesired.settings.privacyRoute.copy(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                    ),
                ),
            )
        val directTorDesired =
            torDesired.copy(
                settings = torDesired.settings.copy(
                    privacyRoute = torDesired.settings.privacyRoute.copy(bypassVpnTunnel = true),
                ),
            )

        listOf(vpnDesired, torDesired, directTorDesired).forEach { home ->
            val facts = cliRouteIdentityFactStates(
                home = home,
                runtimes = activeRuntimes(home, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )

            assertTrue(facts.isEmpty())
        }

        val rows = source("home/CliRouteIdentityRows.kt")
        val stateBuilder = rows
            .substringAfter("internal fun cliRouteIdentityFactStates(")
            .substringBefore("@Composable\ninternal fun CliRouteIdentityFacts")
        assertFalse(stateBuilder.contains("privacyRoute"))
        assertTrue(rows.contains("val showGenericIp = liveFacts.isEmpty()"))
    }

    @Test
    fun `identity labels follow the observed route and never the desired route`() {
        val disconnected =
            HomeRouteUiState(
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        permitted = true,
                    ),
                ),
            )
        val vpnOnly =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = false),
            )
        val torOnly =
            HomeRouteUiState(
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
            )
        val vpnTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = true),
            )

        val labels = listOf(disconnected, vpnOnly, torOnly, vpnTor).map { home ->
            val facts = cliRouteIdentityFactStates(
                home = home,
                runtimes = activeRuntimes(home, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )
            if (facts.isEmpty()) {
                listOf("IP info")
            } else {
                facts.map { fact ->
                    when (fact.identity.kind) {
                        CliRouteIdentityKind.VPN -> "VPN IP"
                        CliRouteIdentityKind.TOR -> "Tor IP"
                    }
                }
            }
        }

        assertEquals(
            listOf(
                listOf("IP info"),
                listOf("VPN IP"),
                listOf("Tor IP"),
                listOf("VPN IP", "Tor IP"),
            ),
            labels,
        )
    }

    @Test
    fun `tor probe failure and offline phase always clear identity spinner`() {
        val connectedTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.PROXY, torActive = true),
                ipInfo = ipInfo(ip = "198.51.100.10", countryCode = "us"),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )
        val failed =
            cliRouteIdentityFactStates(
                home = connectedTor,
                runtimes = activeRuntimes(connectedTor, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.FAILED,
            )
        assertFalse(failed.single { it.identity.kind == CliRouteIdentityKind.TOR }.loading)

        val offline = connectedTor.copy(torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.OFFLINE))
        val offlineFacts =
            cliRouteIdentityFactStates(
                home = offline,
                runtimes = activeRuntimes(offline, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )
        assertFalse(offlineFacts.single { it.identity.kind == CliRouteIdentityKind.TOR }.loading)
    }

    @Test
    fun `latency spinner is limited to connect while refresh uses determining text`() {
        val vpnRefreshing =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 7L),
                dashboardConnectionMetricsLoading = true,
            )
        val vpnTorRefreshing =
            vpnRefreshing.copy(connection = vpnRefreshing.connection.copy(torActive = true))
        val connecting =
            vpnRefreshing.copy(
                connection = vpnRefreshing.connection.copy(state = ConnectionState.CONNECTING),
                dashboardConnectionMetricsLoading = false,
            )
        val reconnecting =
            connecting.copy(connection = connecting.connection.copy(state = ConnectionState.RECONNECTING))

        assertFalse(cliHomeLatencyLoading(vpnRefreshing))
        assertFalse(cliHomeLatencyLoading(vpnTorRefreshing))
        assertTrue(cliHomeLatencyLoading(connecting))
        assertTrue(cliHomeLatencyLoading(reconnecting))
        assertFalse(cliHomeLatencyLoading(HomeRouteUiState()))
        assertTrue(cliHomeLatencyDetermining(vpnRefreshing, connected = true, latencyMs = null))
        assertTrue(cliHomeLatencyDetermining(vpnTorRefreshing, connected = true, latencyMs = 24L))
        assertFalse(cliHomeLatencyDetermining(connecting, connected = false, latencyMs = null))
    }

    @Test
    fun `tor only latency uses determining text without a spinner`() {
        val sentinelTorOnly =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
                dashboardConnectionMetricsLoading = true,
            )
        val legacyTorOnly =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                dashboardConnectionMetricsLoading = true,
            )

        listOf(sentinelTorOnly to true, legacyTorOnly to false).forEach { (home, connected) ->
            assertFalse(cliHomeLatencyLoading(home))
            assertTrue(cliHomeLatencyUnavailable(home, connected))
            assertEquals(
                connected,
                cliHomeLatencyDetermining(home, connected, latencyMs = null),
            )
        }
    }

    @Test
    fun `vpn and vpn tor display the selected public route probe`() {
        val profile = latencyProfile()
        val vpn =
            HomeRouteUiState(
                activeProfile = profile,
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = profile.id,
                    protocolOptionId = "vless",
                ),
                protocolTunnelPingsByOptionId = mapOf("vless" to 84L),
            )
        val vpnTor = vpn.copy(connection = vpn.connection.copy(torActive = true))

        assertEquals(84L, cliHomeLatencyMs(vpn, connected = true))
        assertEquals(84L, cliHomeLatencyMs(vpnTor, connected = true))
        assertFalse(cliHomeLatencyUnavailable(vpn, connected = true))
        assertFalse(cliHomeLatencyUnavailable(vpnTor, connected = true))
    }

    @Test
    fun `failed bounded vpn probe becomes unavailable after loading lease ends`() {
        val profile = latencyProfile()
        val failed =
            HomeRouteUiState(
                activeProfile = profile,
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = profile.id,
                    protocolOptionId = "vless",
                ),
                protocolTunnelPingUnavailableOptionIds = setOf("vless"),
            )

        assertTrue(cliHomeLatencyUnavailable(failed, connected = true))
        assertFalse(
            cliHomeLatencyUnavailable(
                failed.copy(dashboardConnectionMetricsLoading = true),
                connected = true,
            ),
        )
        assertFalse(cliHomeLatencyUnavailable(failed, connected = false))
    }

    @Test
    fun `disconnect clears tor only latency unavailable presentation`() {
        val disconnected =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
            )

        assertFalse(cliHomeLatencyLoading(disconnected))
        assertFalse(cliHomeLatencyUnavailable(disconnected, connected = false))
        assertFalse(cliHomeLatencyDetermining(disconnected, connected = false, latencyMs = null))
    }

    @Test
    fun `vpn plus tor exposes both real identity rows`() {
        val vpnTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = true),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )
        val facts =
            cliRouteIdentityFactStates(
                home = vpnTor,
                runtimes = activeRuntimes(vpnTor, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.CONFIRMED,
            )

        assertEquals(2, facts.size)
        assertEquals(
            listOf(CliRouteIdentityKind.VPN, CliRouteIdentityKind.TOR),
            facts.map { fact -> fact.identity.kind },
        )
    }

    @Test
    fun `identity rows do not reserve an invisible tor line and spinner text swaps share one row floor`() {
        val identity = source("home/CliRouteIdentityRows.kt")
        val keyValue = source("components/CliText.kt")
        val spinner = source("components/CliSpinner.kt")
        val keyValueBlock =
            keyValue
                .substringAfter("internal fun CliKeyValue(")
                .substringBefore("internal fun CliElbowLine(")

        assertFalse(identity.contains("CliRouteIdentitySizingGhost"))
        assertFalse(identity.contains("CLI_ROUTE_IDENTITY_RESERVED_ROW_COUNT"))
        assertTrue(spinner.contains("internal val cliSpinnerSlotSize = 16.dp"))
        assertTrue(keyValueBlock.contains(".defaultMinSize(minHeight = cliSpinnerSlotSize)"))
    }

    private fun ipInfo(
        ip: String,
        countryCode: String,
        city: String? = null,
    ) = IpInfo(
        ip = ip,
        countryCode = countryCode,
        countryName = null,
        city = city,
        isp = null,
        fetchedAt = 0L,
    )

    private fun route(
        state: ConnectionState,
        trafficMode: TrafficMode,
        torActive: Boolean,
    ) = ConnectionSnapshot(
        state = state,
        trafficMode = trafficMode,
        profileId = 7L,
        profileName = "fox",
        torActive = torActive,
        appliedTorRoute = if (torActive) {
            AppliedTorRoute(
                scope = com.foxhole.core.model.PrivacyRouteScope.SELECTED_APPS,
                bypassVpnTunnel = false,
            )
        } else {
            null
        },
    )

    private fun latencyProfile(): Profile =
        Profile(
            id = 7L,
            name = "VPN",
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            secretRef = "secret",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions =
            listOf(
                ProfileProtocolOption(
                    id = "vless",
                    displayName = "VLESS",
                    protocolHint = ProtocolHint.VLESS,
                    isSelected = true,
                ),
            ),
            selectedProtocolOptionId = "vless",
            isActive = true,
        )

    private fun source(relative: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative").readText()
}
